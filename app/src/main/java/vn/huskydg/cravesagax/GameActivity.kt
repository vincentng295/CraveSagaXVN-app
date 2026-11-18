package vn.huskydg.cravesagax

import android.annotation.SuppressLint
import android.graphics.Color
import android.graphics.drawable.GradientDrawable
import android.os.Build
import android.os.Bundle
import android.view.Gravity
import android.view.MotionEvent
import android.view.View
import android.view.ViewGroup
import android.view.WindowManager
import android.webkit.WebView
import android.widget.FrameLayout
import android.widget.TextView
import androidx.activity.OnBackPressedCallback
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.GravityCompat
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat
import androidx.core.view.WindowInsetsControllerCompat
import androidx.drawerlayout.widget.DrawerLayout
import kotlin.math.abs

/**
 * Mini browser: WebView trang game ở giữa, menu bên phải (DrawerLayout) chứa popup của extension.
 * Mở menu bằng nút nổi ở mép phải (kéo lên/xuống được) – không dùng vuốt mép để khỏi vướng thao tác trong game.
 */
class GameActivity : AppCompatActivity() {

    private lateinit var game: Game
    private lateinit var drawer: DrawerLayout
    private lateinit var gameView: WebView
    private lateinit var panelView: WebView
    private var panelLoaded = false

    private val chooser = FileChooserHelper(this)

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    @SuppressLint("ClickableViewAccessibility")
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        val selected = Games.byId(intent.getStringExtra(EXTRA_GAME))
        if (selected == null) { finish(); return }
        game = selected

        if (!WebSetup.isSupported()) {
            WebSetup.showUnsupported(this)
            return
        }
        ExtensionHub.init(applicationContext)

        window.addFlags(WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON)
        WindowCompat.setDecorFitsSystemWindows(window, false)
        if (Build.VERSION.SDK_INT >= 28) {
            window.attributes.layoutInDisplayCutoutMode =
                WindowManager.LayoutParams.LAYOUT_IN_DISPLAY_CUTOUT_MODE_SHORT_EDGES
        }

        // ---- WebView game ----
        gameView = WebView(this)
        WebSetup.configure(gameView, hideWebViewUa = true)
        gameView.setBackgroundColor(Color.BLACK)
        gameView.webViewClient = GameClient(game)
        gameView.webChromeClient = AppChromeClient(this, chooser)
        WebSetup.installGame(gameView, this)
        LoginHelper.install(gameView, game)

        // ---- WebView menu phải (popup.html của extension) ----
        panelView = WebView(this)
        WebSetup.configure(panelView, hideWebViewUa = false)
        panelView.setBackgroundColor(0xFFF9F9FB.toInt())
        panelView.webViewClient = PanelClient(this, WebSetup.assetLoader(this))
        panelView.webChromeClient = AppChromeClient(this, chooser)
        WebSetup.installPanel(panelView, this)

        // ---- Nút nổi mở menu ----
        val handle = TextView(this).apply {
            text = "☰"
            setTextColor(Color.WHITE)
            textSize = 18f
            gravity = Gravity.CENTER
            alpha = 0.55f
            background = GradientDrawable().apply {
                setColor(0xAA000000.toInt())
                val r = dp(14).toFloat()
                cornerRadii = floatArrayOf(r, r, 0f, 0f, 0f, 0f, r, r) // bo góc bên trái
            }
        }

        val content = FrameLayout(this)
        content.addView(gameView, FrameLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        content.addView(handle, FrameLayout.LayoutParams(dp(34), dp(56), Gravity.END or Gravity.CENTER_VERTICAL))

        drawer = DrawerLayout(this)
        drawer.addView(content, DrawerLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT))
        val panelWidth = minOf(dp(380), (resources.displayMetrics.widthPixels * 0.9f).toInt())
        drawer.addView(panelView, DrawerLayout.LayoutParams(panelWidth, ViewGroup.LayoutParams.MATCH_PARENT, Gravity.END))
        drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
        drawer.addDrawerListener(object : DrawerLayout.SimpleDrawerListener() {
            override fun onDrawerOpened(drawerView: View) {
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_UNLOCKED)
            }

            override fun onDrawerClosed(drawerView: View) {
                drawer.setDrawerLockMode(DrawerLayout.LOCK_MODE_LOCKED_CLOSED)
                hideSystemBars()
            }
        })
        setContentView(drawer)

        // Bàn phím ảo: đẩy nội dung lên để còn thấy ô nhập email/mật khẩu khi đăng nhập.
        ViewCompat.setOnApplyWindowInsetsListener(drawer) { v, insets ->
            v.setPadding(0, 0, 0, insets.getInsets(WindowInsetsCompat.Type.ime()).bottom)
            insets
        }

        // Chạm = mở menu, kéo dọc = dời vị trí nút.
        handle.setOnTouchListener(object : View.OnTouchListener {
            private var downY = 0f
            private var startTranslation = 0f
            private var moved = false

            override fun onTouch(v: View, e: MotionEvent): Boolean {
                when (e.actionMasked) {
                    MotionEvent.ACTION_DOWN -> {
                        downY = e.rawY
                        startTranslation = v.translationY
                        moved = false
                    }
                    MotionEvent.ACTION_MOVE -> {
                        val dy = e.rawY - downY
                        if (abs(dy) > dp(6)) moved = true
                        if (moved) {
                            val limit = (content.height - v.height) / 2f
                            v.translationY = (startTranslation + dy).coerceIn(-limit, limit)
                        }
                    }
                    MotionEvent.ACTION_UP -> if (!moved) openMenu()
                }
                return true
            }
        })

        onBackPressedDispatcher.addCallback(this, object : OnBackPressedCallback(true) {
            override fun handleOnBackPressed() {
                if (drawer.isDrawerOpen(GravityCompat.END)) drawer.closeDrawer(GravityCompat.END) else confirmExit()
            }
        })

        gameView.loadUrl(game.login?.url ?: game.url)
    }

    private fun openMenu() {
        if (!panelLoaded) {
            panelLoaded = true
            panelView.loadUrl(Injector.EXT_BASE + "popup.html")
        }
        drawer.openDrawer(GravityCompat.END)
    }

    fun reloadGame() {
        if (this::gameView.isInitialized) {
            drawer.closeDrawer(GravityCompat.END)
            gameView.loadUrl(game.url)
        }
    }

    private fun confirmExit() {
        AlertDialog.Builder(this)
            .setMessage("Thoát game và quay về màn hình chọn phiên bản?")
            .setPositiveButton("Thoát") { _, _ -> finish() }
            .setNegativeButton("Ở lại", null)
            .show()
    }

    private fun hideSystemBars() {
        val c = WindowInsetsControllerCompat(window, window.decorView)
        c.hide(WindowInsetsCompat.Type.systemBars())
        c.systemBarsBehavior = WindowInsetsControllerCompat.BEHAVIOR_SHOW_TRANSIENT_BARS_BY_SWIPE
    }

    override fun onWindowFocusChanged(hasFocus: Boolean) {
        super.onWindowFocusChanged(hasFocus)
        if (hasFocus) hideSystemBars()
    }

    override fun onResume() {
        super.onResume()
        ExtensionHub.host = this
        if (this::gameView.isInitialized) {
            gameView.onResume()
            panelView.onResume()
        }
    }

    override fun onPause() {
        if (ExtensionHub.host === this) ExtensionHub.host = null
        android.webkit.CookieManager.getInstance().flush() // lưu phiên đăng nhập
        if (this::gameView.isInitialized) {
            gameView.onPause()
            panelView.onPause()
        }
        super.onPause()
    }

    override fun onDestroy() {
        if (this::gameView.isInitialized) {
            (gameView.parent as? ViewGroup)?.removeView(gameView)
            (panelView.parent as? ViewGroup)?.removeView(panelView)
            gameView.destroy()
            panelView.destroy()
        }
        super.onDestroy()
    }

    companion object {
        const val EXTRA_GAME = "game_id"
    }
}
