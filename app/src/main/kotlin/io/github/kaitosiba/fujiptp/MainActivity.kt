package io.github.kaitosiba.fujiptp

import android.content.Intent
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.runtime.Composable
import androidx.core.content.IntentCompat
import androidx.lifecycle.viewmodel.compose.viewModel
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import androidx.navigation.compose.rememberNavController
import io.github.kaitosiba.fujiptp.browser.BrowserScreen
import io.github.kaitosiba.fujiptp.browser.BrowserViewModel
import io.github.kaitosiba.fujiptp.diagnostics.DiagnosticsScreen
import io.github.kaitosiba.fujiptp.diagnostics.DiagnosticsViewModel
import io.github.kaitosiba.fujiptp.preview.PreviewScreen
import io.github.kaitosiba.fujiptp.ui.theme.FujiPtpTheme

class MainActivity : ComponentActivity() {

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            FujiPtpTheme {
                AppNavHost()
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
        (application as FujiPtpApp).container.connectionManager.connectUsb(device)
    }
}

private object Routes {
    const val BROWSER = "browser"
    const val PREVIEW = "preview"
    const val DIAGNOSTICS = "diagnostics"
}

@Composable
private fun AppNavHost() {
    val navController = rememberNavController()
    // 一覧とプレビューで選択状態や一覧を共有するため、Activity スコープで持つ
    val browserViewModel: BrowserViewModel = viewModel()
    NavHost(navController = navController, startDestination = Routes.BROWSER) {
        composable(Routes.BROWSER) {
            BrowserScreen(
                viewModel = browserViewModel,
                onOpenPreview = { navController.navigate(Routes.PREVIEW) },
                onOpenDiagnostics = { navController.navigate(Routes.DIAGNOSTICS) },
            )
        }
        composable(Routes.PREVIEW) {
            PreviewScreen(browserViewModel, onBack = { navController.popBackStack() })
        }
        composable(Routes.DIAGNOSTICS) {
            val viewModel: DiagnosticsViewModel = viewModel()
            DiagnosticsScreen(viewModel, onBack = { navController.popBackStack() })
        }
    }
}
