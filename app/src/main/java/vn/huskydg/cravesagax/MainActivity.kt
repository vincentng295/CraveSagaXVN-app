package vn.huskydg.cravesagax

import android.content.Intent
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.GradientDrawable
import android.os.Bundle
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ScrollView
import android.widget.TextView
import androidx.appcompat.app.AppCompatActivity
import androidx.core.view.ViewCompat
import androidx.core.view.WindowCompat
import androidx.core.view.WindowInsetsCompat

/** Màn hình đầu tiên: chọn phiên bản game. */
class MainActivity : AppCompatActivity() {

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

        column.addView(
            text("Việt hóa tự động hội thoại • mã nguồn mở, miễn phí", 11f, 0xFF7C819A.toInt()).apply {
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
    }

    override fun onResume() {
        super.onResume()
        ExtensionHub.host = this
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
