package io.github.kaitosiba.fujiptp.importer

import androidx.room.Dao
import androidx.room.Database
import androidx.room.Entity
import androidx.room.Index
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.PrimaryKey
import androidx.room.Query
import androidx.room.RoomDatabase
import androidx.room.migration.Migration
import androidx.sqlite.db.SupportSQLiteDatabase
import kotlinx.coroutines.flow.Flow

/** 取り込んだファイルの記録。取込済み表示と重複スキップ、将来のジオタグ付与に使う。 */
@Entity(tableName = "imported_files", indices = [Index("cameraSerial")])
data class ImportedFileEntity(
    /** StableObjectId.key */
    @PrimaryKey val stableKey: String,
    val cameraSerial: String,
    val fileName: String,
    /** MediaKind の名前 */
    val kind: String,
    val sizeBytes: Long,
    /** 保存先の content:// URI */
    val contentUri: String,
    val relativePath: String,
    /** カメラ時計の撮影日時（ISO-8601、TZ なし） */
    val capturedAt: String?,
    val importedAtMillis: Long,
    /** EXIF DateTimeOriginal（"yyyy:MM:dd HH:mm:ss"）。取り込み後に読む */
    val exifDateTimeOriginal: String? = null,
    val exifSubSecTimeOriginal: String? = null,
    /** EXIF OffsetTimeOriginal（"+09:00" など） */
    val exifOffsetTimeOriginal: String? = null,
    /** EXIF を読み終えたか（読めなかった場合も true にして読み直さない） */
    val exifRead: Boolean = false,
)

@Dao
interface ImportedFileDao {
    @Query("SELECT stableKey FROM imported_files WHERE cameraSerial = :cameraSerial")
    fun keysFor(cameraSerial: String): Flow<List<String>>

    @Query("SELECT * FROM imported_files ORDER BY capturedAt DESC, fileName DESC")
    fun all(): Flow<List<ImportedFileEntity>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportedFileEntity)

    @Query(
        "UPDATE imported_files SET exifDateTimeOriginal = :dateTime, exifSubSecTimeOriginal = :subSec, " +
            "exifOffsetTimeOriginal = :offset, exifRead = 1 WHERE stableKey = :stableKey",
    )
    suspend fun updateExif(stableKey: String, dateTime: String?, subSec: String?, offset: String?)
}

@Database(entities = [ImportedFileEntity::class], version = 2, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importedFiles(): ImportedFileDao

    companion object {
        /** v2: ジオタグ用に EXIF の撮影時刻を持つ */
        val MIGRATION_1_2 = object : Migration(1, 2) {
            override fun migrate(db: SupportSQLiteDatabase) {
                db.execSQL("ALTER TABLE imported_files ADD COLUMN exifDateTimeOriginal TEXT")
                db.execSQL("ALTER TABLE imported_files ADD COLUMN exifSubSecTimeOriginal TEXT")
                db.execSQL("ALTER TABLE imported_files ADD COLUMN exifOffsetTimeOriginal TEXT")
                db.execSQL("ALTER TABLE imported_files ADD COLUMN exifRead INTEGER NOT NULL DEFAULT 0")
            }
        }
    }
}
