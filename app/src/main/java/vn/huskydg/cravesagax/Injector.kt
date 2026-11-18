package vn.huskydg.cravesagax

import android.content.Context
import org.json.JSONObject

/**
 * Ghép script chèn vào trang (thay cho content_scripts của manifest.json).
 *  - Trang game: shim chrome + content.js + injected.js, chỉ chạy ở các URL khớp manifest gốc.
 *  - Panel/Options: shim chrome toàn cục + chỉnh giao diện.
 */
object Injector {
    const val EXT_ORIGIN = "https://appassets.androidplatform.net"
    const val EXT_BASE = "$EXT_ORIGIN/assets/ext/"

    const val AISTUDIO_ORIGIN = "https://aistudio.google.com"
    val AISTUDIO_ORIGINS: Set<String> = setOf(AISTUDIO_ORIGIN)

    /** Origin cho addWebMessageListener / addDocumentStartJavaScript (khớp "matches" của manifest). */
    val CONTENT_ORIGINS: Set<String> = setOf(
        "https://gi.nutaku.com",
        "https://d1njotu2ezlj2j.cloudfront.net",
        "https://d2z1bqkh6aowmt.cloudfront.net",
        "https://gg-client-r.crave-saga.net"
    )

    /** Phần đường dẫn của "matches" – WebView chỉ lọc theo origin nên phải kiểm tra thêm trong JS. */
    private val CONTENT_URL_REGEXES = listOf(
        "^https://gi\\.nutaku\\.com/play-frame/crave-saga-x/",
        "^https://d1njotu2ezlj2j\\.cloudfront\\.net/",
        "^https://d2z1bqkh6aowmt\\.cloudfront\\.net/",
        "^https://gg-client-r\\.crave-saga\\.net/client/1\\.1\\.113/index_dmm\\.html(?:[?#].*)?$"
    )

    private var gameCache: String? = null
    private var panelCache: String? = null

    private fun asset(ctx: Context, path: String): String =
        ctx.assets.open(path).bufferedReader(Charsets.UTF_8).use { it.readText() }

    @Synchronized
    fun gameScript(ctx: Context): String {
        gameCache?.let { return it }
        val regexes = CONTENT_URL_REGEXES.joinToString(",") { "new RegExp(${JSONObject.quote(it)})" }
        val script = buildString {
            append("(function(){\n")
            append("var __ok=[").append(regexes).append("].some(function(r){return r.test(location.href)});\n")
            append("if(!__ok||!window.CraveBridge)return;\n")
            append(asset(ctx, "inject/chrome-shim.js")).append("\n")
            append("var __chrome=__createCraveChrome({panel:false});\n")
            // content.js (isolated world trong Chrome) -> bọc riêng, nhận chrome/fetch qua tham số.
            append("(function(chrome,fetch){\n")
            append(asset(ctx, "inject/content.js")).append("\n")
            append("})(__chrome,__chrome.__fetch);\n")
            // injected.js (MAIN world trong Chrome) -> cùng trang nhưng không thấy biến chrome.
            append("(function(){\n")
            append(asset(ctx, "inject/injected.js")).append("\n")
            append("})();\n")
            append("})();\n")
        }
        gameCache = script
        return script
    }

    private var aiStudioCache: String? = null

    /**
     * Mini trình duyệt Google AI Studio: get-api-key.js (hook XHR ListCloudApiKeys) + get-api-key-bridge.js
     * (chép nguyên văn từ extension; bridge dùng chrome.storage.local của lớp shim).
     */
    @Synchronized
    fun aiStudioScript(ctx: Context): String {
        aiStudioCache?.let { return it }
        val script = buildString {
            append("(function(){\n")
            append("if(!window.CraveBridge)return;\n")
            append(asset(ctx, "inject/chrome-shim.js")).append("\n")
            append("var __chrome=__createCraveChrome({panel:false});\n")
            append("(function(chrome){\n").append(asset(ctx, "inject/get-api-key-bridge.js")).append("\n})(__chrome);\n")
            append("})();\n")
            append("(function(){\n").append(asset(ctx, "inject/get-api-key.js")).append("\n})();\n")
        }
        aiStudioCache = script
        return script
    }

    @Synchronized
    fun panelScript(ctx: Context): String {
        panelCache?.let { return it }
        val script = buildString {
            append("(function(){\n")
            append(asset(ctx, "inject/chrome-shim.js")).append("\n")
            append("var c=__createCraveChrome({panel:true});\n")
            append("try{Object.defineProperty(window,'chrome',{value:c,configurable:true,writable:true});}catch(e){window.chrome=c;}\n")
            append("window.fetch=c.__fetch;\n")
            append("})();\n")
            append(asset(ctx, "inject/panel-patch.js")).append("\n")
        }
        panelCache = script
        return script
    }
}
