package io.github.kaitosiba.fujiptp

import android.app.Application
import androidx.room.Room
import coil3.ImageLoader
import coil3.PlatformContext
import coil3.SingletonImageLoader
import coil3.disk.DiskCache
import io.github.kaitosiba.fujiptp.camera.ProfileRegistry
import io.github.kaitosiba.fujiptp.catalog.CatalogManager
import io.github.kaitosiba.fujiptp.connection.CameraConnectionManager
import io.github.kaitosiba.fujiptp.geotag.GeotagSettings
import io.github.kaitosiba.fujiptp.geotag.GpxRepository
import io.github.kaitosiba.fujiptp.importer.AppDatabase
import io.github.kaitosiba.fujiptp.importer.ImportManager
import io.github.kaitosiba.fujiptp.preview.PtpPreviewFetcher
import io.github.kaitosiba.fujiptp.preview.PtpPreviewKeyer
import io.github.kaitosiba.fujiptp.thumbnail.PtpThumbnailFetcher
import io.github.kaitosiba.fujiptp.thumbnail.PtpThumbnailKeyer
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import okio.Path.Companion.toOkioPath
import org.maplibre.android.MapLibre

class FujiPtpApp : Application(), SingletonImageLoader.Factory {

    /** 手動 DI。依存がまだ少ないので Hilt は画面と依存がもっと増えた時点で導入する。 */
    lateinit var container: AppContainer
        private set

    override fun onCreate() {
        super.onCreate()
        container = AppContainer(this)
        MapLibre.getInstance(this)
    }

    override fun newImageLoader(context: PlatformContext): ImageLoader =
        ImageLoader.Builder(context)
            .components {
                add(PtpThumbnailKeyer())
                add(PtpThumbnailFetcher.Factory { container.catalogManager.active.value?.session })
                add(PtpPreviewKeyer())
                add(PtpPreviewFetcher.Factory(cacheDir.resolve("previews")) { container.catalogManager.active.value?.session })
            }
            .diskCache {
                DiskCache.Builder()
                    .directory(cacheDir.resolve("thumbnails").toOkioPath())
                    .maxSizeBytes(THUMBNAIL_CACHE_BYTES)
                    .build()
            }
            .build()

    private companion object {
        /** サムネイル 1 枚 5〜10 KB なので、数万枚分 */
        const val THUMBNAIL_CACHE_BYTES = 256L * 1024 * 1024
    }
}

class AppContainer(app: Application) {
    val applicationScope: CoroutineScope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    val profileRegistry: ProfileRegistry = ProfileRegistry.default()
    val connectionManager: CameraConnectionManager =
        CameraConnectionManager(app, profileRegistry, applicationScope)
    val catalogManager: CatalogManager = CatalogManager(app, connectionManager, applicationScope)
    val database: AppDatabase = Room.databaseBuilder(app, AppDatabase::class.java, "fujiptp.db")
        .addMigrations(AppDatabase.MIGRATION_1_2)
        .build()
    val importManager: ImportManager =
        ImportManager(app, catalogManager, database.importedFiles(), applicationScope)
    val geotagSettings: GeotagSettings = GeotagSettings(app)
    val gpxRepository: GpxRepository = GpxRepository(app.contentResolver)
}
