package io.github.kaitosiba.fujiptp.browser

import android.app.Application
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaitosiba.fujiptp.FujiPtpApp
import io.github.kaitosiba.fujiptp.camera.CatalogState
import io.github.kaitosiba.fujiptp.camera.ImportFormat
import io.github.kaitosiba.fujiptp.camera.ImportPlan
import io.github.kaitosiba.fujiptp.camera.ImportStatus
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.camera.Shot
import io.github.kaitosiba.fujiptp.camera.StableObjectId
import io.github.kaitosiba.fujiptp.camera.importStatus
import io.github.kaitosiba.fujiptp.camera.planImport
import io.github.kaitosiba.fujiptp.catalog.ActiveCatalog
import io.github.kaitosiba.fujiptp.connection.ConnectionState
import io.github.kaitosiba.fujiptp.importer.ImportProgress
import io.github.kaitosiba.fujiptp.preview.PtpPreview
import io.github.kaitosiba.fujiptp.thumbnail.PtpThumbnail
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.flatMapLatest
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.flow.stateIn
import java.time.LocalDate

/** グリッドの 1 セル。 */
data class ShotItem(
    val key: String,
    val shot: Shot,
    /** null ならサムネイルなし（動画など） */
    val thumbnail: PtpThumbnail?,
    /** 主ファイルのプレビュー（非対応形式は null） */
    val preview: PtpPreview?,
    /** 「RAW+JPG」などの形式表示 */
    val badge: String,
    val importStatus: ImportStatus,
)

data class DateSection(val date: LocalDate?, val items: List<ShotItem>)

data class BrowserUiState(
    val connection: ConnectionState = ConnectionState.Disconnected,
    val catalog: CatalogState = CatalogState(),
    val sections: List<DateSection> = emptyList(),
    val importProgress: ImportProgress = ImportProgress(),
)

class BrowserViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as FujiPtpApp).container
    private val connectionManager = container.connectionManager
    private val catalogManager = container.catalogManager
    private val importManager = container.importManager

    private data class CatalogContent(
        val state: CatalogState = CatalogState(),
        val sections: List<DateSection> = emptyList(),
        val importedKeys: Set<String> = emptySet(),
    )

    @OptIn(ExperimentalCoroutinesApi::class)
    private val catalogContent = catalogManager.active.flatMapLatest { active ->
        if (active == null) {
            flowOf(CatalogContent())
        } else {
            combine(active.catalog.state, importManager.importedKeys(active.cameraSerial)) { state, imported ->
                CatalogContent(state, toSections(active, state.shots, imported), imported)
            }
        }
    }.flowOn(Dispatchers.Default)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), CatalogContent())

    val ui: StateFlow<BrowserUiState> =
        combine(connectionManager.state, catalogContent, importManager.progress) { connection, content, progress ->
            BrowserUiState(connection, content.state, content.sections, progress)
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), BrowserUiState())

    /** 選択中の Shot のキー */
    private val _selection = MutableStateFlow<Set<String>>(emptySet())
    val selection: StateFlow<Set<String>> = _selection.asStateFlow()

    /** プレビューで最初に開く Shot のキー */
    var previewStartKey: String? = null
        private set

    fun connectUsb() = connectionManager.connectUsb()

    fun connectDemo() = connectionManager.connectDemo()

    fun disconnect() {
        _selection.value = emptySet()
        connectionManager.disconnect()
    }

    fun reload() = catalogManager.reload()

    fun openPreview(key: String) {
        previewStartKey = key
    }

    fun toggleSelection(key: String) = _selection.update { if (key in it) it - key else it + key }

    /** その日の Shot を全部選択する。すでに全部選択済みなら解除する。 */
    fun toggleSection(section: DateSection) = _selection.update { current ->
        val keys = section.items.map { it.key }.toSet()
        if (current.containsAll(keys)) current - keys else current + keys
    }

    fun selectAllNotImported() {
        _selection.value = allItems().filter { it.importStatus != ImportStatus.ALL }.map { it.key }.toSet()
    }

    fun clearSelection() {
        _selection.value = emptySet()
    }

    /** 選択中（[keys] を渡せばその Shot）の取り込み計画。 */
    fun buildImportPlan(format: ImportFormat, skipImported: Boolean, keys: Set<String> = selection.value): ImportPlan {
        val active = catalogManager.active.value ?: return ImportPlan(emptyList(), 0)
        val shots = allItems().filter { it.key in keys }.map { it.shot }
        return planImport(shots, format, active.cameraSerial, catalogContent.value.importedKeys, skipImported)
    }

    fun startImport(plan: ImportPlan): Boolean {
        val started = importManager.start(plan)
        if (started) _selection.value = emptySet()
        return started
    }

    fun cancelImport() = importManager.cancel()

    fun dismissImportMessage() = importManager.dismissMessage()

    fun allItems(): List<ShotItem> = catalogContent.value.sections.flatMap { it.items }

    private fun toSections(active: ActiveCatalog, shots: List<Shot>, imported: Set<String>): List<DateSection> {
        val profile = active.session.profile.profile
        return shots
            .groupBy { it.capturedAt?.toLocalDate() }
            .map { (date, group) ->
                DateSection(
                    date = date,
                    items = group.map { shot ->
                        val primary = shot.primary
                        val key = StableObjectId.of(active.cameraSerial, primary.info).key
                        ShotItem(
                            key = shot.key,
                            shot = shot,
                            thumbnail = if (profile.hasPtpThumbnail(primary.kind)) {
                                PtpThumbnail(cacheKey = key, info = primary.info, kind = primary.kind)
                            } else {
                                null
                            },
                            preview = PtpPreview(cacheKey = key, info = primary.info, kind = primary.kind)
                                .takeIf { it.supported },
                            badge = badgeOf(shot.kinds),
                            importStatus = shot.importStatus(active.cameraSerial, imported),
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
