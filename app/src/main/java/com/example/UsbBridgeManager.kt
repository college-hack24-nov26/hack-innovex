// ============================================================================
// 1. Replace UsbBridgeManager.kt completely with this fixed version:
//
// WHY IT WAS NOT GIVING ALERTS:
// 1. Android 14/15 blocks USB permission popup unless .setPackage(context.packageName) is set!
// 2. Arduino Uno requires port.dtr = true and port.rts = true to transmit Serial data!
// 3. Added auto-scan every 3 seconds so you can plug in the OTG cable anytime!
// ============================================================================

package com.example

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Build
import android.util.Log
import androidx.core.content.ContextCompat
import com.hoho.android.usbserial.driver.UsbSerialPort
import com.hoho.android.usbserial.driver.UsbSerialProber
import com.hoho.android.usbserial.util.SerialInputOutputManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.delay
import kotlinx.coroutines.isActive
import kotlinx.coroutines.launch

class UsbBridgeManager(
    private val context: Context,
    private val onConnected: () -> Unit = {},
    private val onDistanceAlert: (distanceCm: Int) -> Unit
) : SerialInputOutputManager.Listener {

    private val tag = "UsbBridgeManager"

    companion object {
        private const val ACTION_USB_PERMISSION = "com.example.USB_PERMISSION"
    }

    private val usbManager = context.getSystemService(Context.USB_SERVICE) as UsbManager
    private var usbSerialPort: UsbSerialPort? = null
    private var ioManager: SerialInputOutputManager? = null
    private val scope = CoroutineScope(Dispatchers.Main + SupervisorJob())
    private var autoConnectJob: Job? = null
    private var isPermissionPending = false

    private val stringBuffer = StringBuilder()

    private val usbReceiver = object : BroadcastReceiver() {
        override fun onReceive(ctx: Context?, intent: Intent?) {
            when (intent?.action) {
                ACTION_USB_PERMISSION -> {
                    isPermissionPending = false
                    val granted = intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false)
                    val device = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                    } else {
                        @Suppress("DEPRECATION")
                        intent.getParcelableExtra(UsbManager.EXTRA_DEVICE)
                    }
                    if (granted && device != null) {
                        connectToDevice(device)
                    } else {
                        // Even if EXTRA_PERMISSION_GRANTED extra was stripped, check usbManager.hasPermission directly
                        scanAndConnect()
                    }
                }
                UsbManager.ACTION_USB_DEVICE_ATTACHED -> {
                    scanAndConnect()
                }
                UsbManager.ACTION_USB_DEVICE_DETACHED -> {
                    disconnectPort()
                }
            }
        }
    }

    fun start() {
        try {
            val filter = IntentFilter().apply {
                addAction(ACTION_USB_PERMISSION)
                addAction(UsbManager.ACTION_USB_DEVICE_ATTACHED)
                addAction(UsbManager.ACTION_USB_DEVICE_DETACHED)
            }
            ContextCompat.registerReceiver(
                context,
                usbReceiver,
                filter,
                ContextCompat.RECEIVER_NOT_EXPORTED
            )

            // Auto-check every 3 seconds in case OTG cable is plugged in after app starts
            autoConnectJob?.cancel()
            autoConnectJob = scope.launch(Dispatchers.IO) {
                while (isActive) {
                    if (usbSerialPort == null && !isPermissionPending) {
                        scanAndConnect()
                    }
                    delay(3000L)
                }
            }
        } catch (e: Exception) {
            Log.e(tag, "Start error: ${e.message}")
        }
    }

    fun scanAndConnect() {
        try {
            if (usbSerialPort != null) return

            val availableDrivers = UsbSerialProber.getDefaultProber().findAllDrivers(usbManager)
            if (availableDrivers.isEmpty()) return

            val driver = availableDrivers[0]
            val device = driver.device

            if (!usbManager.hasPermission(device)) {
                if (isPermissionPending) return
                isPermissionPending = true

                // CRITICAL FOR ANDROID 14/15: Intent MUST have .setPackage(context.packageName)
                val explicitIntent = Intent(ACTION_USB_PERMISSION).apply {
                    setPackage(context.packageName)
                }
                val flags = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                    PendingIntent.FLAG_MUTABLE or PendingIntent.FLAG_UPDATE_CURRENT
                } else {
                    PendingIntent.FLAG_UPDATE_CURRENT
                }
                val permissionIntent = PendingIntent.getBroadcast(context, 0, explicitIntent, flags)
                usbManager.requestPermission(device, permissionIntent)
                return
            }

            connectToDevice(device)
        } catch (e: Exception) {
            isPermissionPending = false
            Log.e(tag, "scanAndConnect error: ${e.message}")
        }
    }

    private fun connectToDevice(device: UsbDevice) {
        try {
            val driver = UsbSerialProber.getDefaultProber().probeDevice(device) ?: return
            val connection = usbManager.openDevice(device) ?: return

            val port = driver.ports[0]
            port.open(connection)
            port.setParameters(9600, 8, UsbSerialPort.STOPBITS_1, UsbSerialPort.PARITY_NONE)

            // REQUIRED FOR ARDUINO UNO: Enable DTR & RTS so Arduino starts sending Serial lines!
            try {
                port.dtr = true
                port.rts = true
            } catch (_: Exception) {}

            usbSerialPort = port
            ioManager = SerialInputOutputManager(port, this).also {
                it.start()
            }

            scope.launch(Dispatchers.Main) {
                onConnected()
            }
            Log.i(tag, "Arduino USB OTG connected!")
        } catch (e: Exception) {
            Log.e(tag, "Connection failed: ${e.message}")
            disconnectPort()
        }
    }

    override fun onNewData(data: ByteArray?) {
        data ?: return
        val chunk = String(data, Charsets.UTF_8)
        synchronized(stringBuffer) {
            stringBuffer.append(chunk)

            var newlineIndex = stringBuffer.indexOf("\n")
            while (newlineIndex != -1) {
                val line = stringBuffer.substring(0, newlineIndex).trim()
                stringBuffer.delete(0, newlineIndex + 1)

                if (line.contains("DIST:")) {
                    val distStr = line.substringAfter("DIST:").filter { it.isDigit() }
                    val distanceCm = distStr.toIntOrNull()
                    // Trigger alert ONLY when obstacle is within 30 cm (2 cm to 30 cm)
                    if (distanceCm != null && distanceCm in 2..30) {
                        scope.launch(Dispatchers.Main) {
                            onDistanceAlert(distanceCm)
                        }
                    }
                }
                newlineIndex = stringBuffer.indexOf("\n")
            }

            if (stringBuffer.length > 500) {
                stringBuffer.clear()
            }
        }
    }

    override fun onRunError(e: Exception?) {
        Log.e(tag, "Serial run error: ${e?.message}")
        disconnectPort()
    }

    private fun disconnectPort() {
        try {
            ioManager?.stop()
        } catch (_: Exception) {}
        ioManager = null

        try {
            usbSerialPort?.close()
        } catch (_: Exception) {}
        usbSerialPort = null
        isPermissionPending = false
    }

    fun stop() {
        autoConnectJob?.cancel()
        disconnectPort()
        try {
            context.unregisterReceiver(usbReceiver)
        } catch (_: Exception) {}
    }
}