package io.github.kaitosiba.fujiptp.geotag

import android.app.Application
import android.content.Intent
import android.net.Uri
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.viewModelScope
import io.github.kaitosiba.fujiptp.FujiPtpApp
import io.github.kaitosiba.fujiptp.camera.MediaKind
import io.github.kaitosiba.fujiptp.importer.ImportedFileEntity
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import java.time.Duration

/** 取り込んだファイル 1 件の付与予定。 */
data class GeotagRow(
    val file: ImportedFileEntity,
    val photo: PhotoInstant?,
    val match: GeoMatch?,
)

data class GeotagSummary(
    val total: Int,
    val interpolated: Int,
    val nearest: Int,
    val unmatched: Int,
    val noTime: Int,
)

sealed interface GeotagUiState {
    /** GPX フォルダが未設定 */
    data object NeedFolder : GeotagUiState

    data class Loading(val message: String) : GeotagUiState

    data class Ready(
        val rows: List<GeotagRow>,
        val summary: GeotagSummary,
        /** 写真の撮影期間付近のトラック（間引き済み） */
        val track: List<TrackPoint>,
        val gpxFiles: Int,
        val failedGpxFiles: Int,
        val suggestion: ClockShiftSuggestion?,
    ) : GeotagUiState

    data class Failed(val message: String) : GeotagUiState
}

/**
 * 取り込み済みファイルの撮影時刻と GPX を突き合わせ、付与予定の位置を出す（M3 は書き込みなしの確認まで）。
 */
class GeotagViewModel(app: Application) : AndroidViewModel(app) {

    private val container = (app as FujiPtpApp).container
    private val settings = container.geotagSettings
    private val gpxRepository = container.gpxRepository
    private val dao = container.database.importedFiles()
    private val resolver = app.contentResolver

    val config: StateFlow<GeotagConfig> = settings.config

    private val _state = MutableStateFlow<GeotagUiState>(GeotagUiState.Loading("読み込み中"))
    val state: StateFlow<GeotagUiState> = _state.asStateFlow()

    init {
        viewModelScope.launch {
            combine(dao.all(), settings.config) { files, config -> files to config }
                .collectLatest { (files, config) ->
                    try {
                        recompute(files, config)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        Log.w(TAG, "geotag recompute failed", e)
                        _state.value = GeotagUiState.Failed(e.message ?: e.toString())
                    }
                }
        }
    }

    /** SAF で選ばれた GPX フォルダを保存する（読み取り権限を永続化する）。 */
    fun setGpxTree(uri: Uri) {
        resolver.takePersistableUriPermission(uri, Intent.FLAG_GRANT_READ_URI_PERMISSION)
        settings.setGpxTree(uri)
    }

    fun adjustClock(delta: Duration) = settings.setClockCorrection(config.value.clockCorrection.plus(delta))

    fun resetClock() = settings.setClockCorrection(Duration.ZERO)

    fun applySuggestion(suggestion: ClockShiftSuggestion) = adjustClock(suggestion.shift)

    private suspend fun recompute(files: List<ImportedFileEntity>, config: GeotagConfig) = withContext(Dispatchers.IO) {
        val tree = config.gpxTreeUri
        if (tree == null) {
            _state.value = GeotagUiState.NeedFolder
            return@withContext
        }
        val targets = files.filter { it.kind != MediaKind.VIDEO.name }

        // 取り込み時に EXIF を読んでいない古い記録は、ここで読んで保存する（保存すると再計算が走る）
        val missing = targets.filter { !it.exifRead }
        if (missing.isNotEmpty()) {
            missing.forEachIndexed { i, file ->
                _state.value = GeotagUiState.Loading("撮影時刻を読み込み中 ${i + 1} / ${missing.size}")
                val exif = ExifTimeReader.read(resolver, Uri.parse(file.contentUri))
                dao.updateExif(file.stableKey, exif?.dateTimeOriginal, exif?.subSecTimeOriginal, exif?.offsetTimeOriginal)
            }
            return@withContext
        }

        val photos = targets.map { file ->
            val time = file.exifDateTimeOriginal?.let {
                PhotoTime.toInstant(
                    ExifCaptureTime(it, file.exifSubSecTimeOriginal, file.exifOffsetTimeOriginal),
                    config.fallbackZone,
                    config.clockCorrection,
                )
            }
            file to time
        }
        val instants = photos.mapNotNull { it.second?.instant }
        if (instants.isEmpty()) {
            _state.value = GeotagUiState.Ready(emptyList(), GeotagSummary(targets.size, 0, 0, 0, targets.size), emptyList(), 0, 0, null)
            return@withContext
        }

        _state.value = GeotagUiState.Loading("GPX を読み込み中")
        // GPX は撮影より後に更新されているはずなので、最も古い写真の少し前以降に更新されたものだけ読む
        val since = instants.min().minus(Duration.ofDays(2))
        val gpx = gpxRepository.load(tree, since) { name -> _state.value = GeotagUiState.Loading("GPX を読み込み中: $name") }

        val rows = photos.map { (file, time) ->
            GeotagRow(file, time, time?.let { gpx.index.locate(it.instant, config.matchOptions) })
        }
        val summary = GeotagSummary(
            total = rows.size,
            interpolated = rows.count { it.match?.method == MatchMethod.INTERPOLATED },
            nearest = rows.count { it.match?.method == MatchMethod.NEAREST },
            unmatched = rows.count { it.photo != null && it.match == null },
            noTime = rows.count { it.photo == null },
        )
        val track = gpx.index.pointsBetween(instants.min().minus(MARGIN), instants.max().plus(MARGIN)).thin(MAX_TRACK_POINTS)
        _state.value = GeotagUiState.Ready(
            rows = rows,
            summary = summary,
            track = track,
            gpxFiles = gpx.fileCount,
            failedGpxFiles = gpx.failedFiles,
            suggestion = ClockShiftEstimator.suggest(instants, gpx.index, config.matchOptions),
        )
    }

    private fun <T> List<T>.thin(max: Int): List<T> {
        if (size <= max) return this
        val step = (size + max - 1) / max
        return filterIndexed { i, _ -> i % step == 0 || i == lastIndex }
    }

    private companion object {
        const val TAG = "FujiPtp"
        val MARGIN: Duration = Duration.ofHours(1)
        const val MAX_TRACK_POINTS = 5000
    }
}
