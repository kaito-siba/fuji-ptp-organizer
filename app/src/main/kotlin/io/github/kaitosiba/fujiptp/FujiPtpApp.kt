package io.github.kaitosiba.fujiptp

import android.app.Application
import io.github.kaitosiba.fujiptp.camera.ProfileRegistry
import io.github.kaitosiba.fujiptp.connection.CameraConnectionManager
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.Dispatchers

class FujiPtpApp : Application() {

    /** M0 では手動 DI。画面と依存が増える M1 で Hilt に置き換える。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
    }
}

class AppContainer(app: Application) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profileRegistry: ProfileRegistry = ProfileRegistry.default()
    val connectionManager: CameraConnectionManager =
        CameraConnectionManager(app, profileRegistry, applicationScope)
}
