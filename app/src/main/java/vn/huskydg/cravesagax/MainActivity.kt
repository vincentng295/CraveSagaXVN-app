package vn.huskydg.cravesagax

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.net.Uri
import android.os.Bundle
import android.os.PowerManager
import android.provider.Settings
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Màn hình đầu tiên: chọn phiên bản game. */
class MainActivity : AppCompatActivity() {

    private companion object {
        const val WARP_LITE_URL = "https://vincentng295.github.io/WARPLite-Android/"
    }

    private lateinit var batteryButton: TextView

    private fun dp(v: Int) = (v * resources.displayMetrics.density + 0.5f).toInt()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        ExtensionHub.init(applicationContext) // nạp storage/từ điển ngay từ đầu cho đỡ chờ khi vào game
        WindowCompat.setDecorFitsSystemWindows(window, false)

        val column = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            gravity = Gravity.CENTER_HORIZONTAL
            setPadding(dp(20), dp(28), dp(20), dp(24))
        }

        column.addView(text("Crave Saga X VN", 26f, Color.WHITE, bold = true).apply { gravity = Gravity.CENTER })
        column.addView(
            text("Chọn phiên bản game để chơi", 14f, 0xFFB4B8CC.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(4), 0, dp(20))
            }
        )

        for (game in Games.all) column.addView(gameCard(game), cardParams())

        val optionsButton = text("📝 Từ điển / Cài đặt dịch", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(0xFF2563EB.toInt(), 10)
            setOnClickListener { startActivity(Intent(this@MainActivity, OptionsActivity::class.java)) }
        }
        column.addView(optionsButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(14) })

        batteryButton = text("", 14f, Color.WHITE).apply {
            gravity = Gravity.CENTER
            setPadding(dp(16), dp(12), dp(16), dp(12))
            setOnClickListener { requestIgnoreBatteryOptimizations() }
        }
        column.addView(batteryButton, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(10) })
        column.addView(
            text("Nên bật nếu dịch từ điển bằng Gemma lâu: giúp Android không ngắt việc dịch chạy nền khi bạn thoát hoặc đóng app.", 11f, 0xFF7C819A.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(dp(8), dp(6), dp(8), 0)
            }
        )
        refreshBatteryButton()

        // Quảng bá WARP Lite: tăng tốc truy cập web khi mạng lag
        val promo = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(16), dp(14), dp(16), dp(14))
            background = rounded(0xFF3A2414.toInt(), 12).apply { setStroke(dp(1), 0xFFFF8A3D.toInt()) }
            isClickable = true
            isFocusable = true
            setOnClickListener {
                try {
                    startActivity(Intent(Intent.ACTION_VIEW, Uri.parse(WARP_LITE_URL)))
                } catch (e: Exception) {
                    Toast.makeText(this@MainActivity, "Không mở được trình duyệt", Toast.LENGTH_SHORT).show()
                }
            }
            addView(text("🚀 Mạng lag? Tải app WARP Lite", 14f, 0xFFFF8A3D.toInt(), bold = true))
            addView(text("Tải app WARP Lite để tăng tốc độ truy cập web nếu mạng lag. Miễn phí, không cần tài khoản — chạm để tải APK.", 12f, 0xFFE5C9B0.toInt()).apply {
                setPadding(0, dp(4), 0, 0)
            })
        }
        column.addView(promo, LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        ).apply { topMargin = dp(16) })

        column.addView(
            text("Việt hóa tự động hội thoại • mã nguồn mở, miễn phí • v${Updater.current(this).name}", 11f, 0xFF7C819A.toInt()).apply {
                gravity = Gravity.CENTER
                setPadding(0, dp(18), 0, 0)
            }
        )

        val scroll = ScrollView(this).apply {
            setBackgroundColor(0xFF0F1220.toInt())
            isFillViewport = true
            addView(column, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }
        setContentView(scroll)

        ViewCompat.setOnApplyWindowInsetsListener(scroll) { v, insets ->
            val bars = insets.getInsets(WindowInsetsCompat.Type.systemBars() or WindowInsetsCompat.Type.displayCutout())
            v.setPadding(bars.left, bars.top, bars.right, bars.bottom)
            insets
        }

        UpdateDialog.check(this) // có bản mới trên GitHub Release thì hiện nội dung cập nhật + nút tải
    }

    override fun onResume() {
        super.onResume()
        ExtensionHub.host = this
        refreshBatteryButton() // người dùng có thể vừa cấp/thu hồi quyền ở màn hình hệ thống
    }

    private fun isIgnoringBatteryOptimizations(): Boolean =
        getSystemService(PowerManager::class.java)?.isIgnoringBatteryOptimizations(packageName) == true

    private fun refreshBatteryButton() {
        val ok = isIgnoringBatteryOptimizations()
        batteryButton.text = if (ok) "✅ Đã bỏ qua tối ưu hóa pin" else "🔋 Bỏ qua tối ưu hóa pin (chạy nền ổn định)"
        batteryButton.background = rounded(if (ok) 0xFF166534.toInt() else 0xFF374151.toInt(), 10)
    }

    /** Hiện hộp thoại hệ thống xin loại app khỏi danh sách tối ưu hóa pin; máy không hỗ trợ thì mở trang cài đặt chung. */
    private fun requestIgnoreBatteryOptimizations() {
        if (isIgnoringBatteryOptimizations()) {
            Toast.makeText(this, "App đã được bỏ qua tối ưu hóa pin", Toast.LENGTH_SHORT).show()
            return
        }
        try {
            startActivity(
                Intent(Settings.ACTION_REQUEST_IGNORE_BATTERY_OPTIMIZATIONS, Uri.parse("package:$packageName"))
            )
        } catch (e: Exception) {
            try {
                startActivity(Intent(Settings.ACTION_IGNORE_BATTERY_OPTIMIZATION_SETTINGS))
            } catch (e2: Exception) {
                Toast.makeText(this, "Máy không mở được cài đặt tối ưu hóa pin", Toast.LENGTH_LONG).show()
            }
        }
    }

    override fun onPause() {
        if (ExtensionHub.host === this) ExtensionHub.host = null
        super.onPause()
    }

    private fun cardParams() = LinearLayout.LayoutParams(
        ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
    ).apply { bottomMargin = dp(12) }

    private fun gameCard(game: Game): View {
        val card = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            background = rounded(0xFF1A1F36.toInt(), 14)
            isClickable = true
            isFocusable = true
            setOnClickListener {
                startActivity(Intent(this@MainActivity, GameActivity::class.java).putExtra(GameActivity.EXTRA_GAME, game.id))
            }
        }
        val accent = View(this).apply { background = rounded(game.accent, 4) }
        card.addView(accent, LinearLayout.LayoutParams(dp(6), dp(54)).apply { marginStart = dp(14); marginEnd = dp(14) })

        val texts = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        texts.addView(text(game.title, 18f, Color.WHITE, bold = true))
        texts.addView(text(game.subtitle, 12f, 0xFFB4B8CC.toInt()).apply { setPadding(0, dp(3), 0, 0) })
        card.addView(texts, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply {
            topMargin = dp(16); bottomMargin = dp(16)
        })

        card.addView(text("▶", 18f, game.accent).apply { setPadding(dp(8), 0, dp(18), 0) })
        return card
    }

    private fun text(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(this).apply {
        text = s
        setTextSize(TypedValue.COMPLEX_UNIT_SP, sp)
        setTextColor(color)
        if (bold) typeface = Typeface.DEFAULT_BOLD
    }

    private fun rounded(color: Int, radiusDp: Int) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
    }
}
