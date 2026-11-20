package vn.huskydg.cravesagax

import android.app.Activity
import android.content.Context
import android.content.Intent
import android.net.Uri
import android.webkit.CookieManager
import android.webkit.JsPromptResult
import android.webkit.JsResult
import android.webkit.ValueCallback
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebSettings
import android.webkit.WebView
import android.webkit.WebViewClient
import android.widget.EditText
import androidx.activity.ComponentActivity
import androidx.activity.result.contract.ActivityResultContracts
import androidx.appcompat.app.AlertDialog
import androidx.webkit.WebSettingsCompat
import androidx.webkit.WebViewAssetLoader
import androidx.webkit.WebViewCompat
import androidx.webkit.WebViewFeature

/** Cấu hình WebView dùng chung cho trang game, menu phải và trang Options. */
object WebSetup {
    private const val ASSET_HOST = "appassets.androidplatform.net"

    /** Cần WebView đủ mới (WebMessageListener + DocumentStartScript, Chromium 86+). */
    fun isSupported(): Boolean =
        WebViewFeature.isFeatureSupported(WebViewFeature.WEB_MESSAGE_LISTENER) &&
            WebViewFeature.isFeatureSupported(WebViewFeature.DOCUMENT_START_SCRIPT)

    fun showUnsupported(activity: Activity) {
        AlertDialog.Builder(activity)
            .setTitle("Android System WebView quá cũ")
            .setMessage("App cần Android System WebView (hoặc Chrome) bản mới. Hãy cập nhật trên Google Play rồi mở lại app.")
            .setPositiveButton(android.R.string.ok) { _, _ -> activity.finish() }
            .setOnCancelListener { activity.finish() }
            .show()
    }

    fun configure(wv: WebView, hideWebViewUa: Boolean) {
        val s = wv.settings
        s.javaScriptEnabled = true
        s.domStorageEnabled = true
        @Suppress("DEPRECATION")
        s.databaseEnabled = true
        s.mediaPlaybackRequiresUserGesture = false
        s.mixedContentMode = WebSettings.MIXED_CONTENT_COMPATIBILITY_MODE
        s.useWideViewPort = true
        s.loadWithOverviewMode = true
        s.setSupportZoom(false)
        s.builtInZoomControls = false
        s.setSupportMultipleWindows(false)
        if (hideWebViewUa) {
            // Một số trang chặn/đổi hành vi khi thấy "; wv" trong User-Agent.
            s.userAgentString = s.userAgentString
                .replace("; wv", "")
                .replace(Regex("Version/\\d+\\.\\d+\\s"), "")
        }
        val cm = CookieManager.getInstance()
        cm.setAcceptCookie(true)
        cm.setAcceptThirdPartyCookies(wv, true)
    }

    /** Bỏ header X-Requested-With (chứa package của app) – Google dùng nó để nhận ra WebView khi đăng nhập. */
    fun hideRequestedWithHeader(wv: WebView) {
        if (WebViewFeature.isFeatureSupported(WebViewFeature.REQUESTED_WITH_HEADER_ALLOW_LIST)) {
            WebSettingsCompat.setRequestedWithHeaderOriginAllowList(wv.settings, emptySet())
        }
    }

    fun assetLoader(ctx: Context): WebViewAssetLoader =
        WebViewAssetLoader.Builder()
            .setDomain(ASSET_HOST)
            .addPathHandler("/assets/", WebViewAssetLoader.AssetsPathHandler(ctx))
            .build()

    /** Gắn cầu nối + script cho WebView trang game (nội dung của các nhà phát hành). */
    fun installGame(wv: WebView, ctx: Context) {
        ExtensionHub.attach(wv, ExtensionHub.Kind.CONTENT, Injector.CONTENT_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(wv, Injector.gameScript(ctx), Injector.CONTENT_ORIGINS)
    }

    /** Gắn cầu nối + shim cho WebView hiển thị popup.html / options.html của extension. */
    fun installPanel(wv: WebView, ctx: Context) {
        // popup.html/options.html không có <meta viewport> -> WebView mặc định dựng layout rộng 980px rồi thu nhỏ (chữ rất bé).
        wv.settings.useWideViewPort = false
        wv.settings.loadWithOverviewMode = false
        val origins = setOf(Injector.EXT_ORIGIN)
        ExtensionHub.attach(wv, ExtensionHub.Kind.PANEL, origins)
        WebViewCompat.addDocumentStartJavaScript(wv, Injector.panelScript(ctx), origins)
    }
}

/**
 * WebViewClient cho trang game: giữ điều hướng http(s) trong app.
 * Với game có bước đăng nhập: khi trang rời khỏi trang đăng nhập (đăng nhập thành công, hoặc đã đăng nhập sẵn)
 * thì chuyển sang trang chơi game. Nếu trang game lại đá về trang đăng nhập thì đợi đăng nhập lần nữa.
 */
class GameClient(private val game: Game) : WebViewClient() {
    private var pendingGame = true
    private var hops = 0

    override fun onPageStarted(view: WebView, url: String?, favicon: android.graphics.Bitmap?) {
        // Vào lại trang đăng nhập: bỏ tài khoản nhập dở của lần trước (chưa được xác nhận thành công).
        if (game.login != null && url != null && LoginHelper.isLoginPage(game, url)) LoginHelper.clearPending()
    }

    override fun onPageFinished(view: WebView, url: String?) {
        if (game.login == null || url == null) return
        when {
            LoginHelper.isLoginPage(game, url) -> pendingGame = true
            LoginHelper.isGamePage(game, url) -> { pendingGame = false; hops = 0 }
            pendingGame -> {
                pendingGame = false
                LoginHelper.commitPending(view.context, game) // đăng nhập thành công -> lưu tài khoản
                if (hops++ < MAX_HOPS) view.loadUrl(game.url)
            }
        }
    }

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val scheme = request.url.scheme ?: return true
        return !(scheme == "http" || scheme == "https" || scheme == "about" || scheme == "blob")
    }

    private companion object { const val MAX_HOPS = 3 }
}

/** WebViewClient cho trang của extension (từ assets); liên kết ngoài mở bằng trình duyệt máy. */
class PanelClient(private val activity: Activity, private val loader: WebViewAssetLoader) : WebViewClient() {
    override fun shouldInterceptRequest(view: WebView, request: WebResourceRequest): WebResourceResponse? =
        loader.shouldInterceptRequest(request.url)

    override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
        val url = request.url
        if (url.host == "appassets.androidplatform.net") return false
        try {
            activity.startActivity(Intent(Intent.ACTION_VIEW, url))
        } catch (_: Exception) {
        }
        return true
    }
}

/** Mở trình chọn file cho <input type="file"> (nhập từ điển / nhập cache). */
class FileChooserHelper(activity: ComponentActivity) {
    private var callback: ValueCallback<Array<Uri>>? = null

    private val launcher = activity.registerForActivityResult(ActivityResultContracts.StartActivityForResult()) { r ->
        callback?.onReceiveValue(WebChromeClient.FileChooserParams.parseResult(r.resultCode, r.data))
        callback = null
    }

    fun open(cb: ValueCallback<Array<Uri>>, params: WebChromeClient.FileChooserParams): Boolean {
        callback?.onReceiveValue(null)
        callback = cb
        return try {
            launcher.launch(params.createIntent())
            true
        } catch (e: Exception) {
            callback = null
            cb.onReceiveValue(null)
            false
        }
    }
}

/** alert()/confirm()/prompt() mặc định bị WebView bỏ qua nên phải tự hiện hộp thoại. */
open class AppChromeClient(
    private val activity: Activity,
    private val chooser: FileChooserHelper
) : WebChromeClient() {

    override fun onJsAlert(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (activity.isFinishing) { result.cancel(); return true }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsConfirm(view: WebView, url: String?, message: String?, result: JsResult): Boolean {
        if (activity.isFinishing) { result.cancel(); return true }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm() }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onJsPrompt(
        view: WebView, url: String?, message: String?, defaultValue: String?, result: JsPromptResult
    ): Boolean {
        if (activity.isFinishing) { result.cancel(); return true }
        val input = EditText(activity).apply { setText(defaultValue ?: "") }
        AlertDialog.Builder(activity)
            .setMessage(message)
            .setView(input)
            .setPositiveButton(android.R.string.ok) { _, _ -> result.confirm(input.text.toString()) }
            .setNegativeButton(android.R.string.cancel) { _, _ -> result.cancel() }
            .setOnCancelListener { result.cancel() }
            .show()
        return true
    }

    override fun onShowFileChooser(
        webView: WebView, filePathCallback: ValueCallback<Array<Uri>>, params: FileChooserParams
    ): Boolean = chooser.open(filePathCallback, params)
}
