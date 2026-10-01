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
)

@Dao
interface ImportedFileDao {
    @Query("SELECT stableKey FROM imported_files WHERE cameraSerial = :cameraSerial")
    fun keysFor(cameraSerial: String): Flow<List<String>>

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun upsert(entity: ImportedFileEntity)
}

@Database(entities = [ImportedFileEntity::class], version = 1, exportSchema = false)
abstract class AppDatabase : RoomDatabase() {
    abstract fun importedFiles(): ImportedFileDao
}
