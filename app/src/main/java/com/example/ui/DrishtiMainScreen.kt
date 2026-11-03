package com.example.ui

import androidx.compose.animation.animateColorAsState
import androidx.compose.animation.core.FastOutSlowInEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.AutoAwesome
import androidx.compose.material.icons.filled.Close
import androidx.compose.material.icons.filled.DirectionsWalk
import androidx.compose.material.icons.filled.FlashOff
import androidx.compose.material.icons.filled.FlashOn
import androidx.compose.material.icons.filled.Hearing
import androidx.compose.material.icons.filled.Home
import androidx.compose.material.icons.filled.Map
import androidx.compose.material.icons.filled.Mic
import androidx.compose.material.icons.filled.MicNone
import androidx.compose.material.icons.filled.Navigation
import androidx.compose.material.icons.filled.QrCodeScanner
import androidx.compose.material.icons.filled.RecordVoiceOver
import androidx.compose.material.icons.filled.Settings
import androidx.compose.material.icons.filled.Share
import androidx.compose.material.icons.filled.SmartToy
import androidx.compose.material.icons.filled.Sos
import androidx.compose.material.icons.filled.VolumeUp
import androidx.compose.material.icons.filled.Warning
import androidx.compose.material.icons.filled.Work
import androidx.compose.material3.Button
import androidx.compose.material3.ButtonDefaults
import androidx.compose.material3.Card
import androidx.compose.material3.CardDefaults
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FilterChip
import androidx.compose.material3.FilterChipDefaults
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.OutlinedTextFieldDefaults
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.material3.TopAppBarDefaults
import androidx.compose.runtime.Composable
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.scale
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.res.stringResource
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.example.R
import com.example.model.AppLanguage
import com.example.model.AppMode
import com.example.model.AppStrings
import com.example.model.Direction
import com.example.model.NavigationData
import com.example.model.Obstacle
import com.example.model.ObstacleSeverity
import com.example.model.ScanType
import com.example.model.UserSettings
import com.example.reader.ScanResult
import com.example.ui.theme.BrightCyan
import com.example.ui.theme.DangerCrimson
import com.example.ui.theme.DarkCyan
import com.example.ui.theme.DeepSurface
import com.example.ui.theme.GoldenAmber
import com.example.ui.theme.ObsidianBackground
import com.example.ui.theme.SurfaceElevated
import com.example.ui.theme.TextMuted
import com.example.ui.theme.TextPrimary
import com.example.ui.theme.TextSecondary
import com.example.ui.theme.WarningOrange

@OptIn(ExperimentalMaterial3Api::class, ExperimentalLayoutApi::class)
@Composable
fun DrishtiMainScreen(
    viewModel: DrishtiViewModel,
    modifier: Modifier = Modifier
) {
    val uiState by viewModel.uiState.collectAsState()
    val navState by viewModel.navState.collectAsState()

    var destinationInput by remember { mutableStateOf("") }
    val lang = uiState.userSettings.language

    Scaffold(
        modifier = modifier.fillMaxSize(),
        containerColor = ObsidianBackground,
        topBar = {
            TopAppBar(
                title = {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Box(
                            modifier = Modifier
                                .size(36.dp)
                                .clip(CircleShape)
                                .background(BrightCyan),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Hearing,
                                contentDescription = null,
                                tint = ObsidianBackground,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                        Spacer(modifier = Modifier.width(10.dp))
                        Column {
                            Text(
                                text = stringResource(R.string.app_name),
                                style = MaterialTheme.typography.titleLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                            Text(
                                text = AppStrings.appSubtitle(lang),
                                style = MaterialTheme.typography.labelSmall.copy(color = BrightCyan)
                            )
                        }
                    }
                },
                actions = {
                    IconButton(
                        onClick = { viewModel.toggleTorch() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("torch_toggle_btn")
                    ) {
                        Icon(
                            imageVector = if (uiState.isTorchOn) Icons.Default.FlashOn else Icons.Default.FlashOff,
                            contentDescription = if (uiState.isTorchOn) "Flashlight on. Tap to turn off." else "Flashlight off. Tap to turn on.",
                            tint = if (uiState.isTorchOn) GoldenAmber else TextMuted
                        )
                    }

                    IconButton(
                        onClick = { viewModel.triggerEmergencySOS() },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("emergency_sos_btn")
                    ) {
                        Box(
                            modifier = Modifier
                                .size(34.dp)
                                .clip(CircleShape)
                                .background(DangerCrimson),
                            contentAlignment = Alignment.Center
                        ) {
                            Icon(
                                imageVector = Icons.Default.Sos,
                                contentDescription = "Emergency SOS button",
                                tint = Color.White,
                                modifier = Modifier.size(22.dp)
                            )
                        }
                    }

                    IconButton(
                        onClick = {
                            if (uiState.currentMode == AppMode.SETTINGS) {
                                viewModel.switchMode(AppMode.WALKING)
                            } else {
                                viewModel.switchMode(AppMode.SETTINGS)
                            }
                        },
                        modifier = Modifier
                            .size(48.dp)
                            .testTag("settings_btn")
                    ) {
                        Icon(
                            imageVector = Icons.Default.Settings,
                            contentDescription = "Settings and Emergency Contacts",
                            tint = if (uiState.currentMode == AppMode.SETTINGS) GoldenAmber else BrightCyan
                        )
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = ObsidianBackground
                )
            )
        }
    ) { innerPadding ->
        Column(
            modifier = Modifier
                .fillMaxSize()
                .padding(innerPadding)
        ) {
            // 1. Spoken Audio Announcement Banner
            SpokenAnnouncementBanner(
                message = uiState.lastSpokenMessage,
                isSpeaking = uiState.isSpeaking,
                lang = lang
            )

            // 2. Press-To-Speak Banner (ONLY VISIBLE IN ASSISTANT MODE!)
            if (uiState.currentMode == AppMode.ASSISTANT) {
                TapToSpeakAccessibilityCard(
                    isListening = uiState.isListening,
                    recognizedText = uiState.recognizedText,
                    lang = lang,
                    onTap = { viewModel.toggleVoiceListening() }
                )
            }

            // 3. Mode Selector Tabs
            ModeSelectorTabs(
                currentMode = uiState.currentMode,
                lang = lang,
                onModeSelected = { viewModel.switchMode(it) }
            )

            // 4. Camera & Foreground Screen Content
            Box(
                modifier = Modifier
                    .weight(1f)
                    .fillMaxWidth()
            ) {
                if (uiState.isCameraPermissionGranted && uiState.currentMode != AppMode.SETTINGS) {
                    CameraPreviewWithOverlay(
                        modifier = Modifier.fillMaxSize(),
                        isTorchOn = uiState.isTorchOn,
                        obstacles = uiState.obstacles,
                        onFrameCaptured = { imageProxy ->
                            viewModel.onCameraFrame(imageProxy)
                        }
                    )
                } else if (uiState.currentMode != AppMode.SETTINGS) {
                    Box(
                        modifier = Modifier
                            .fillMaxSize()
                            .background(DeepSurface),
                        contentAlignment = Alignment.Center
                    ) {
                        Text(
                            text = AppStrings.pathMonitoringDesc(lang),
                            color = TextMuted,
                            textAlign = TextAlign.Center
                        )
                    }
                }

                Column(
                    modifier = Modifier
                        .fillMaxSize()
                        .padding(horizontal = 14.dp, vertical = 6.dp)
                        .verticalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(10.dp)
                ) {
                    when (uiState.currentMode) {
                        AppMode.WALKING -> {
                            WalkingModeContent(
                                obstacles = uiState.obstacles,
                                navData = navState,
                                userSettings = uiState.userSettings,
                                destinationInput = destinationInput,
                                isAnalyzingAiVision = uiState.isAnalyzingAiVision,
                                aiVisionDescription = uiState.aiVisionDescription,
                                lang = lang,
                                onLookAround = { viewModel.triggerLookAround() },
                                onDestinationChange = { destinationInput = it },
                                onStartNav = {
                                    if (destinationInput.isNotBlank()) {
                                        viewModel.startNavigation(destinationInput)
                                        destinationInput = ""
                                    }
                                },
                                onStopNav = { viewModel.stopNavigation() },
                                onOpenGoogleMaps = { viewModel.openInGoogleMaps() },
                                onShareLocation = { viewModel.shareCurrentLocation() }
                            )
                        }
                        AppMode.SCAN -> {
                            ScanModeContent(
                                selectedScanType = uiState.scanType,
                                onScanTypeSelected = { viewModel.setScanType(it) },
                                latestScan = uiState.latestScanResult,
                                isAnalyzingAiVision = uiState.isAnalyzingAiVision,
                                aiVisionDescription = uiState.aiVisionDescription,
                                lang = lang,
                                onTriggerQuickScan = { viewModel.scanDocumentHybrid() },
                                onTriggerGeminiVision = { viewModel.analyzeWithAiVision() },
                                onReadMore = { viewModel.readMoreScannedText() }
                            )
                        }
                        AppMode.ASSISTANT -> {
                            AssistantModeContent(
                                recognizedText = uiState.recognizedText,
                                isListening = uiState.isListening,
                                lang = lang,
                                onQuickQuery = { viewModel.handleSpokenCommand(it) }
                            )
                        }
                        AppMode.SETTINGS -> {
                            SettingsModeContent(
                                currentSettings = uiState.userSettings,
                                onSave = { updated -> viewModel.saveUserSettings(updated) },
                                onSendSOS = { viewModel.triggerEmergencySOS() },
                                onLanguageSelected = { viewModel.selectAppLanguage(it) }
                            )
                        }
                    }

                    SafetyDisclaimerCard(lang = lang)
                }
            }

            // 5. Bottom Voice Action Bar (ONLY VISIBLE IN ASSISTANT MODE!)
            if (uiState.currentMode == AppMode.ASSISTANT) {
                VoiceAssistantBottomBar(
                    isListening = uiState.isListening,
                    recognizedText = uiState.recognizedText,
                    soundRms = uiState.soundRms,
                    onToggleListening = { viewModel.toggleVoiceListening() }
                )
            }
        }
    }
}

@Composable
private fun TapToSpeakAccessibilityCard(
    isListening: Boolean,
    recognizedText: String,
    lang: AppLanguage = AppLanguage.ENGLISH,
    onTap: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_tap")
    val pulseAlpha by infiniteTransition.animateFloat(
        initialValue = 0.85f,
        targetValue = 1.0f,
        animationSpec = infiniteRepeatable(
            animation = tween(600, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "alpha"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onTap
            )
            .testTag("press_to_speak_card"),
        shape = RoundedCornerShape(14.dp),
        colors = CardDefaults.cardColors(
            containerColor = if (isListening) BrightCyan.copy(alpha = pulseAlpha) else DeepSurface
        ),
        border = androidx.compose.foundation.BorderStroke(
            2.dp,
            if (isListening) ObsidianBackground else GoldenAmber
        )
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 14.dp, vertical = 10.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Box(
                modifier = Modifier
                    .size(46.dp)
                    .clip(CircleShape)
                    .background(if (isListening) ObsidianBackground else GoldenAmber),
                contentAlignment = Alignment.Center
            ) {
                Icon(
                    imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                    contentDescription = null,
                    tint = if (isListening) BrightCyan else ObsidianBackground,
                    modifier = Modifier.size(28.dp)
                )
            }
            Spacer(modifier = Modifier.width(12.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AppStrings.tapToSpeakTitle(lang, isListening),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = if (isListening) ObsidianBackground else GoldenAmber,
                        letterSpacing = 0.5.sp
                    )
                )
                Text(
                    text = if (isListening) recognizedText.ifEmpty { AppStrings.listeningPrompt(lang) }
                    else AppStrings.tapToSpeakSubtitle(lang),
                    style = MaterialTheme.typography.bodySmall.copy(
                        color = if (isListening) ObsidianBackground else TextSecondary,
                        fontWeight = FontWeight.Medium
                    ),
                    maxLines = 1
                )
            }
        }
    }
}

@Composable
private fun SpokenAnnouncementBanner(
    message: String,
    isSpeaking: Boolean,
    lang: AppLanguage = AppLanguage.ENGLISH
) {
    val bgColor by animateColorAsState(
        targetValue = if (isSpeaking) DeepSurface else ObsidianBackground,
        label = "banner_bg"
    )

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 2.dp)
            .testTag("spoken_banner"),
        colors = CardDefaults.cardColors(containerColor = bgColor),
        shape = RoundedCornerShape(10.dp),
        border = CardDefaults.outlinedCardBorder().copy(width = 1.dp)
    ) {
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 12.dp, vertical = 6.dp),
            verticalAlignment = Alignment.CenterVertically
        ) {
            Icon(
                imageVector = Icons.Default.VolumeUp,
                contentDescription = null,
                tint = if (isSpeaking) BrightCyan else GoldenAmber,
                modifier = Modifier.size(22.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Column(modifier = Modifier.weight(1f)) {
                Text(
                    text = AppStrings.lastSpokenTitle(lang),
                    style = MaterialTheme.typography.labelSmall.copy(
                        color = if (isSpeaking) BrightCyan else TextMuted,
                        fontWeight = FontWeight.Bold,
                        letterSpacing = 1.sp
                    )
                )
                Text(
                    text = message.ifEmpty { AppStrings.readyMessage(lang) },
                    style = MaterialTheme.typography.bodyMedium.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    ),
                    maxLines = 2
                )
            }
        }
    }
}

@Composable
private fun ModeSelectorTabs(
    currentMode: AppMode,
    lang: AppLanguage = AppLanguage.ENGLISH,
    onModeSelected: (AppMode) -> Unit
) {
    Row(
        modifier = Modifier
            .fillMaxWidth()
            .padding(horizontal = 12.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp)
    ) {
        AppMode.entries.forEach { mode ->
            val isSelected = (mode == currentMode)
            val btnColor = if (isSelected) BrightCyan else SurfaceElevated
            val textColor = if (isSelected) ObsidianBackground else TextPrimary

            Button(
                onClick = { onModeSelected(mode) },
                modifier = Modifier
                    .weight(1f)
                    .height(48.dp)
                    .testTag("mode_${mode.name.lowercase()}_btn"),
                shape = RoundedCornerShape(10.dp),
                colors = ButtonDefaults.buttonColors(
                    containerColor = btnColor,
                    contentColor = textColor
                ),
                contentPadding = PaddingValues(2.dp)
            ) {
                val icon = when (mode) {
                    AppMode.WALKING -> Icons.Default.DirectionsWalk
                    AppMode.SCAN -> Icons.Default.QrCodeScanner
                    AppMode.ASSISTANT -> Icons.Default.RecordVoiceOver
                    AppMode.SETTINGS -> Icons.Default.Settings
                }
                Icon(
                    imageVector = icon,
                    contentDescription = null,
                    modifier = Modifier.size(16.dp)
                )
                Spacer(modifier = Modifier.width(4.dp))
                Text(
                    text = mode.displayName(lang),
                    style = MaterialTheme.typography.labelMedium.copy(fontWeight = FontWeight.Bold),
                    maxLines = 1
                )
            }
        }
    }
}
@Composable
private fun WalkingModeContent(
    obstacles: List<Obstacle>,
    navData: NavigationData,
    userSettings: UserSettings,
    destinationInput: String,
    isAnalyzingAiVision: Boolean,
    aiVisionDescription: String,
    lang: AppLanguage = userSettings.language,
    onLookAround: () -> Unit,
    onDestinationChange: (String) -> Unit,
    onStartNav: () -> Unit,
    onStopNav: () -> Unit,
    onOpenGoogleMaps: () -> Unit,
    onShareLocation: () -> Unit
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {

        Button(
            onClick = onLookAround,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("look_around_btn"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrightCyan)
        ) {
            if (isAnalyzingAiVision) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = ObsidianBackground,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = AppStrings.lookAroundBtn(lang, true),
                    color = ObsidianBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ObsidianBackground,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = AppStrings.lookAroundBtn(lang, false),
                    color = ObsidianBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        if (aiVisionDescription.isNotBlank()) {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(12.dp),
                colors = CardDefaults.cardColors(containerColor = DeepSurface),
                border = androidx.compose.foundation.BorderStroke(1.dp, BrightCyan)
            ) {
                Text(
                    text = "👁️ $aiVisionDescription",
                    modifier = Modifier.padding(14.dp),
                    style = MaterialTheme.typography.bodyLarge.copy(
                        color = TextPrimary,
                        fontWeight = FontWeight.SemiBold
                    )
                )
            }
        }

        FrontObstacleRadarCard(obstacles = obstacles, lang = lang)

        if (navData.isNavigating) {
            Card(
                modifier = Modifier
                    .fillMaxWidth()
                    .testTag("active_nav_card"),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f)),
                border = androidx.compose.foundation.BorderStroke(2.dp, BrightCyan)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Icon(
                                imageVector = Icons.Default.Navigation,
                                contentDescription = null,
                                tint = BrightCyan,
                                modifier = Modifier.size(24.dp)
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "DESTINATION: ${navData.destination}",
                                style = MaterialTheme.typography.titleMedium.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = BrightCyan
                                )
                            )
                        }

                        IconButton(
                            onClick = onStopNav,
                            modifier = Modifier
                                .size(48.dp)
                                .testTag("stop_nav_btn")
                        ) {
                            Icon(
                                imageVector = Icons.Default.Close,
                                contentDescription = "Close navigation",
                                tint = DangerCrimson
                            )
                        }
                    }

                    Spacer(modifier = Modifier.height(12.dp))

                    // Single, direct Google Maps walking button
                    Button(
                        onClick = onOpenGoogleMaps,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(54.dp)
                            .testTag("open_gmaps_nav_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = BrightCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = ObsidianBackground,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (lang) {
                                AppLanguage.MARATHI -> "गुगल मॅप्स उघडा (Google Maps)"
                                AppLanguage.HINDI -> "गूगल मैप्स खोलें (Google Maps)"
                                AppLanguage.ENGLISH -> "Open in Google Maps"
                            },
                            color = ObsidianBackground,
                            fontWeight = FontWeight.Bold,
                            fontSize = 15.sp
                        )
                    }

                    Spacer(modifier = Modifier.height(8.dp))

                    OutlinedButton(
                        onClick = onShareLocation,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(48.dp)
                            .testTag("share_gmaps_link_btn"),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(
                            imageVector = Icons.Default.Share,
                            contentDescription = null,
                            tint = TextPrimary,
                            modifier = Modifier.size(18.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppStrings.sendLocation(lang),
                            color = TextPrimary,
                            fontSize = 13.sp
                        )
                    }
                }
            }
        } else {
            Card(
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(16.dp),
                colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f)),
                border = androidx.compose.foundation.BorderStroke(1.dp, SurfaceElevated)
            ) {
                Column(modifier = Modifier.padding(16.dp)) {
                    Row(verticalAlignment = Alignment.CenterVertically) {
                        Icon(
                            imageVector = Icons.Default.Map,
                            contentDescription = null,
                            tint = BrightCyan,
                            modifier = Modifier.size(24.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = AppStrings.findWalkingRoute(lang),
                            style = MaterialTheme.typography.titleMedium.copy(
                                fontWeight = FontWeight.Bold,
                                color = TextPrimary
                            )
                        )
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        OutlinedTextField(
                            value = destinationInput,
                            onValueChange = onDestinationChange,
                            placeholder = { Text(AppStrings.destinationPlaceholder(lang), color = TextMuted, fontSize = 13.sp) },
                            modifier = Modifier
                                .weight(1f)
                                .testTag("destination_input"),
                            shape = RoundedCornerShape(12.dp),
                            singleLine = true,
                            colors = OutlinedTextFieldDefaults.colors(
                                focusedBorderColor = BrightCyan,
                                unfocusedBorderColor = SurfaceElevated,
                                focusedTextColor = TextPrimary,
                                unfocusedTextColor = TextPrimary
                            )
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Button(
                            onClick = onStartNav,
                            modifier = Modifier
                                .height(56.dp)
                                .testTag("start_route_btn"),
                            shape = RoundedCornerShape(12.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = BrightCyan)
                        ) {
                            Text(AppStrings.go(lang), color = ObsidianBackground, fontWeight = FontWeight.Bold)
                        }
                    }

                    Spacer(modifier = Modifier.height(10.dp))

                    Row(
                        modifier = Modifier.fillMaxWidth(),
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Button(
                            onClick = {
                                if (userSettings.homeAddress.isNotBlank()) {
                                    onDestinationChange(userSettings.homeAddress)
                                    onStartNav()
                                } else {
                                    onDestinationChange("Home")
                                    onStartNav()
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = DarkCyan)
                        ) {
                            Icon(Icons.Default.Home, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(AppStrings.goHome(lang), fontSize = 12.sp)
                        }

                        Button(
                            onClick = {
                                if (userSettings.workAddress.isNotBlank()) {
                                    onDestinationChange(userSettings.workAddress)
                                    onStartNav()
                                } else {
                                    onDestinationChange("Work")
                                    onStartNav()
                                }
                            },
                            modifier = Modifier.weight(1f).height(48.dp),
                            colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                        ) {
                            Icon(Icons.Default.Work, contentDescription = null, modifier = Modifier.size(18.dp))
                            Spacer(modifier = Modifier.width(6.dp))
                            Text(AppStrings.goWork(lang), fontSize = 12.sp)
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun FrontObstacleRadarCard(
    obstacles: List<Obstacle>,
    lang: AppLanguage = AppLanguage.ENGLISH
) {
    val frontObstacle = obstacles.firstOrNull { it.direction == Direction.CENTER }
    val highestThreat = frontObstacle ?: obstacles.firstOrNull()

    val cardBorderColor = when (highestThreat?.severity) {
        ObstacleSeverity.DANGER -> DangerCrimson
        ObstacleSeverity.WARNING -> WarningOrange
        else -> BrightCyan
    }

    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("obstacle_alert_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f)),
        border = androidx.compose.foundation.BorderStroke(2.dp, cardBorderColor)
    ) {
        Column(modifier = Modifier.padding(14.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically
            ) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = cardBorderColor,
                    modifier = Modifier.size(26.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = if (highestThreat != null) highestThreat.toSpokenAlert(lang) else AppStrings.pathScanning(lang),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = cardBorderColor
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (obstacles.isEmpty()) {
                Text(
                    text = AppStrings.pathMonitoringDesc(lang),
                    style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                )
            } else {
                obstacles.take(3).forEach { obs ->
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 4.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                        verticalAlignment = Alignment.CenterVertically
                    ) {
                        Row(verticalAlignment = Alignment.CenterVertically) {
                            Box(
                                modifier = Modifier
                                    .size(12.dp)
                                    .clip(CircleShape)
                                    .background(
                                        when (obs.severity) {
                                            ObstacleSeverity.DANGER -> DangerCrimson
                                            ObstacleSeverity.WARNING -> WarningOrange
                                            ObstacleSeverity.INFO -> BrightCyan
                                        }
                                    )
                            )
                            Spacer(modifier = Modifier.width(8.dp))
                            Text(
                                text = "${obs.label} (${obs.direction.getSpoken(lang)})",
                                style = MaterialTheme.typography.bodyLarge.copy(
                                    fontWeight = FontWeight.Bold,
                                    color = TextPrimary
                                )
                            )
                        }

                        Text(
                            text = "%.1f m".format(obs.distanceMeters),
                            style = MaterialTheme.typography.titleLarge.copy(
                                fontWeight = FontWeight.ExtraBold,
                                color = GoldenAmber
                            )
                        )
                    }
                }
            }
        }
    }
}


@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ScanModeContent(
    selectedScanType: ScanType,
    onScanTypeSelected: (ScanType) -> Unit,
    latestScan: ScanResult?,
    isAnalyzingAiVision: Boolean,
    aiVisionDescription: String,
    lang: AppLanguage = AppLanguage.ENGLISH,
    onTriggerQuickScan: () -> Unit,
    onTriggerGeminiVision: () -> Unit = {},
    onReadMore: () -> Unit = {}
) {
    Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
        // 1. Scan Sub-Icons / Category Chips Card (Auto All, Documents, Rupees, Medicine)
        Card(
            modifier = Modifier.fillMaxWidth(),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f))
        ) {
            Column(modifier = Modifier.padding(14.dp)) {
                Text(
                    text = AppStrings.scanModeTitle(lang),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
                Text(
                    text = selectedScanType.shortSpokenPrompt(lang),
                    style = MaterialTheme.typography.bodySmall.copy(color = BrightCyan)
                )

                Spacer(modifier = Modifier.height(10.dp))

                FlowRow(
                    horizontalArrangement = Arrangement.spacedBy(6.dp),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    ScanType.entries.forEach { st ->
                        FilterChip(
                            selected = (st == selectedScanType),
                            onClick = { onScanTypeSelected(st) },
                            label = { Text(st.title(lang)) },
                            modifier = Modifier.height(44.dp),
                            colors = FilterChipDefaults.filterChipColors(
                                selectedContainerColor = BrightCyan,
                                selectedLabelColor = ObsidianBackground,
                                containerColor = SurfaceElevated,
                                labelColor = TextSecondary
                            )
                        )
                    }
                }
            }
        }

        // 2. Fast Offline Scanner Button (ML Kit + Safety Rules)
        Button(
            onClick = onTriggerQuickScan,
            modifier = Modifier
                .fillMaxWidth()
                .height(56.dp)
                .testTag("quick_scan_btn"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = GoldenAmber)
        ) {
            Icon(
                imageVector = Icons.Default.QrCodeScanner,
                contentDescription = null,
                tint = ObsidianBackground,
                modifier = Modifier.size(24.dp)
            )
            Spacer(modifier = Modifier.width(10.dp))
            Text(
                text = AppStrings.scanButton(lang, isAnalyzing = isAnalyzingAiVision),
                color = ObsidianBackground,
                fontWeight = FontWeight.Bold,
                fontSize = 15.sp
            )
        }

        // 3. Special Gemini AI Vision Button
        Button(
            onClick = onTriggerGeminiVision,
            modifier = Modifier
                .fillMaxWidth()
                .height(58.dp)
                .testTag("ai_vision_scan_btn"),
            shape = RoundedCornerShape(14.dp),
            colors = ButtonDefaults.buttonColors(containerColor = BrightCyan)
        ) {
            if (isAnalyzingAiVision) {
                CircularProgressIndicator(
                    modifier = Modifier.size(24.dp),
                    color = ObsidianBackground,
                    strokeWidth = 3.dp
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when (lang) {
                        AppLanguage.MARATHI -> "जेमिनी एआय वाचत आहे..."
                        AppLanguage.HINDI -> "जेमिनी एआई पढ़ रहा है..."
                        AppLanguage.ENGLISH -> "Gemini AI Reading..."
                    },
                    color = ObsidianBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            } else {
                Icon(
                    imageVector = Icons.Default.AutoAwesome,
                    contentDescription = null,
                    tint = ObsidianBackground,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(10.dp))
                Text(
                    text = when (lang) {
                        AppLanguage.MARATHI -> "✨ एआय व्हिजनने वाचा (Gemini AI)"
                        AppLanguage.HINDI -> "✨ एआई विज़न से पढ़ें (Gemini AI)"
                        AppLanguage.ENGLISH -> "✨ Read with Gemini AI Vision"
                    },
                    color = ObsidianBackground,
                    fontWeight = FontWeight.Bold,
                    fontSize = 15.sp
                )
            }
        }

        // 4. Scanned Result Card + Read Full Text (More) Button
        Card(
            modifier = Modifier
                .fillMaxWidth()
                .testTag("scan_result_card"),
            shape = RoundedCornerShape(16.dp),
            colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f)),
            border = androidx.compose.foundation.BorderStroke(2.dp, GoldenAmber)
        ) {
            Column(modifier = Modifier.padding(16.dp)) {
                Text(
                    text = AppStrings.scannedResultTitle(lang),
                    style = MaterialTheme.typography.labelMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = GoldenAmber,
                        letterSpacing = 1.sp
                    )
                )

                Spacer(modifier = Modifier.height(8.dp))

                if (aiVisionDescription.isNotBlank()) {
                    Text(
                        text = aiVisionDescription,
                        style = MaterialTheme.typography.headlineSmall.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                } else if (latestScan != null) {
                    Text(
                        text = latestScan.title,
                        style = MaterialTheme.typography.headlineMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = TextPrimary
                        )
                    )
                    Spacer(modifier = Modifier.height(4.dp))
                    Text(
                        text = latestScan.details,
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                } else {
                    Text(
                        text = AppStrings.scanPlaceholder(lang),
                        style = MaterialTheme.typography.bodyMedium.copy(color = TextSecondary)
                    )
                }

                if (latestScan != null || aiVisionDescription.isNotBlank()) {
                    Spacer(modifier = Modifier.height(12.dp))
                    Button(
                        onClick = onReadMore,
                        modifier = Modifier
                            .fillMaxWidth()
                            .height(50.dp)
                            .testTag("read_more_btn"),
                        shape = RoundedCornerShape(12.dp),
                        colors = ButtonDefaults.buttonColors(containerColor = DarkCyan)
                    ) {
                        Icon(
                            imageVector = Icons.Default.VolumeUp,
                            contentDescription = null,
                            tint = Color.White,
                            modifier = Modifier.size(22.dp)
                        )
                        Spacer(modifier = Modifier.width(8.dp))
                        Text(
                            text = when (lang) {
                                AppLanguage.MARATHI -> "अधिक वाचा (पूर्ण मजकूर ऐका)"
                                AppLanguage.HINDI -> "और पढ़ें (पूरा टेक्स्ट सुनें)"
                                AppLanguage.ENGLISH -> "Read Full Text (More)"
                            },
                            color = Color.White,
                            fontWeight = FontWeight.Bold,
                            fontSize = 14.sp
                        )
                    }
                }
            }
        }
    }
}
// ============================================================================
// 2. In ui/DrishtiMainScreen.kt: Update AssistantModeContent() suggestions list
// ============================================================================


@Composable
private fun AssistantModeContent(
    recognizedText: String,
    isListening: Boolean,
    lang: AppLanguage = AppLanguage.ENGLISH,
    onQuickQuery: (String) -> Unit
) {
    Card(
        modifier = Modifier
            .fillMaxWidth()
            .testTag("assistant_card"),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f))
    ) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.SmartToy,
                    contentDescription = null,
                    tint = BrightCyan,
                    modifier = Modifier.size(24.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = AppStrings.assistantTitle(lang),
                    style = MaterialTheme.typography.titleMedium.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }

            Spacer(modifier = Modifier.height(8.dp))

            if (isListening || recognizedText.isNotBlank()) {
                Surface(
                    color = ObsidianBackground,
                    shape = RoundedCornerShape(8.dp),
                    modifier = Modifier.fillMaxWidth().padding(vertical = 4.dp)
                ) {
                    Text(
                        text = if (isListening) "🎤 $recognizedText" else recognizedText,
                        color = BrightCyan,
                        fontSize = 13.sp,
                        fontWeight = FontWeight.Medium,
                        modifier = Modifier.padding(8.dp)
                    )
                }
            } else {
                Text(
                    text = AppStrings.assistantSubtitle(lang),
                    style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
                )
            }

            Spacer(modifier = Modifier.height(12.dp))

            // 4 Clean, Meaningful Questions (No clutter, directly accessible)
            val suggestions = listOf(
                AppStrings.queryWhereAmI(lang),
                AppStrings.queryTakeMeHome(lang),
                AppStrings.queryMyEmergencyDetails(lang)
            )

            suggestions.forEach { query ->
                Button(
                    onClick = { onQuickQuery(query) },
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(vertical = 4.dp)
                        .height(48.dp),
                    shape = RoundedCornerShape(10.dp),
                    colors = ButtonDefaults.buttonColors(containerColor = SurfaceElevated)
                ) {
                    Text(
                        text = "\"$query\"",
                        style = MaterialTheme.typography.bodyMedium.copy(
                            color = TextPrimary,
                            fontWeight = FontWeight.SemiBold
                        ),
                        modifier = Modifier.fillMaxWidth(),
                        textAlign = TextAlign.Start
                    )
                }
            }
        }
    }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun SettingsModeContent(
    currentSettings: UserSettings,
    onSave: (UserSettings) -> Unit,
    onSendSOS: () -> Unit,
    onLanguageSelected: (AppLanguage) -> Unit
) {
    var name by remember { mutableStateOf(currentSettings.userName) }
    var contactName by remember { mutableStateOf(currentSettings.emergencyContactName) }
    var contactPhone by remember { mutableStateOf(currentSettings.emergencyContactPhone) }
    var contactPhone2 by remember { mutableStateOf(currentSettings.emergencyContactPhone2) }
    var home by remember { mutableStateOf(currentSettings.homeAddress) }
    var work by remember { mutableStateOf(currentSettings.workAddress) }
    var selectedLanguage by remember { mutableStateOf(currentSettings.language) }

    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(16.dp),
        colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.98f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldenAmber)
    ) {
        Column(modifier = Modifier.padding(16.dp), verticalArrangement = Arrangement.spacedBy(10.dp)) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(Icons.Default.Settings, contentDescription = null, tint = GoldenAmber)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (selectedLanguage) {
                        AppLanguage.MARATHI -> "सेटिंग्ज आणि प्रोफाइल"
                        AppLanguage.HINDI -> "सेटिंग्स और प्रोफाइल"
                        AppLanguage.ENGLISH -> "Settings & Profile"
                    },
                    style = MaterialTheme.typography.titleLarge.copy(fontWeight = FontWeight.Bold, color = TextPrimary)
                )
            }

            Text(
                text = "Language / भाषा निवडा / भाषा चुनें",
                style = MaterialTheme.typography.titleSmall.copy(fontWeight = FontWeight.Bold, color = BrightCyan)
            )

            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(8.dp)
            ) {
                AppLanguage.entries.forEach { langItem ->
                    val isSelected = (langItem == selectedLanguage)
                    FilterChip(
                        selected = isSelected,
                        onClick = {
                            selectedLanguage = langItem
                            onLanguageSelected(langItem)
                        },
                        label = { Text(langItem.displayName, fontWeight = FontWeight.Bold) },
                        modifier = Modifier.weight(1f).height(46.dp),
                        colors = FilterChipDefaults.filterChipColors(
                            selectedContainerColor = BrightCyan,
                            selectedLabelColor = ObsidianBackground,
                            containerColor = SurfaceElevated,
                            labelColor = TextPrimary
                        )
                    )
                }
            }

            Spacer(modifier = Modifier.height(4.dp))

            Text(
                text = when (selectedLanguage) {
                    AppLanguage.MARATHI -> "तातडीच्या मदतीसाठी संपर्क माहिती जतन करा."
                    AppLanguage.HINDI -> "आपातकालीन सहायता के लिए संपर्क जानकारी सहेजें।"
                    AppLanguage.ENGLISH -> "Save details for emergency SMS alerts."
                },
                style = MaterialTheme.typography.bodySmall.copy(color = TextMuted)
            )

            OutlinedTextField(
                value = name,
                onValueChange = { name = it },
                label = { Text(AppStrings.fullNameLabel(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = contactPhone,
                onValueChange = { contactPhone = it },
                label = { Text(AppStrings.contactPhone1Label(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = contactName,
                onValueChange = { contactName = it },
                label = { Text(AppStrings.contactNameLabel(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = contactPhone2,
                onValueChange = { contactPhone2 = it },
                label = { Text(AppStrings.contactPhone2Label(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = home,
                onValueChange = { home = it },
                label = { Text(AppStrings.homeAddressLabel(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            OutlinedTextField(
                value = work,
                onValueChange = { work = it },
                label = { Text(AppStrings.workAddressLabel(selectedLanguage)) },
                modifier = Modifier.fillMaxWidth(),
                shape = RoundedCornerShape(10.dp),
                singleLine = true
            )

            Spacer(modifier = Modifier.height(4.dp))

            Button(
                onClick = {
                    onSave(
                        UserSettings(
                            userName = name,
                            emergencyContactName = contactName,
                            emergencyContactPhone = contactPhone,
                            emergencyContactPhone2 = contactPhone2,
                            caregiverEmail = "",
                            homeAddress = home,
                            workAddress = work,
                            language = selectedLanguage
                        )
                    )
                },
                modifier = Modifier.fillMaxWidth().height(50.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = BrightCyan)
            ) {
                Text(
                    text = when (selectedLanguage) {
                        AppLanguage.MARATHI -> "माहिती जतन करा"
                        AppLanguage.HINDI -> "जानकारी सहेजें"
                        AppLanguage.ENGLISH -> "Save Personal Details"
                    },
                    fontWeight = FontWeight.Bold,
                    color = ObsidianBackground
                )
            }

            Button(
                onClick = onSendSOS,
                modifier = Modifier.fillMaxWidth().height(48.dp),
                shape = RoundedCornerShape(12.dp),
                colors = ButtonDefaults.buttonColors(containerColor = DangerCrimson)
            ) {
                Icon(Icons.Default.Sos, contentDescription = null, tint = Color.White)
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = when (selectedLanguage) {
                        AppLanguage.MARATHI -> "एसओएस चाचणी (SMS पाठवा)"
                        AppLanguage.HINDI -> "एसओएस टेस्ट (SMS भेजें)"
                        AppLanguage.ENGLISH -> "Test Emergency SOS (SMS)"
                    },
                    fontWeight = FontWeight.Bold,
                    color = Color.White
                )
            }
        }
    }
}

@Composable
private fun SafetyDisclaimerCard(lang: AppLanguage = AppLanguage.ENGLISH) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape = RoundedCornerShape(12.dp),
        colors = CardDefaults.cardColors(containerColor = DeepSurface.copy(alpha = 0.95f)),
        border = androidx.compose.foundation.BorderStroke(1.dp, GoldenAmber)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(12.dp)
        ) {
            Row(verticalAlignment = Alignment.CenterVertically) {
                Icon(
                    imageVector = Icons.Default.Warning,
                    contentDescription = null,
                    tint = GoldenAmber,
                    modifier = Modifier.size(20.dp)
                )
                Spacer(modifier = Modifier.width(8.dp))
                Text(
                    text = AppStrings.safetyTitle(lang),
                    style = MaterialTheme.typography.titleSmall.copy(
                        fontWeight = FontWeight.Bold,
                        color = TextPrimary
                    )
                )
            }
            Spacer(modifier = Modifier.height(6.dp))
            Text(
                text = AppStrings.safetyGuidelines(lang),
                style = MaterialTheme.typography.bodySmall.copy(
                    color = TextMuted,
                    fontSize = 11.sp,
                    lineHeight = 15.sp
                )
            )
        }
    }
}

@Composable
private fun VoiceAssistantBottomBar(
    isListening: Boolean,
    recognizedText: String,
    soundRms: Float,
    onToggleListening: () -> Unit
) {
    val infiniteTransition = rememberInfiniteTransition(label = "pulse_bottom")
    val pulseScale by infiniteTransition.animateFloat(
        initialValue = 1f,
        targetValue = if (isListening) (1.10f + (soundRms / 60f).coerceIn(0f, 0.20f)) else 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(400, easing = FastOutSlowInEasing),
            repeatMode = RepeatMode.Reverse
        ),
        label = "pulse_scale"
    )

    Surface(
        modifier = Modifier
            .fillMaxWidth()
            .clickable(
                interactionSource = remember { MutableInteractionSource() },
                indication = null,
                onClick = onToggleListening
            ),
        color = DeepSurface,
        tonalElevation = 8.dp,
        border = androidx.compose.foundation.BorderStroke(2.dp, if (isListening) BrightCyan else GoldenAmber)
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 16.dp, vertical = 12.dp),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                verticalAlignment = Alignment.CenterVertically,
                horizontalArrangement = Arrangement.Center
            ) {
                Box(
                    modifier = Modifier
                        .size(64.dp)
                        .scale(pulseScale)
                        .clip(CircleShape)
                        .background(if (isListening) BrightCyan else GoldenAmber)
                        .testTag("voice_speak_btn"),
                    contentAlignment = Alignment.Center
                ) {
                    Icon(
                        imageVector = if (isListening) Icons.Default.Mic else Icons.Default.MicNone,
                        contentDescription = if (isListening) "Stop listening" else "Tap anywhere to speak",
                        tint = ObsidianBackground,
                        modifier = Modifier.size(36.dp)
                    )
                }

                Spacer(modifier = Modifier.width(16.dp))

                Column(modifier = Modifier.weight(1f)) {
                    Text(
                        text = if (isListening) "LISTENING... SPEAK NOW" else "TAP ANYWHERE TO SPEAK",
                        style = MaterialTheme.typography.titleMedium.copy(
                            fontWeight = FontWeight.Bold,
                            color = if (isListening) BrightCyan else GoldenAmber,
                            letterSpacing = 0.5.sp
                        )
                    )
                    Text(
                        text = if (isListening) {
                            if (recognizedText.isNotBlank()) "\"$recognizedText\"" else "Speak now..."
                        } else {
                            "Ask 'Where am I?', 'Take me home', or 'Read PAN card'"
                        },
                        style = MaterialTheme.typography.bodySmall.copy(
                            color = TextSecondary
                        ),
                        maxLines = 1
                    )
                }
            }
        }
    }
}