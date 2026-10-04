package io.github.kaitosiba.fujiptp.camera

import io.github.kaitosiba.fujiptp.ptp.PtpObjectInfo
import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.json.Json
import java.io.File

/** 取得済み ObjectInfo の永続キャッシュ。再接続時の一覧取得を速くするために使う。 */
interface ObjectInfoStore {
    fun load(key: String): List<PtpObjectInfo>?

    fun save(key: String, objects: List<PtpObjectInfo>)
}

class InMemoryObjectInfoStore : ObjectInfoStore {
    private val entries = mutableMapOf<String, List<PtpObjectInfo>>()

    @Synchronized
    override fun load(key: String): List<PtpObjectInfo>? = entries[key]

    @Synchronized
    override fun save(key: String, objects: List<PtpObjectInfo>) {
        entries[key] = objects.toList()
    }
}

/** [directory] 配下にキーごとの JSON ファイルとして保存する。壊れたファイルは無視する。 */
class FileObjectInfoStore(private val directory: File) : ObjectInfoStore {
    private val json = Json { ignoreUnknownKeys = true }
    private val serializer = ListSerializer(PtpObjectInfo.serializer())

    override fun load(key: String): List<PtpObjectInfo>? {
        val file = fileFor(key)
        if (!file.exists()) return null
        return try {
            json.decodeFromString(serializer, file.readText())
        } catch (e: Exception) {
            file.delete()
            null
        }
    }

    override fun save(key: String, objects: List<PtpObjectInfo>) {
        directory.mkdirs()
        val tmp = File(directory, "${fileFor(key).name}.tmp")
        tmp.writeText(json.encodeToString(serializer, objects))
        if (!tmp.renameTo(fileFor(key))) {
            tmp.delete()
        }
    }

    private fun fileFor(key: String) = File(directory, key.replace(Regex("[^A-Za-z0-9._-]"), "_") + ".json")
}
