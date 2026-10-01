package io.github.kaitosiba.fujiptp.connection

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.util.Log
import androidx.core.content.ContextCompat
import androidx.core.content.IntentCompat
import io.github.kaitosiba.fujiptp.camera.FujifilmProfile
import io.github.kaitosiba.fujiptp.camera.ProfileMatch
import io.github.kaitosiba.fujiptp.camera.ProfileRegistry
import io.github.kaitosiba.fujiptp.camera.UsbIdentity
import io.github.kaitosiba.fujiptp.ptp.PtpClient
import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo
import io.github.kaitosiba.fujiptp.ptp.android.UsbCameraConnector
import io.github.kaitosiba.fujiptp.ptp.android.UsbCameraConnector.Companion.toDump
import io.github.kaitosiba.fujiptp.ptp.dump.DumpJson
import io.github.kaitosiba.fujiptp.ptp.dump.UsbDump
import io.github.kaitosiba.fujiptp.ptp.fake.FakePtpClient
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock
import kotlinx.coroutines.withContext

enum class SessionSource { USB, DEMO }

/** 開いているカメラセッション。 */
data class CameraSession(
    val client: PtpClient,
    val source: SessionSource,
    val deviceInfo: PtpDeviceInfo,
    val usb: UsbDump?,
    val profile: ProfileMatch,
    /** 切断検知用（UsbDevice.deviceName） */
    val usbDeviceName: String?,
)

sealed interface ConnectionState {
    data object Disconnected : ConnectionState
    data class Connecting(val message: String) : ConnectionState
    data class Connected(val session: CameraSession) : ConnectionState
    data class Failed(val message: String) : ConnectionState
}

/** カメラとの接続状態を一元管理する。USB 実機とデモ（FakePtpClient）を同じ形で扱う。 */
class CameraConnectionManager(
    context: Context,
    private val registry: ProfileRegistry,
    private val scope: CoroutineScope,
) {
    private val context: Context = context.applicationContext
    private val connector = UsbCameraConnector(this.context)
    private val mutex = Mutex()

    private val _state = MutableStateFlow<ConnectionState>(ConnectionState.Disconnected)
    val state: StateFlow<ConnectionState> = _state.asStateFlow()

    init {
        val receiver = object : BroadcastReceiver() {
            override fun onReceive(ctx: Context, intent: Intent) {
                val device = IntentCompat.getParcelableExtra(intent, UsbManager.EXTRA_DEVICE, UsbDevice::class.java)
                val session = (state.value as? ConnectionState.Connected)?.session ?: return
                if (device != null && device.deviceName == session.usbDeviceName) {
                    Log.i(TAG, "camera detached: ${device.deviceName}")
                    scope.launch { closeSession(ConnectionState.Failed("カメラが切断された")) }
                }
            }
        }
        ContextCompat.registerReceiver(
            this.context, receiver, IntentFilter(UsbManager.ACTION_USB_DEVICE_DETACHED),
            ContextCompat.RECEIVER_NOT_EXPORTED,
        )
    }

    /** USB 接続のカメラに接続する。[device] 省略時は接続中の PTP デバイスから探す（Fujifilm 優先）。 */
    fun connectUsb(device: UsbDevice? = null) {
        scope.launch {
            mutex.withLock {
                closeCurrent()
                _state.value = try {
                    openUsb(device)
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "connect failed", e)
                    ConnectionState.Failed("接続に失敗: ${e.message}")
                }
            }
        }
    }

    private suspend fun openUsb(device: UsbDevice?): ConnectionState {
        val target = device
            ?: connector.findCameras(setOf(FujifilmProfile.FUJIFILM_VENDOR_ID)).firstOrNull()
            ?: return ConnectionState.Failed("PTP カメラが見つからない。接続モードとケーブルを確認してくれ")

        _state.value = ConnectionState.Connecting("USB アクセス許可を確認中: ${target.productName ?: target.deviceName}")
        if (!connector.requestPermission(target)) {
            return ConnectionState.Failed("USB アクセスが許可されなかった")
        }

        _state.value = ConnectionState.Connecting("PTP セッションを開いている")
        val client = withContext(Dispatchers.IO) { connector.open(target) }
        val info = try {
            client.deviceInfo()
        } catch (e: Exception) {
            client.close()
            throw e
        }
        val usb = target.toDump()
        Log.i(TAG, "connected: ${info.manufacturer} ${info.model}")
        return ConnectionState.Connected(
            CameraSession(
                client = client,
                source = SessionSource.USB,
                deviceInfo = info,
                usb = usb,
                profile = registry.resolve(UsbIdentity(usb.vendorId, usb.productId), info),
                usbDeviceName = target.deviceName,
            ),
        )
    }

    /** アプリ同梱のダンプを使ったデモ接続。エミュレータでの開発用。 */
    fun connectDemo() {
        scope.launch {
            mutex.withLock {
                closeCurrent()
                _state.value = ConnectionState.Connecting("デモデータを読み込み中")
                _state.value = try {
                    openDemo()
                } catch (e: CancellationException) {
                    throw e
                } catch (e: Exception) {
                    Log.w(TAG, "demo connect failed", e)
                    ConnectionState.Failed("デモデータを読めない: ${e.message}")
                }
            }
        }
    }

    private suspend fun openDemo(): ConnectionState {
        val dump = withContext(Dispatchers.IO) {
            context.assets.open(DEMO_ASSET).bufferedReader().use { DumpJson.decode(it.readText()) }
        }
        val client = FakePtpClient(dump, latencyMillis = DEMO_LATENCY_MILLIS)
        val info = client.deviceInfo()
        return ConnectionState.Connected(
            CameraSession(
                client = client,
                source = SessionSource.DEMO,
                deviceInfo = info,
                usb = dump.usb,
                profile = registry.resolve(dump.usb?.let { UsbIdentity(it.vendorId, it.productId) }, info),
                usbDeviceName = null,
            ),
        )
    }

    fun disconnect() {
        scope.launch { closeSession(ConnectionState.Disconnected) }
    }

    private suspend fun closeSession(next: ConnectionState) {
        mutex.withLock {
            closeCurrent()
            _state.value = next
        }
    }

    private fun closeCurrent() {
        val session = (_state.value as? ConnectionState.Connected)?.session ?: return
        runCatching { session.client.close() }.onFailure { Log.w(TAG, "close failed", it) }
        _state.value = ConnectionState.Disconnected
    }

    companion object {
        private const val TAG = "FujiPtp"
        private const val DEMO_ASSET = "demo/x100vi-demo.json"
        private const val DEMO_LATENCY_MILLIS = 15L
    }
}
