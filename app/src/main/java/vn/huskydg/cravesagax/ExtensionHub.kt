package vn.huskydg.cravesagax

import android.app.Activity
import android.content.ContentValues
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.os.Environment
import android.os.Handler
import android.os.Looper
import android.provider.MediaStore
import android.util.Base64
import android.util.Log
import android.webkit.WebView
import android.widget.Toast
import androidx.webkit.JavaScriptReplyProxy
import androidx.webkit.WebMessageCompat
import androidx.webkit.WebViewCompat
import org.json.JSONObject
import java.io.File
import java.io.FileOutputStream
import java.io.OutputStream
import java.net.HttpURLConnection
import java.net.URL
import java.util.concurrent.ConcurrentHashMap
import java.util.concurrent.CopyOnWriteArrayList
import java.util.concurrent.Executors

/**
 * Phía native của lớp giả lập chrome.* (xem assets/inject/chrome-shim.js).
 * Giữ danh sách các "frame" đang chạy shim để phát sự kiện storage.onChanged và tabs.sendMessage.
 */
object ExtensionHub {
    enum class Kind { CONTENT, PANEL }

    private class Endpoint(val fid: String, val kind: Kind, val proxy: JavaScriptReplyProxy)

    private const val TAG = "ExtensionHub"
    private const val HEADER_SET = "#sset\n"
    private const val HEADER_TAB = "#tab\n"

    private val main = Handler(Looper.getMainLooper())
    private val endpoints = CopyOnWriteArrayList<Endpoint>()
    private val net = Executors.newCachedThreadPool()
    private val connections = ConcurrentHashMap<Int, HttpURLConnection>()

    private lateinit var appContext: Context
    private lateinit var storage: ExtensionStorage
    private var started = false

    /** Activity đang ở trước mặt người dùng (để mở Options, thoát game, hiện Toast...). */
    @Volatile
    var host: Activity? = null

    @Synchronized
    fun init(context: Context) {
        if (started) return
        appContext = context.applicationContext
        storage = ExtensionStorage(appContext)
        storage.init()
        started = true
    }

    /** Gắn cầu nối vào 1 WebView. Phải gọi trước khi loadUrl. */
    fun attach(webView: WebView, kind: Kind, origins: Set<String>) {
        WebViewCompat.addWebMessageListener(
            webView, "CraveBridge", origins
        ) { _, message: WebMessageCompat, _, _, replyProxy ->
            val data = message.data
            if (data != null) onMessage(kind, data, replyProxy)
        }
    }

    // ------------------------------------------------------------------ nhận message

    private fun onMessage(kind: Kind, data: String, proxy: JavaScriptReplyProxy) {
        if (data.startsWith(HEADER_SET)) {
            handleSet(data, proxy)
            return
        }
        if (data.startsWith(HEADER_TAB)) {
            // Chuyển nguyên văn (có thể rất lớn, ví dụ file import cache) – không parse.
            val payload = data.substring(HEADER_TAB.length)
            broadcast("{\"t\":\"msg\",\"msg\":$payload}", Kind.CONTENT)
            return
        }

        val m = try { JSONObject(data) } catch (e: Exception) { return }
        when (m.optString("t")) {
            "hello" -> {
                val fid = m.optString("fid")
                endpoints.removeAll { it.fid == fid }
                endpoints.add(Endpoint(fid, kind, proxy))
            }
            "bye" -> {
                val fid = m.optString("fid")
                endpoints.removeAll { it.fid == fid }
            }
            "sget" -> {
                val id = m.getInt("id")
                val keys = m.optJSONArray("keys")?.let { arr -> List(arr.length()) { arr.getString(it) } }
                storage.io.execute {
                    val items = storage.get(keys)
                    val sb = StringBuilder(items.values.sumOf { it.length } + 64)
                    sb.append("{\"t\":\"res\",\"id\":").append(id).append(",\"items\":{")
                    var first = true
                    for ((k, v) in items) {
                        if (!first) sb.append(',')
                        first = false
                        sb.append(JSONObject.quote(k)).append(':').append(v)
                    }
                    sb.append("}}")
                    reply(proxy, sb.toString())
                }
            }
            "srem" -> {
                val id = m.getInt("id")
                val arr = m.getJSONArray("keys")
                val keys = List(arr.length()) { arr.getString(it) }
                storage.io.execute {
                    val changed = storage.remove(keys)
                    reply(proxy, "{\"t\":\"res\",\"id\":$id}")
                    announce(changed)
                }
            }
            "sclear" -> {
                val id = m.getInt("id")
                storage.io.execute {
                    val changed = storage.clear()
                    reply(proxy, "{\"t\":\"res\",\"id\":$id}")
                    announce(changed)
                }
            }
            "open" -> openUrl(m.optString("url"))
            "app" -> handleAppAction(m.optString("action"))
            "fbegin" -> fileBegin(m.getInt("id"), m.optString("name"), m.optString("mime"))
            "fchunk" -> fileChunk(m.getInt("id"), m.getString("d"))
            "fend" -> fileEnd(m.getInt("id"))
            "fetch" -> startFetch(m, proxy)
            "fcancel" -> connections.remove(m.optInt("id"))?.disconnect()
        }
    }

    // ------------------------------------------------------------------ storage

    private fun handleSet(data: String, proxy: JavaScriptReplyProxy) {
        try {
            val headerEnd = data.indexOf('\n', HEADER_SET.length)
            val header = JSONObject(data.substring(HEADER_SET.length, headerEnd))
            val id = header.getInt("id")
            val keys = header.getJSONArray("keys")
            val lens = header.getJSONArray("lens")
            var pos = headerEnd + 1
            val items = LinkedHashMap<String, String>()
            for (i in 0 until keys.length()) {
                val len = lens.getInt(i)
                items[keys.getString(i)] = data.substring(pos, pos + len)
                pos += len
            }
            storage.io.execute {
                val changed = storage.set(items)
                reply(proxy, "{\"t\":\"res\",\"id\":$id}")
                announce(changed)
            }
        } catch (e: Exception) {
            Log.e(TAG, "Message ghi storage không hợp lệ", e)
        }
    }

    /** Phát chrome.storage.onChanged tới mọi frame/trang (kể cả bên vừa ghi, giống Chrome). */
    private fun announce(changed: Map<String, String?>) {
        if (changed.isEmpty()) return
        val sb = StringBuilder("{\"t\":\"changed\",\"changes\":{")
        var first = true
        for ((k, v) in changed) {
            if (!first) sb.append(',')
            first = false
            sb.append(JSONObject.quote(k)).append(':')
            if (v == null) sb.append("{}") else sb.append("{\"newValue\":").append(v).append('}')
        }
        sb.append("}}")
        broadcast(sb.toString(), null)
    }

    // ------------------------------------------------------------------ gửi message

    private fun reply(proxy: JavaScriptReplyProxy, message: String) {
        main.post {
            try { proxy.postMessage(message) } catch (e: Exception) { Log.w(TAG, "reply lỗi: ${e.message}") }
        }
    }

    private fun broadcast(message: String, only: Kind?) {
        main.post {
            for (ep in endpoints) {
                if (only != null && ep.kind != only) continue
                try { ep.proxy.postMessage(message) } catch (e: Exception) { endpoints.remove(ep) }
            }
        }
    }

    // ------------------------------------------------------------------ hành động của app

    private fun openUrl(url: String) {
        main.post {
            val act = host ?: return@post
            when {
                url.startsWith(Injector.EXT_BASE + "options.html") ->
                    act.startActivity(Intent(act, OptionsActivity::class.java))
                url.startsWith("http://") || url.startsWith("https://") ->
                    try { act.startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(url))) } catch (e: Exception) {
                        Toast.makeText(act, "Không mở được liên kết", Toast.LENGTH_SHORT).show()
                    }
            }
        }
    }

    private fun handleAppAction(action: String) {
        main.post {
            val game = host as? GameActivity ?: return@post
            when (action) {
                "pick" -> game.finish()
                "reload" -> game.reloadGame()
            }
        }
    }

    // ------------------------------------------------------------------ lưu file vào Downloads

    private class PendingFile(val out: OutputStream, val name: String, val uri: Uri?, val path: String?)

    private val files = HashMap<Int, PendingFile>() // chỉ truy cập trên luồng io của storage

    private fun safeName(raw: String): String {
        val cleaned = raw.replace(Regex("[\\\\/:*?\"<>|]"), "_").trim()
        return if (cleaned.isEmpty()) "download" else cleaned
    }

    private fun fileBegin(id: Int, rawName: String, mime: String) {
        storage.io.execute {
            try {
                val name = safeName(rawName)
                val pending: PendingFile
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                    val values = ContentValues().apply {
                        put(MediaStore.MediaColumns.DISPLAY_NAME, name)
                        put(MediaStore.MediaColumns.MIME_TYPE, mime.ifEmpty { "application/octet-stream" })
                        put(MediaStore.MediaColumns.RELATIVE_PATH, Environment.DIRECTORY_DOWNLOADS + "/CraveSagaX")
                        put(MediaStore.MediaColumns.IS_PENDING, 1)
                    }
                    val uri = appContext.contentResolver.insert(MediaStore.Downloads.EXTERNAL_CONTENT_URI, values)
                        ?: throw IllegalStateException("MediaStore insert thất bại")
                    val out = appContext.contentResolver.openOutputStream(uri)
                        ?: throw IllegalStateException("Không mở được output stream")
                    pending = PendingFile(out, name, uri, "Download/CraveSagaX/$name")
                } else {
                    val dir = appContext.getExternalFilesDir(Environment.DIRECTORY_DOWNLOADS) ?: appContext.filesDir
                    dir.mkdirs()
                    val f = File(dir, name)
                    pending = PendingFile(FileOutputStream(f), name, null, f.absolutePath)
                }
                files[id] = pending
            } catch (e: Exception) {
                Log.e(TAG, "Không tạo được file tải về", e)
                toast("Không lưu được file: ${e.message}")
            }
        }
    }

    private fun fileChunk(id: Int, b64: String) {
        storage.io.execute {
            val f = files[id] ?: return@execute
            try {
                f.out.write(Base64.decode(b64, Base64.DEFAULT))
            } catch (e: Exception) {
                Log.e(TAG, "Ghi file lỗi", e)
                files.remove(id)
                try { f.out.close() } catch (_: Exception) {}
                toast("Ghi file lỗi: ${e.message}")
            }
        }
    }

    private fun fileEnd(id: Int) {
        storage.io.execute {
            val f = files.remove(id) ?: return@execute
            try {
                f.out.close()
                if (f.uri != null) {
                    val values = ContentValues().apply { put(MediaStore.MediaColumns.IS_PENDING, 0) }
                    appContext.contentResolver.update(f.uri, values, null, null)
                }
                toast("Đã lưu: ${f.path}")
            } catch (e: Exception) {
                Log.e(TAG, "Đóng file lỗi", e)
                toast("Lưu file lỗi: ${e.message}")
            }
        }
    }

    private fun toast(text: String) {
        main.post { Toast.makeText(appContext, text, Toast.LENGTH_LONG).show() }
    }

    // ------------------------------------------------------------------ fetch qua native (Gemini API)

    private fun startFetch(m: JSONObject, proxy: JavaScriptReplyProxy) {
        val id = m.getInt("id")
        val url = m.getString("url")
        // Chỉ cho phép gọi Gemini/Gemma API, tránh biến app thành proxy mở cho trang bất kỳ.
        if (!url.startsWith("https://generativelanguage.googleapis.com/")) {
            reply(proxy, "{\"t\":\"ferr\",\"id\":$id,\"msg\":\"host not allowed\"}")
            return
        }
        val method = m.optString("method", "GET")
        val headers = m.optJSONObject("headers")
        val body = if (m.isNull("body")) null else m.optString("body")

        net.execute {
            var conn: HttpURLConnection? = null
            try {
                val c = URL(url).openConnection() as HttpURLConnection
                conn = c
                connections[id] = c
                c.requestMethod = method
                c.connectTimeout = 30_000
                c.readTimeout = 180_000
                if (headers != null) {
                    val it = headers.keys()
                    while (it.hasNext()) {
                        val k = it.next()
                        c.setRequestProperty(k, headers.getString(k))
                    }
                }
                if (body != null) {
                    c.doOutput = true
                    c.outputStream.use { out -> out.write(body.toByteArray(Charsets.UTF_8)) }
                }
                val code = c.responseCode
                reply(proxy, "{\"t\":\"fh\",\"id\":$id,\"status\":$code}")
                val stream = if (code in 200..399) c.inputStream else c.errorStream
                stream?.use { input ->
                    val buf = ByteArray(8192)
                    while (true) {
                        val n = input.read(buf)
                        if (n < 0) break
                        if (n == 0) continue
                        val d = Base64.encodeToString(buf, 0, n, Base64.NO_WRAP)
                        reply(proxy, "{\"t\":\"fc\",\"id\":$id,\"d\":\"$d\"}")
                    }
                }
                reply(proxy, "{\"t\":\"fe\",\"id\":$id}")
            } catch (e: Exception) {
                reply(proxy, "{\"t\":\"ferr\",\"id\":$id,\"msg\":${JSONObject.quote(e.message ?: "error")}}")
            } finally {
                connections.remove(id)
                conn?.disconnect()
            }
        }
    }
}
