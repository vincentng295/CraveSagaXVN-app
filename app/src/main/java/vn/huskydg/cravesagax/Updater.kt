package vn.huskydg.cravesagax

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Build
import android.provider.Settings
import androidx.core.content.FileProvider
import androidx.core.content.pm.PackageInfoCompat
import org.json.JSONObject
import java.io.File
import java.io.IOException
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * Kiểm tra / tải / cài bản cập nhật từ GitHub Release `apk-release`
 * (workflow "Release APK" tạo: CraveSagaX_VN.apk, version.json, changelog.md).
 */
object Updater {
    private const val BASE = "https://github.com/vincentng295/CraveSagaXVN-app/releases/download/apk-release/"
    const val VERSION_URL = BASE + "version.json"
    const val CHANGELOG_URL = BASE + "changelog.md"
    const val APK_URL = BASE + "CraveSagaX_VN.apk"

    class Info(
        val versionCode: Long,
        val versionName: String,
        val shortCommit: String,
        val apkUrl: String,
        val apkSize: Long,
        val sha256: String,
        val changelogUrl: String
    )

    class Current(val code: Long, val name: String)

    fun current(ctx: Context): Current {
        val pi = ctx.packageManager.getPackageInfo(ctx.packageName, 0)
        return Current(PackageInfoCompat.getLongVersionCode(pi), pi.versionName ?: "?")
    }

    private fun open(url: String): HttpURLConnection {
        val c = URL(url).openConnection() as HttpURLConnection
        c.connectTimeout = 10_000
        c.readTimeout = 20_000
        c.instanceFollowRedirects = true
        c.setRequestProperty("User-Agent", "CraveSagaXVN-app")
        c.setRequestProperty("Cache-Control", "no-cache")
        return c
    }

    private fun httpsOnly(url: String) = url.startsWith("https://")

    /** Đọc version.json của bản release mới nhất. Chạy trên thread nền. */
    fun fetchLatest(): Info? {
        val c = open(VERSION_URL)
        try {
            if (c.responseCode != 200) return null
            val j = JSONObject(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
            val apk = j.optString("apkUrl", APK_URL)
            if (!httpsOnly(apk)) return null
            return Info(
                versionCode = j.getLong("versionCode"),
                versionName = j.optString("versionName", ""),
                shortCommit = j.optString("shortCommit", ""),
                apkUrl = apk,
                apkSize = j.optLong("apkSize", 0),
                sha256 = j.optString("sha256", "").lowercase(),
                changelogUrl = j.optString("changelogUrl", CHANGELOG_URL).takeIf { httpsOnly(it) } ?: CHANGELOG_URL
            )
        } finally {
            c.disconnect()
        }
    }

    /** Tải changelog.md rồi đổi sang văn bản thường (bỏ ký hiệu markdown đơn giản). */
    fun fetchChangelog(url: String): String? {
        val c = open(url)
        try {
            if (c.responseCode != 200) return null
            return plain(c.inputStream.bufferedReader(Charsets.UTF_8).use { it.readText() })
        } finally {
            c.disconnect()
        }
    }

    private fun plain(md: String): String = md.replace("\r\n", "\n").lines().joinToString("\n") { raw ->
        var l = raw.trimEnd()
        l = l.replace(Regex("^#{1,6}\\s*"), "")
        l = l.replace(Regex("^\\s*[-*+]\\s+"), "• ")
        l.replace("**", "").replace("__", "").replace("`", "")
    }.trim()

    /** Tải APK vào cache/updates, báo tiến độ 0..100, kiểm tra SHA-256 nếu json có. Chạy trên thread nền. */
    fun download(ctx: Context, info: Info, isCancelled: () -> Boolean, onProgress: (Int) -> Unit): File {
        val dir = File(ctx.cacheDir, "updates").apply { mkdirs() }
        dir.listFiles()?.forEach { it.delete() }
        val out = File(dir, "CraveSagaX_VN.apk")
        val c = open(info.apkUrl)
        try {
            if (c.responseCode != 200) throw IOException("HTTP ${c.responseCode}")
            val total = if (c.contentLengthLong > 0) c.contentLengthLong else info.apkSize
            val md = MessageDigest.getInstance("SHA-256")
            var done = 0L
            var last = -1
            c.inputStream.use { input ->
                out.outputStream().use { os ->
                    val buf = ByteArray(64 * 1024)
                    while (true) {
                        if (isCancelled()) throw IOException("Đã hủy")
                        val n = input.read(buf)
                        if (n < 0) break
                        os.write(buf, 0, n)
                        md.update(buf, 0, n)
                        done += n
                        if (total > 0) {
                            val pct = (done * 100 / total).toInt().coerceIn(0, 100)
                            if (pct != last) { last = pct; onProgress(pct) }
                        }
                    }
                }
            }
            if (info.sha256.isNotEmpty()) {
                val got = md.digest().joinToString("") { "%02x".format(it) }
                if (got != info.sha256) {
                    out.delete()
                    throw IOException("File tải về bị sai mã SHA-256")
                }
            }
            return out
        } catch (e: Exception) {
            out.delete()
            throw e
        } finally {
            c.disconnect()
        }
    }

    /**
     * Mở trình cài đặt hệ thống cho APK đã tải.
     * Trả về false nếu app chưa được phép cài app từ nguồn này (đã mở màn hình cấp quyền; quay lại bấm "Cài đặt" lần nữa).
     */
    fun install(ctx: Context, apk: File): Boolean {
        if (Build.VERSION.SDK_INT >= 26 && !ctx.packageManager.canRequestPackageInstalls()) {
            ctx.startActivity(
                Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${ctx.packageName}"))
                    .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
            )
            return false
        }
        val uri = FileProvider.getUriForFile(ctx, "${ctx.packageName}.fileprovider", apk)
        ctx.startActivity(
            Intent(Intent.ACTION_VIEW)
                .setDataAndType(uri, "application/vnd.android.package-archive")
                .addFlags(Intent.FLAG_GRANT_READ_URI_PERMISSION or Intent.FLAG_ACTIVITY_NEW_TASK)
        )
        return true
    }
}
