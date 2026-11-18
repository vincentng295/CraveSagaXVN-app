package vn.huskydg.cravesagax

import android.content.Context
import android.net.Uri
import android.util.Log
import java.io.File
import java.util.concurrent.ExecutorService
import java.util.concurrent.Executors

/**
 * Thay cho chrome.storage.local. Mỗi key lưu 1 file chứa nguyên văn JSON (không parse ở phía native)
 * để tránh tốn bộ nhớ/CPU với translationDict (~11 MB). Mọi truy cập đều chạy trên luồng [io].
 */
class ExtensionStorage(private val context: Context) {
    val io: ExecutorService = Executors.newSingleThreadExecutor()

    private val dir = File(context.filesDir, "ext-storage").apply { mkdirs() }
    private val map = LinkedHashMap<String, String>()

    /** Gọi 1 lần lúc khởi động; các tác vụ sau đó xếp hàng sau bước nạp này. */
    fun init() {
        io.execute {
            try {
                dir.listFiles()?.forEach { f ->
                    if (f.isFile && f.name.endsWith(".json")) {
                        val key = Uri.decode(f.name.removeSuffix(".json"))
                        map[key] = f.readText(Charsets.UTF_8)
                    }
                }
                if (!map.containsKey("translationDict")) seedDictionary()
            } catch (e: Exception) {
                Log.e(TAG, "Nạp storage lỗi", e)
            }
        }
    }

    // Tương đương background.js (onInstalled): lần đầu nạp pretranslated.json đóng gói sẵn.
    private fun seedDictionary() {
        try {
            val text = context.assets.open("ext/pretranslated.json").use { it.readBytes().toString(Charsets.UTF_8) }
            put("translationDict", text)
            Log.i(TAG, "Đã nạp pretranslated.json lần đầu")
        } catch (e: Exception) {
            Log.e(TAG, "Không nạp được pretranslated.json", e)
        }
    }

    private fun fileFor(key: String) = File(dir, Uri.encode(key) + ".json")

    private fun put(key: String, raw: String) {
        map[key] = raw
        val target = fileFor(key)
        val tmp = File(dir, target.name + ".tmp")
        tmp.writeText(raw, Charsets.UTF_8)
        if (!tmp.renameTo(target)) {
            target.delete()
            tmp.renameTo(target)
        }
    }

    fun get(names: List<String>?): Map<String, String> =
        if (names == null) LinkedHashMap(map)
        else names.filter { map.containsKey(it) }.associateWith { map.getValue(it) }

    /** @return các key thật sự đổi -> JSON mới (null nếu bị xóa). */
    fun set(items: Map<String, String>): Map<String, String?> {
        val changed = LinkedHashMap<String, String?>()
        for ((k, v) in items) {
            if (map[k] == v) continue
            try {
                put(k, v)
                changed[k] = v
            } catch (e: Exception) {
                Log.e(TAG, "Ghi key $k lỗi", e)
            }
        }
        return changed
    }

    fun remove(keys: List<String>): Map<String, String?> {
        val changed = LinkedHashMap<String, String?>()
        for (k in keys) {
            if (map.remove(k) != null) {
                fileFor(k).delete()
                changed[k] = null
            }
        }
        return changed
    }

    fun clear(): Map<String, String?> = remove(map.keys.toList())

    companion object {
        private const val TAG = "ExtStorage"
    }
}
