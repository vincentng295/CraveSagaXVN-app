package vn.huskydg.cravesagax

import android.os.Bundle
import android.view.ViewGroup
import android.webkit.WebView
import android.widget.FrameLayout
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Trang Options của extension (quản lý từ điển, công cụ dịch, export/import JSON). */
class OptionsActivity : AppCompatActivity() {

    private lateinit var webView: WebView
    private val chooser = FileChooserHelper(this)

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        if (!WebSetup.isSupported()) {
            WebSetup.showUnsupported(this)
            return
        }
        ExtensionHub.init(applicationContext)
        WindowCompat.setDecorFitsSystemWindows(window, false)

        webView = WebView(this)
        WebSetup.configure(webView, hideWebViewUa = false)
        webView.setBackgroundColor(0xFF0F1220.toInt())
        webView.webViewClient = PanelClient(this, WebSetup.assetLoader(this))
        webView.webChromeClient = AppChromeClient(this, chooser)
        WebSetup.installPanel(webView, this)

        val root = FrameLayout(this)
        root.setBackgroundColor(0xFF0F1220.toInt())
        root.addView(webView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        setContentView(root)
        ViewCompat.setOnApplyWindowInsetsListener(root) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() = finish()
        })

        webView.loadUrl(Injector.EXT_BASE + "options.html")
    }

    override fun onResume() {
        super.onResume()
        ExtensionHub.host = this
    }

    override fun onPause() {
        if (ExtensionHub.host === this) ExtensionHub.host = null
        super.onPause()
    }

    override fun onDestroy() {
        if (this::webView.isInitialized) {
            (webView.parent as? ViewGroup)?.removeView(webView)
            webView.destroy()
        }
        super.onDestroy()
    }
}
