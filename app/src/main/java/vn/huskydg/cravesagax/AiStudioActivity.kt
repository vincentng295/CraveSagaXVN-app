package vn.huskydg.cravesagax

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.webkit.WebChromeClient
import android.webkit.WebResourceRequest
import android.webkit.WebView
import android.webkit.WebViewClient
import android.webkit.CookieManager
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.TextView
import android.widget.Toast
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.webkit.WebViewCompat
import org.json.JSONArray

/**
 * Mini trình duyệt cho Google AI Studio: đăng nhập Google rồi tự bắt API key.
 * Script get-api-key.js (hook XHR ListCloudApiKeys) + get-api-key-bridge.js của extension được chèn nguyên văn;
 * key được lưu vào "chrome.storage.local.fetchedApiKeys" nên popup (ô chọn API key) tự cập nhật.
 */
class AiStudioActivity : AppCompatActivity() {

    private lateinit var web: WebView
    private lateinit var progress: ProgressBar
    private val chooser = FileChooserHelper(this)
    private var autoClose = false

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val startUrl = intent.getStringExtra(EXTRA_URL)?.takeIf { it.startsWith(Injector.AISTUDIO_ORIGIN) } ?: START_URL
        autoClose = Uri.parse(startUrl).getQueryParameter("auto_close") == "1"

        if (!WebSetup.isSupported()) {
            WebSetup.showUnsupported(this)
            return
        }
        ExtensionHub.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        // ---- WebView ----
        web = WebView(this)
        WebSetup.configure(web, hideWebViewUa = true) // Google từ chối đăng nhập khi thấy "; wv" trong User-Agent
        WebSetup.hideRequestedWithHeader(web)         // và header X-Requested-With = package của app
        web.setBackgroundColor(0xFF0F1220.toInt())
        web.webViewClient = object : WebViewClient() {
            override fun shouldOverrideUrlLoading(view: WebView, request: WebResourceRequest): Boolean {
                val s = request.url.scheme ?: return true
                return !(s == "http" || s == "https" || s == "about" || s == "blob")
            }
        }
        web.webChromeClient = object : AppChromeClient(this, chooser) {
            override fun onProgressChanged(view: WebView, newProgress: Int) {
                progress.progress = newProgress
                progress.visibility = if (newProgress in 1..99) View.VISIBLE else View.INVISIBLE
            }

            override fun onCloseWindow(window: WebView?) {
                finish() // bridge gọi window.close() khi auto_close=1
            }
        }
        ExtensionHub.attach(web, ExtensionHub.Kind.CONTENT, Injector.AISTUDIO_ORIGINS)
        WebViewCompat.addDocumentStartJavaScript(web, Injector.aiStudioScript(this), Injector.AISTUDIO_ORIGINS)

        // ---- Thanh trên ----
        val titles = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(text("Google AI Studio", 16f, Color.WHITE, bold = true))
            addView(text("Đăng nhập Google → app tự lấy API Key", 11f, 0xFFB4B8CC.toInt()))
        }
        val bar = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            setBackgroundColor(0xFF1A1F36.toInt())
            setPadding(dp(14), dp(4), dp(4), dp(4))
            addView(titles, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(barButton("⟳") { web.reload() })
            addView(barButton("↗") { openExternal() })
            addView(barButton("✕") { finish() })
        }

        progress = ProgressBar(this, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100
            visibility = View.INVISIBLE
        }

        val root = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setBackgroundColor(0xFF0F1220.toInt())
            addView(bar, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
            addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(3)))
            addView(web, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        }
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val b = insets.getInsets(
                WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout() or WindowInsetsCompat.Type.ime()
            )
            v.setPadding(b.left, b.top, b.right, b.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (web.canGoBack()) web.goBack() else finish()
            }
        })

        web.loadUrl(startUrl)
    }

    /** ExtensionHub gọi khi chrome.storage.local.fetchedApiKeys vừa thay đổi (bridge đã lưu key). */
    fun onKeysFetched(json: String) {
        val n = try { JSONArray(json).length() } catch (_: Exception) { 0 }
        if (n <= 0) return
        Toast.makeText(this, "Đã lấy $n API key", Toast.LENGTH_LONG).show()
        if (autoClose) finish()
    }

    private fun openExternal() {
        try {
            startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(web.url ?: START_URL)))
        } catch (_: Exception) {
        }
    }

    private fun barButton(label: String, onClick: () -> Unit) = TextView(this).apply {
        text = label
        gravity = Gravity.CENTER
        setTextColor(Color.WHITE)
        setTextSize(TypedValue.COMPLEX_UNIT_SP, 20f)
        minimumWidth = dp(48)
        minimumHeight = dp(48)
        layoutParams = LinearLayout.LayoutParams(dp(48), dp(48))
        background = GradientDrawable().apply { cornerRadius = dp(12).toFloat() }
        setOnClickListener { onClick() }
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    override fun onResume() {
        super.onResume()
        ExtensionHub.host = this
        if (this::web.isInitialized) web.onResume()
    }

    override fun onPause() {
        if (ExtensionHub.host === this) ExtensionHub.host = null
        CookieManager.getInstance().flush() // giữ phiên đăng nhập Google
        if (this::web.isInitialized) web.onPause()
        super.onPause()
    }

    override fun onDestroy() {
        if (this::web.isInitialized) {
            (web.parent as? ViewGroup)?.removeView(web)
            web.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_URL = "url"
        const val START_URL = "https://aistudio.google.com/app/api-keys?auto_close=1"
    }
}
