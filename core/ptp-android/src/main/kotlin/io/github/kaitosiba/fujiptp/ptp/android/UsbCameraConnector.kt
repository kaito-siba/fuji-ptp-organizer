package io.github.kaitosiba.fujiptp.ptp.android

import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.hardware.usb.UsbConstants
import android.hardware.usb.UsbDevice
import android.hardware.usb.UsbManager
import android.mtp.MtpDevice
import androidx.core.content.ContextCompat
import io.github.kaitosiba.fujiptp.ptp.PtpException
import io.github.kaitosiba.fujiptp.ptp.dump.UsbDump
import io.github.kaitosiba.fujiptp.ptp.dump.UsbEndpointDump
import io.github.kaitosiba.fujiptp.ptp.dump.UsbInterfaceDump
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume

/** USB に接続された PTP カメラを探し、権限を取得してセッションを開く。 */
class UsbCameraConnector(context: Context) {

    private val context: Context = context.applicationContext
    private val usbManager: UsbManager = context.getSystemService(UsbManager::class.java)

    /** Still Image クラス（PTP）のインターフェースを持つデバイス。[preferredVendorIds] のものを先頭に並べる。 */
    fun findCameras(preferredVendorIds: Set<Int> = emptySet()): List<UsbDevice> =
        usbManager.deviceList.values
            .filter { it.hasPtpInterface() }
            .sortedByDescending { it.vendorId in preferredVendorIds }

    fun hasPermission(device: UsbDevice): Boolean = usbManager.hasPermission(device)

    /** 権限ダイアログを出して結果を待つ。既に許可済みなら即 true。 */
    suspend fun requestPermission(device: UsbDevice): Boolean {
        if (usbManager.hasPermission(device)) return true
        return suspendCancellableCoroutine { cont ->
            val receiver = object : BroadcastReceiver() {
                override fun onReceive(ctx: Context, intent: Intent) {
                    if (intent.action != ACTION_USB_PERMISSION) return
                    context.unregisterReceiver(this)
                    if (cont.isActive) {
                        cont.resume(intent.getBooleanExtra(UsbManager.EXTRA_PERMISSION_GRANTED, false))
                    }
                }
            }
            ContextCompat.registerReceiver(
                context, receiver, IntentFilter(ACTION_USB_PERMISSION), ContextCompat.RECEIVER_NOT_EXPORTED,
            )
            cont.invokeOnCancellation { runCatching { context.unregisterReceiver(receiver) } }
            // Android 14 以降、システムが extras を書き込むため MUTABLE かつ明示的 Intent にする
            val intent = Intent(ACTION_USB_PERMISSION).setPackage(context.packageName)
            val pending = PendingIntent.getBroadcast(context, 0, intent, PendingIntent.FLAG_MUTABLE)
            usbManager.requestPermission(device, pending)
        }
    }

    /** セッションを開く。権限は取得済みであること。 */
    fun open(device: UsbDevice): FrameworkPtpClient {
        val connection = usbManager.openDevice(device)
            ?: throw PtpException("USB デバイスを開けない（権限がないか、切断された）")
        val mtp = MtpDevice(device)
        if (!mtp.open(connection)) {
            connection.close()
            throw PtpException("PTP セッションを開けない（他のアプリがカメラを使用中の可能性）")
        }
        return FrameworkPtpClient(mtp)
    }

    companion object {
        private const val ACTION_USB_PERMISSION = "io.github.kaitosiba.fujiptp.USB_PERMISSION"

        fun UsbDevice.hasPtpInterface(): Boolean =
            (0 until interfaceCount).any { getInterface(it).interfaceClass == UsbConstants.USB_CLASS_STILL_IMAGE }

        /** USB 記述子のダンプ。シリアル番号は権限がないと読めないため含めない。 */
        fun UsbDevice.toDump(): UsbDump = UsbDump(
            vendorId = vendorId,
            productId = productId,
            manufacturerName = manufacturerName,
            productName = productName,
            version = version,
            deviceClass = deviceClass,
            deviceSubclass = deviceSubclass,
            deviceProtocol = deviceProtocol,
            interfaces = (0 until interfaceCount).map { index ->
                val itf = getInterface(index)
                UsbInterfaceDump(
                    id = itf.id,
                    alternateSetting = itf.alternateSetting,
                    interfaceClass = itf.interfaceClass,
                    interfaceSubclass = itf.interfaceSubclass,
                    interfaceProtocol = itf.interfaceProtocol,
                    endpoints = (0 until itf.endpointCount).map { e ->
                        val ep = itf.getEndpoint(e)
                        UsbEndpointDump(
                            address = ep.address,
                            type = ep.type,
                            direction = ep.direction,
                            maxPacketSize = ep.maxPacketSize,
                            interval = ep.interval,
                        )
                    },
                )
            },
        )
    }
}
