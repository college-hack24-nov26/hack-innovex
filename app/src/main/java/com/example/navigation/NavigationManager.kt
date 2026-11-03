package com.example.navigation

import android.annotation.SuppressLint
import android.content.ClipData
import android.content.ClipboardManager
import android.content.Context
import android.content.Intent
import android.location.Geocoder
import android.location.Location
import android.net.Uri
import android.os.Looper
import android.util.Log
import com.example.model.ManeuverType
import com.example.model.NavStep
import com.example.model.NavigationData
import com.google.android.gms.location.FusedLocationProviderClient
import com.google.android.gms.location.LocationCallback
import com.google.android.gms.location.LocationRequest
import com.google.android.gms.location.LocationResult
import com.google.android.gms.location.LocationServices
import com.google.android.gms.location.Priority
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.util.Locale
import kotlin.math.atan2
import kotlin.math.cos
import kotlin.math.max
import kotlin.math.sin
import kotlin.math.sqrt

class NavigationManager(private val context: Context) {

    private val tag = "NavigationManager"
    private val scope = CoroutineScope(Dispatchers.IO)
    private val fusedLocationClient: FusedLocationProviderClient =
        LocationServices.getFusedLocationProviderClient(context)

    private val geocoder = Geocoder(context, Locale.getDefault())

    private val _navState = MutableStateFlow(NavigationData())
    val navState: StateFlow<NavigationData> = _navState.asStateFlow()

    private var locationCallback: LocationCallback? = null
    private var isLocationTrackingStarted = false

    private var onInstructionAnnounce: ((String) -> Unit)? = null
    private var onArrivalAnnounce: (() -> Unit)? = null

    fun setListeners(
        onInstruction: (String) -> Unit,
        onArrival: () -> Unit
    ) {
        this.onInstructionAnnounce = onInstruction
        this.onArrivalAnnounce = onArrival
    }

    @SuppressLint("MissingPermission")
    fun startLocationUpdates() {
        if (isLocationTrackingStarted) return
        isLocationTrackingStarted = true

        // Instantly obtain last known GPS fix
        try {
            fusedLocationClient.lastLocation.addOnSuccessListener { loc ->
                if (loc != null) {
                    onNewLocation(loc)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Last location fetch error: ${e.message}")
        }

        val locationRequest = LocationRequest.Builder(Priority.PRIORITY_HIGH_ACCURACY, 2000L)
            .setMinUpdateIntervalMillis(1000L)
            .setMinUpdateDistanceMeters(1.0f)
            .build()

        locationCallback = object : LocationCallback() {
            override fun onLocationResult(result: LocationResult) {
                val location = result.lastLocation ?: return
                onNewLocation(location)
            }
        }

        try {
            fusedLocationClient.requestLocationUpdates(
                locationRequest,
                locationCallback as LocationCallback,
                Looper.getMainLooper()
            )
        } catch (e: SecurityException) {
            Log.e(tag, "Location permission missing: ${e.message}")
        }
    }

    fun stopLocationUpdates() {
        locationCallback?.let {
            fusedLocationClient.removeLocationUpdates(it)
        }
        isLocationTrackingStarted = false
    }

    private fun onNewLocation(location: Location) {
        scope.launch {
            val resolvedAddress = resolveHumanAddress(location.latitude, location.longitude)

            withContext(Dispatchers.Main) {
                val updated = _navState.value.copy(
                    userLatitude = location.latitude,
                    userLongitude = location.longitude,
                    currentSpeedKmH = location.speed * 3.6f,
                    currentHeading = location.bearing,
                    currentStreet = if (resolvedAddress.isNotBlank()) resolvedAddress else _navState.value.currentStreet,
                    googleMapsUrl = "https://www.google.com/maps/search/?api=1&query=${location.latitude},${location.longitude}"
                )
                _navState.value = updated

                if (updated.isNavigating) {
                    processNavStepProgress(location)
                }
            }
        }
    }

    private suspend fun resolveHumanAddress(latitude: Double, longitude: Double): String = withContext(Dispatchers.IO) {
        try {
            @Suppress("DEPRECATION")
            val addresses = geocoder.getFromLocation(latitude, longitude, 3)
            val best = addresses?.firstOrNull()
            if (best != null) {
                val parts = mutableListOf<String>()
                val feature = best.featureName
                val thoroughfare = best.thoroughfare
                val subLocality = best.subLocality
                val locality = best.locality
                val adminArea = best.adminArea

                if (!feature.isNullOrBlank() && feature != thoroughfare && !feature.matches(Regex("^[0-9\\+\\-]+$"))) {
                    parts.add(feature)
                }
                if (!thoroughfare.isNullOrBlank() && !parts.contains(thoroughfare)) {
                    parts.add(thoroughfare)
                }
                if (!subLocality.isNullOrBlank() && !parts.contains(subLocality)) {
                    parts.add(subLocality)
                }
                if (!locality.isNullOrBlank() && !parts.contains(locality)) {
                    parts.add(locality)
                }
                if (parts.isEmpty() && !adminArea.isNullOrBlank()) {
                    parts.add(adminArea)
                }

                if (parts.isNotEmpty()) {
                    return@withContext parts.joinToString(", ")
                }
            }
        } catch (e: Exception) {
            Log.w(tag, "Geocoder failed: ${e.message}")
        }
        return@withContext ""
    }

    private fun processNavStepProgress(location: Location) {
        val state = _navState.value
        val steps = state.steps
        val index = state.currentStepIndex

        if (index >= steps.size) return
        val currentStep = steps[index]

        val distToStep = if (currentStep.latitude != 0.0 && currentStep.longitude != 0.0) {
            calculateDistanceMeters(
                location.latitude, location.longitude,
                currentStep.latitude, currentStep.longitude
            )
        } else {
            (currentStep.distanceMeters - 10).coerceAtLeast(0)
        }

        if (distToStep < 12) {
            val nextIndex = index + 1
            if (nextIndex < steps.size) {
                val nextStep = steps[nextIndex]
                _navState.value = state.copy(
                    currentStepIndex = nextIndex,
                    remainingDistanceMeters = (state.remainingDistanceMeters - currentStep.distanceMeters).coerceAtLeast(15)
                )
                onInstructionAnnounce?.invoke("${nextStep.instruction}. Distance ${nextStep.distanceMeters} meters.")
            } else {
                _navState.value = state.copy(
                    isNavigating = false,
                    isArrived = true,
                    remainingDistanceMeters = 0
                )
                onArrivalAnnounce?.invoke()
            }
        } else {
            _navState.value = state.copy(
                remainingDistanceMeters = distToStep
            )
        }
    }

    /**
     * Start walking route to a user-specified destination query using pedestrian routing.
     */
    fun startWalkingRoute(destinationQuery: String) {
        val s = _navState.value
        val lat = s.userLatitude
        val lng = s.userLongitude
        val cleanDest = destinationQuery.trim().replaceFirstChar { it.uppercase() }

        val gMapsUrl = if (lat != 0.0 && lng != 0.0) {
            "https://www.google.com/maps/dir/?api=1&origin=$lat,$lng&destination=${Uri.encode(cleanDest)}&travelmode=walking"
        } else {
            "https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(cleanDest)}&travelmode=walking"
        }

        _navState.value = _navState.value.copy(
            destination = cleanDest,
            isNavigating = true,
            isArrived = false,
            isOffRoute = false,
            currentStepIndex = 0,
            steps = emptyList(), // No in-detail steps!
            totalDistanceMeters = 0,
            remainingDistanceMeters = 0,
            estimatedDurationMinutes = 0,
            googleMapsUrl = gMapsUrl
        )

        // Directly open Google Maps Walking Mode!
        openInGoogleMapsApp(cleanDest)
    }

    /**
     * Launch native Google Maps app walking turn-by-turn voice navigation.
     */
    fun openInGoogleMapsApp(destName: String = ""): Boolean {
        val s = _navState.value
        val destination = destName.ifBlank { s.destination }.ifBlank { "Destination" }
        val lat = s.userLatitude
        val lng = s.userLongitude

        // Native Google Maps Walking Navigation Intent
        val navUri = if (lat != 0.0 && lng != 0.0) {
            Uri.parse("google.navigation:q=${Uri.encode(destination)}&mode=w")
        } else {
            Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=walking")
        }

        val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(mapIntent)
            true
        } catch (_: Exception) {
            // Web browser fallback if Google Maps app is disabled
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=${Uri.encode(destination)}&travelmode=walking")
            val fallbackIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    fun stopNavigation() {
        _navState.value = _navState.value.copy(
            isNavigating = false,
            destination = "",
            steps = emptyList()
        )
    }

    /**
     * Launch native Google Maps app walking turn-by-turn navigation.
     */
    fun openInGoogleMapsApp(): Boolean {
        val s = _navState.value
        val dest = Uri.encode(s.destination.ifBlank { "Destination" })
        val lat = s.destLatitude
        val lng = s.destLongitude

        // Try direct turn-by-turn navigation intent
        val navUri = Uri.parse("google.navigation:q=$lat,$lng&mode=w")
        val mapIntent = Intent(Intent.ACTION_VIEW, navUri).apply {
            setPackage("com.google.android.apps.maps")
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }

        return try {
            context.startActivity(mapIntent)
            true
        } catch (_: Exception) {
            // Fallback to web/universal Google Maps URL
            val webUri = Uri.parse("https://www.google.com/maps/dir/?api=1&destination=$dest&travelmode=walking")
            val fallbackIntent = Intent(Intent.ACTION_VIEW, webUri).apply {
                addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            }
            try {
                context.startActivity(fallbackIntent)
                true
            } catch (_: Exception) {
                false
            }
        }
    }

    /**
     * Share current location via Google Maps link.
     */
    fun getShareableLocationText(): String {
        val s = _navState.value
        val lat = s.userLatitude
        val lng = s.userLongitude
        val street = if (s.currentStreet.isNotBlank()) s.currentStreet else "Pedestrian Walkway"
        val gmapsUrl = "https://www.google.com/maps/search/?api=1&query=$lat,$lng"
        return String.format(
            Locale.US,
            "📍 My Current Location (AuraSight Assistant):\n%s\nCoordinates: %.5f, %.5f\nOpen in Google Maps: %s",
            street,
            lat,
            lng,
            gmapsUrl
        )
    }

    fun shareCurrentLocation() {
        val text = getShareableLocationText()

        // Also copy to clipboard so user can paste immediately
        try {
            val clipboard = context.getSystemService(Context.CLIPBOARD_SERVICE) as? ClipboardManager
            val clip = ClipData.newPlainText("AuraSight Location", text)
            clipboard?.setPrimaryClip(clip)
        } catch (_: Exception) {
            // Ignore clipboard errors
        }

        val intent = Intent(Intent.ACTION_SEND).apply {
            type = "text/plain"
            putExtra(Intent.EXTRA_SUBJECT, "My Location via Google Maps (AuraSight)")
            putExtra(Intent.EXTRA_TEXT, text)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
        context.startActivity(
            Intent.createChooser(intent, "Send Current Location via Google Maps")
                .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        )
    }

    private fun calculateDistanceMeters(
        lat1: Double, lon1: Double,
        lat2: Double, lon2: Double
    ): Int {
        val r = 6371000.0 // Earth radius in meters
        val dLat = Math.toRadians(lat2 - lat1)
        val dLon = Math.toRadians(lon2 - lon1)
        val a = sin(dLat / 2) * sin(dLat / 2) +
                cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) *
                sin(dLon / 2) * sin(dLon / 2)
        val c = 2 * atan2(sqrt(a), sqrt(1 - a))
        return (r * c).toInt()
    }
}