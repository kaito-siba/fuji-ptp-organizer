package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpDeviceInfo

data class ProfileMatch(val profile: CameraProfile, val score: Int)

class ProfileRegistry(private val profiles: List<CameraProfile>) {

    init {
        require(profiles.isNotEmpty()) { "profiles must not be empty" }
    }

    /** 最もスコアの高いプロファイルを返す。どれも一致しなければ最後のもの（汎用）にフォールバックする。 */
    fun resolve(usb: UsbIdentity?, info: PtpDeviceInfo?): ProfileMatch =
        profiles
            .map { ProfileMatch(it, it.match(usb, info)) }
            .filter { it.score > 0 }
            .maxByOrNull { it.score }
            ?: ProfileMatch(profiles.last(), 0)

    companion object {
        fun default(): ProfileRegistry = ProfileRegistry(
            listOf(FujifilmX100VIProfile, FujifilmProfile(), GenericPtpProfile()),
        )
    }
}
