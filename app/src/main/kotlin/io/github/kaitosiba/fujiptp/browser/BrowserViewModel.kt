package io.github.kaitosiba.fujiptp.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaitosiba.fujiptp.FujiPtpApp
import io.github.kaitosiba.fujiptp.camera.CatalogState
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.camera.Shot
import io.github.kaitosiba.fujiptp.camera.StableObjectId
import io.github.kaitosiba.fujiptp.catalog.ActiveCatalog
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.thumbnail.PtpThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** グリッドの 1 セル。 */
data class ShotItem(
    val key: String,
    val shot: Shot,
    /** null ならサムネイルなし（動画など） */
    val thumbnail: PtpThumbnail?,
    /** 「RAW+JPG」などの形式表示 */
    val badge: String,
)

data class DateSection(val date: LocalDate?, val items: List<ShotItem>)

data class BrowserUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val catalog: CatalogState = CatalogState(),
    val sections: List<DateSection> = emptyList(),
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as FujiPtpApp).container
    private val connectionManager = container.connectionManager
    private val catalogManager = container.catalogManager

    @OptIn(ExperimentalCoroutinesApi::class)
    private val catalogContent = catalogManager.active.flatMapLatest { active ->
        if (active == null) {
            flowOf(CatalogState() to emptyList())
        } else {
            active.catalog.state.map { state -> state to toSections(active, state.shots) }
        }
    }.flowOn(Dispatchers.Default)

    val ui: StateFlow<BrowserUiState> =
        combine(connectionManager.state, catalogContent) { connection, (catalog, sections) ->
            BrowserUiState(connection, catalog, sections)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    fun connectUsb() = connectionManager.connectUsb()

    fun connectDemo() = connectionManager.connectDemo()

    fun disconnect() = connectionManager.disconnect()

    fun reload() = catalogManager.reload()

    private fun toSections(active: ActiveCatalog, shots: List<Shot>): List<DateSection> {
        val profile = active.session.profile.profile
        return shots
            .groupBy { it.capturedAt?.toLocalDate() }
            .map { (date, group) ->
                DateSection(
                    date = date,
                    items = group.map { shot ->
                        val primary = shot.primary
                        ShotItem(
                            key = shot.key,
                            shot = shot,
                            thumbnail = if (profile.hasPtpThumbnail(primary.kind)) {
                                PtpThumbnail(
                                    cacheKey = StableObjectId.of(active.cameraSerial, primary.info).key,
                                    info = primary.info,
                                    kind = primary.kind,
                                )
                            } else {
                                null
                            },
                            badge = badgeOf(shot.kinds),
                        )
                    },
                )
            }
    }

    private companion object {
        fun badgeOf(kinds: Set<MediaKind>): String {
            val labels = listOf(
                MediaKind.RAW to "RAW",
                MediaKind.JPEG to "JPG",
                MediaKind.HEIF to "HEIF",
                MediaKind.VIDEO to "MOV",
                MediaKind.OTHER to "?",
            )
            return labels.filter { it.first in kinds }.joinToString("+") { it.second }
        }
    }
}
