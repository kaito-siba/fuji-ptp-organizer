package io.github.kaitosiba.fujiptp

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.activity.viewModels
import androidx.core.content.IntentCompat
import io.github.kaitosiba.fujiptp.diagnostics.DiagnosticsScreen
import io.github.kaitosiba.fujiptp.diagnostics.DiagnosticsViewModel
import io.github.kaitosiba.fujiptp.ui.theme.FujiPtpTheme

class MainActivity : ComponentActivity() {

    private val viewModel: DiagnosticsViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FujiPtpTheme {
                DiagnosticsScreen(viewModel)
            }
        }
        if (savedInstanceState == null) handleUsbIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        handleUsbIntent(intent)
    }

    /** カメラ接続でアプリが起動された場合は、そのデバイスに自動接続する。 */
    private fun handleUsbIntent(intent: Intent?) {
        if (intent?.action != UsbManager.ACTION_USB_DEVICE_ATTACHED) return
        val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
        viewModel.connectUsb(device)
    }
}
