package vn.huskydg.cravesagax

import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.os.PowerManager
import android.util.Log
import androidx.core.content.ContextCompat
import org.json.JSONArray
import org.json.JSONObject
import java.io.BufferedReader
import java.io.IOException
import java.io.InputStreamReader
import java.net.HttpURLConnection
import java.net.URL
import java.net.URLEncoder

/**
 * Foreground service giữ tiến trình sống trong lúc dịch từ điển bằng Gemma (nút "Dịch lại bằng Gemma"
 * ở Quản lý từ điển). Việc dịch chạy hoàn toàn ở phía native ([GemmaJob]) nên vẫn tiếp tục khi
 * người dùng thoát trang Options, thoát game hoặc vuốt đóng app khỏi màn hình đa nhiệm.
 * Tiến độ hiện ở thông báo (có nút "Dừng"); kết quả / lỗi là thông báo riêng, không tự biến mất.
 */
class GemmaService : Service() {

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        if (intent?.action == ACTION_STOP) {
            if (GemmaJob.running) GemmaJob.cancel(null) else stopSelf()
            return START_NOT_STICKY
        }
        val snap = GemmaJob.snapshotText()
        val notification = Notifier.jobNotification(this, snap.first, snap.second, snap.third)
        try {
            if (Build.VERSION.SDK_INT >= 29) {
                startForeground(Notifier.ID_JOB, notification, ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC)
            } else {
                startForeground(Notifier.ID_JOB, notification)
            }
        } catch (e: Exception) {
            // Không lên được foreground (hệ thống từ chối) -> job vẫn chạy chừng nào tiến trình còn sống.
            Log.w(TAG, "startForeground thất bại: ${e.message}")
        }
        if (!GemmaJob.running) stopSelf() // job đã kết thúc trước khi service kịp khởi động
        return START_NOT_STICKY
    }

    // Android 14/15: foreground service loại dataSync bị giới hạn thời gian (~6 giờ/lượt).
    override fun onTimeout(startId: Int) {
        GemmaJob.cancel("Hệ thống Android giới hạn thời gian chạy nền của một lượt dịch")
    }

    override fun onTimeout(startId: Int, fgsType: Int) {
        GemmaJob.cancel("Hệ thống Android giới hạn thời gian chạy nền của một lượt dịch")
    }

    companion object {
        private const val TAG = "GemmaService"
        const val ACTION_START = "vn.huskydg.cravesagax.GEMMA_START"
        const val ACTION_STOP = "vn.huskydg.cravesagax.GEMMA_STOP"
    }
}

/** Một lượt dịch Gemma chạy nền. Chỉ có tối đa 1 lượt tại 1 thời điểm. */
object GemmaJob {
    private const val TAG = "GemmaJob"
    private const val DEFAULT_MODEL = "gemma-4-31b-it"
    private const val CHUNK_SIZE = 150
    private const val MAX_RETRIES = 3
    private const val DEFAULT_RETRY_DELAY_MS = 30_000L
    private const val NETWORK_RETRY_DELAY_MS = 5_000L
    private const val WAKELOCK_TIMEOUT_MS = 6 * 60 * 60 * 1000L

    class Item(val key: String, val text: String, val speaker: String?)

    private class HttpError(val code: Int, val body: String) : IOException("HTTP $code $body")
    private class CancelledException : RuntimeException()

    @Volatile var running = false
        private set
    @Volatile private var cancelled = false
    @Volatile private var cancelReason: String? = null
    @Volatile private var conn: HttpURLConnection? = null
    @Volatile private var appCtx: Context? = null

    // Trạng thái hiện tại (để trang Options mở sau vẫn hiện đúng tiến độ).
    @Volatile private var state = "idle" // idle | running | retry | done | error | cancelled
    @Volatile private var total = 0
    @Volatile private var processed = 0
    @Volatile private var translated = 0
    @Volatile private var chapLabel = ""
    @Volatile private var statusText = ""
    @Volatile private var lastRemaining: List<String> = emptyList()

    /** (nội dung thông báo, số câu đã xử lý, tổng số câu) */
    fun snapshotText(): Triple<String, Int, Int> = Triple(
        statusText.ifEmpty { "Đang chuẩn bị dịch..." }, processed, total
    )

    @Synchronized
    fun start(
        ctx: Context, apiKey: String, model: String, instruction: String,
        items: List<Item>, label: String
    ): Boolean {
        if (running) { pushStatus(); return false }
        if (items.isEmpty()) return false
        val app = ctx.applicationContext
        appCtx = app
        running = true
        cancelled = false
        cancelReason = null
        state = "running"
        total = items.size
        processed = 0
        translated = 0
        chapLabel = label
        lastRemaining = emptyList()
        statusText = progressText()

        try {
            ContextCompat.startForegroundService(app, Intent(app, GemmaService::class.java).setAction(GemmaService.ACTION_START))
        } catch (e: Exception) {
            Log.w(TAG, "Không khởi động được foreground service: ${e.message}")
        }
        pushStatus()

        Thread({ run(app, apiKey, model.ifBlank { DEFAULT_MODEL }, instruction, items) }, "gemma-job").apply {
            isDaemon = false
            start()
        }
        return true
    }

    /** [reason] != null: bị hệ thống buộc dừng -> báo như lỗi để người dùng thử lại phần còn lại. */
    fun cancel(reason: String?) {
        if (!running) return
        cancelReason = reason
        cancelled = true
        try { conn?.disconnect() } catch (_: Exception) {}
    }

    private fun progressText() =
        "Đã dịch $processed/$total câu" + if (chapLabel.isNotBlank()) " • chap \"$chapLabel\"" else ""

    /** Gửi trạng thái hiện tại tới các trang Options/popup đang mở. */
    fun pushStatus(updates: Map<String, String>? = null) {
        val o = JSONObject()
            .put("type", "GEMMA_PROGRESS")
            .put("state", state)
            .put("total", total)
            .put("processed", processed)
            .put("translated", translated)
            .put("chapLabel", chapLabel)
            .put("text", statusText)
        if (updates != null && updates.isNotEmpty()) {
            val u = JSONObject()
            for ((k, v) in updates) u.put(k, v)
            o.put("updates", u)
        }
        if ((state == "error" || state == "cancelled") && lastRemaining.isNotEmpty()) {
            o.put("remainingKeys", JSONArray(lastRemaining))
        }
        ExtensionHub.sendToPanels(o.toString())
    }

    // ------------------------------------------------------------------ vòng dịch

    private fun run(ctx: Context, apiKey: String, model: String, instruction: String, items: List<Item>) {
        var wakeLock: PowerManager.WakeLock? = null
        try {
            wakeLock = ctx.getSystemService(PowerManager::class.java)
                ?.newWakeLock(PowerManager.PARTIAL_WAKE_LOCK, "cravesagax:gemma")
                ?.apply { setReferenceCounted(false); acquire(WAKELOCK_TIMEOUT_MS) }
        } catch (e: Exception) {
            Log.w(TAG, "Không giữ được wakelock: ${e.message}")
        }

        try {
            ExtensionHub.init(ctx)
            val chunks = items.chunked(CHUNK_SIZE)
            val contents = JSONArray()
            for ((i, chunk) in chunks.withIndex()) {
                if (cancelled) throw CancelledException()

                val input = JSONArray()
                for (item in chunk) {
                    input.put(
                        JSONObject()
                            .put("original", item.text)
                            .put("name", item.speaker?.takeIf { s -> s.isNotEmpty() } ?: JSONObject.NULL)
                    )
                }
                val userText = if (i == 0) {
                    "$instruction\n\nInput:\n$input"
                } else {
                    "Dịch tiếp danh sách câu thoại sau, giữ đúng phong cách, giọng văn và quy tắc đã áp dụng ở các lượt trước:\n\nInput:\n$input"
                }
                contents.put(turn("user", userText))

                val raw = sendTurn(contents, apiKey, model)
                val map = parseResponse(raw)

                val updates = LinkedHashMap<String, String>()
                for (item in chunk) {
                    val t = map[item.text]
                    if (t != null && t.isNotBlank()) updates[item.key] = t.trim()
                }
                if (updates.isNotEmpty()) applyToDictionary(updates)

                translated += updates.size
                processed += chunk.size
                state = "running"
                statusText = progressText()
                Notifier.updateJob(ctx, statusText, processed, total)
                pushStatus(updates)

                contents.put(turn("model", raw)) // giữ ngữ cảnh cho lượt kế tiếp
            }
            finish(ctx, "done", items, null)
        } catch (e: CancelledException) {
            val reason = cancelReason
            if (reason != null) finish(ctx, "error", items, reason) else finish(ctx, "cancelled", items, null)
        } catch (e: Exception) {
            if (cancelled) {
                val reason = cancelReason
                if (reason != null) finish(ctx, "error", items, reason) else finish(ctx, "cancelled", items, null)
            } else {
                Log.e(TAG, "Dịch Gemma lỗi", e)
                finish(ctx, "error", items, shortError(e))
            }
        } catch (e: OutOfMemoryError) {
            Log.e(TAG, "Hết bộ nhớ khi cập nhật từ điển", e)
            finish(ctx, "error", items, "Hết bộ nhớ khi cập nhật từ điển")
        } finally {
            try { wakeLock?.let { if (it.isHeld) it.release() } } catch (_: Exception) {}
            running = false
            conn = null
            ctx.stopService(Intent(ctx, GemmaService::class.java))
        }
    }

    private fun finish(ctx: Context, finalState: String, items: List<Item>, error: String?) {
        val remaining = items.drop(processed).map { it.key }
        lastRemaining = if (finalState == "done") emptyList() else remaining
        state = finalState
        val label = if (chapLabel.isNotBlank()) " trong chap \"$chapLabel\"" else ""
        when (finalState) {
            "done" -> {
                statusText = "Đã dịch xong $translated/$total câu thoại$label."
                Notifier.show(ctx, "done", statusText, openOptions = true, persistent = true)
            }
            "cancelled" -> {
                statusText = "Đã dừng dịch: đã dịch $translated/$total câu, còn ${remaining.size} câu chưa dịch."
            }
            else -> {
                statusText = "Lỗi dịch bằng Gemma: $error\nĐã lưu $translated câu, còn ${remaining.size} câu chưa dịch — " +
                    "mở Quản lý từ điển và bấm \"Thử lại phần còn lại\"."
                Notifier.show(ctx, "error", statusText, openOptions = true, persistent = true)
            }
        }
        pushStatus()
    }

    private fun turn(role: String, text: String) = JSONObject()
        .put("role", role)
        .put("parts", JSONArray().put(JSONObject().put("text", text)))

    // ------------------------------------------------------------------ từ điển

    /** Ghi các bản dịch mới vào translationDict (đọc-sửa-ghi trên luồng storage, không đè phần người dùng vừa sửa). */
    private fun applyToDictionary(updates: Map<String, String>) {
        ExtensionHub.mutateStorage("translationDict") { raw ->
            val dict = if (raw.isNullOrBlank()) JSONObject() else JSONObject(raw)
            val now = System.currentTimeMillis() / 1000
            for ((k, v) in updates) {
                val entry = dict.opt(k)
                if (entry is JSONObject) {
                    entry.put("translated", v)
                } else {
                    dict.put(
                        k, JSONObject()
                            .put("translated", v)
                            .put("name", JSONObject.NULL)
                            .put("chap", JSONObject.NULL)
                            .put("time", now)
                    )
                }
            }
            dict.toString()
        }
    }

    // ------------------------------------------------------------------ gọi API

    private fun parseResponse(rawText: String): Map<String, String> {
        val text = rawText.trim()
        if (text.isEmpty()) throw IOException("Gemma không trả về nội dung")
        val arr: JSONArray = try {
            toResponseArray(text)
        } catch (e: Exception) {
            // Model đôi khi bọc JSON trong text thừa -> cắt từ dấu mở đầu tới dấu đóng cuối.
            val s = text.indexOfFirst { it == '{' || it == '[' }
            val e2 = text.indexOfLast { it == '}' || it == ']' }
            if (s == -1 || e2 <= s) throw e
            toResponseArray(text.substring(s, e2 + 1))
        }
        val out = HashMap<String, String>()
        for (i in 0 until arr.length()) {
            val o = arr.optJSONObject(i) ?: continue
            val original = o.opt("original")
            val translatedText = o.opt("translated")
            if (original is String && translatedText is String) out[original] = translatedText
        }
        return out
    }

    private fun toResponseArray(json: String): JSONArray {
        val v = org.json.JSONTokener(json).nextValue()
        return when (v) {
            is JSONArray -> v
            is JSONObject -> v.optJSONArray("response") ?: JSONArray()
            else -> throw IOException("Phản hồi của Gemma không đúng định dạng JSON")
        }
    }

    /** Gửi 1 turn; tự thử lại khi 429 (theo retryDelay server gợi ý) hoặc khi rớt mạng. */
    private fun sendTurn(contents: JSONArray, apiKey: String, model: String): String {
        var attempt = 1
        while (true) {
            if (cancelled) throw CancelledException()
            try {
                return sendOnce(contents, apiKey, model)
            } catch (e: HttpError) {
                if (cancelled) throw CancelledException()
                if (e.code != 429 || attempt >= MAX_RETRIES) throw e
                val delay = parseRetryDelayMs(e.body) ?: DEFAULT_RETRY_DELAY_MS
                waitBeforeRetry("Gemma bị giới hạn quota (429), thử lại sau ${delay / 1000}s (lần $attempt/$MAX_RETRIES)...", delay)
            } catch (e: IOException) {
                if (cancelled) throw CancelledException()
                if (attempt >= MAX_RETRIES) throw e
                waitBeforeRetry("Mất kết nối mạng, thử lại sau ${NETWORK_RETRY_DELAY_MS / 1000}s (lần $attempt/$MAX_RETRIES)...", NETWORK_RETRY_DELAY_MS)
            }
            attempt++
        }
    }

    private fun waitBeforeRetry(message: String, delayMs: Long) {
        state = "retry"
        statusText = message
        appCtx?.let { Notifier.updateJob(it, message, processed, total) }
        pushStatus()
        val end = System.currentTimeMillis() + delayMs
        while (System.currentTimeMillis() < end) {
            if (cancelled) throw CancelledException()
            try { Thread.sleep(500) } catch (_: InterruptedException) { throw CancelledException() }
        }
        state = "running"
        statusText = progressText()
    }

    private fun sendOnce(contents: JSONArray, apiKey: String, model: String): String {
        val url = URL(
            "https://generativelanguage.googleapis.com/v1beta/models/$model:streamGenerateContent" +
                "?alt=sse&key=${URLEncoder.encode(apiKey, "UTF-8")}"
        )
        val body = JSONObject()
            .put("contents", contents)
            .put(
                "generationConfig", JSONObject()
                    .put("temperature", 0.35)
                    .put("responseMimeType", "application/json")
                    .put("thinkingConfig", JSONObject().put("thinkingLevel", "MINIMAL"))
            )
            .toString()

        val c = url.openConnection() as HttpURLConnection
        conn = c
        try {
            c.requestMethod = "POST"
            c.connectTimeout = 30_000
            c.readTimeout = 180_000
            c.doOutput = true
            c.setRequestProperty("Content-Type", "application/json")
            c.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }

            val code = c.responseCode
            if (code !in 200..299) {
                val err = c.errorStream?.bufferedReader(Charsets.UTF_8)?.use { it.readText() } ?: ""
                throw HttpError(code, err)
            }

            val sb = StringBuilder()
            BufferedReader(InputStreamReader(c.inputStream, Charsets.UTF_8)).use { reader ->
                while (true) {
                    val line = reader.readLine() ?: break
                    val t = line.trim()
                    if (!t.startsWith("data:")) continue
                    val json = t.substring(5).trim()
                    if (json.isEmpty() || json == "[DONE]") continue
                    val data = try { JSONObject(json) } catch (_: Exception) { continue }
                    val parts = data.optJSONArray("candidates")?.optJSONObject(0)
                        ?.optJSONObject("content")?.optJSONArray("parts") ?: continue
                    for (i in 0 until parts.length()) {
                        val p = parts.optJSONObject(i) ?: continue
                        if (!p.optBoolean("thought", false)) sb.append(p.optString("text", ""))
                    }
                }
            }
            return sb.toString()
        } finally {
            if (conn === c) conn = null
            c.disconnect()
        }
    }

    /** "retryDelay": "33s" trong details của lỗi 429 -> mili-giây (+1s cho chắc). */
    private fun parseRetryDelayMs(errBody: String): Long? {
        try {
            val details = JSONObject(errBody).optJSONObject("error")?.optJSONArray("details") ?: return null
            for (i in 0 until details.length()) {
                val d = details.optJSONObject(i) ?: continue
                val m = Regex("^([\\d.]+)s$").find(d.optString("retryDelay", "")) ?: continue
                return Math.ceil(m.groupValues[1].toDouble() * 1000).toLong() + 1000
            }
        } catch (_: Exception) {
        }
        return null
    }

    private fun shortError(e: Throwable): String {
        if (e is HttpError) {
            val msg = try {
                JSONObject(e.body).getJSONObject("error").getString("message")
            } catch (_: Exception) {
                e.body
            }
            return "HTTP ${e.code}: ${msg.trim().take(200)}"
        }
        return (e.message ?: e.javaClass.simpleName).trim().take(200)
    }
}
