package vn.huskydg.cravesagax

import android.content.Context
import android.graphics.Color
import android.graphics.Typeface
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.util.TypedValue
import android.view.Gravity
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import android.widget.ProgressBar
import android.widget.ScrollView
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import java.io.File
import java.util.concurrent.atomic.AtomicBoolean

/** Hộp thoại "Có bản cập nhật mới": nội dung thay đổi + nút Tải xuống → Cài đặt. */
object UpdateDialog {
    @Volatile private var askedFor = 0L // đã hỏi bản này trong phiên chạy hiện tại -> không hỏi lại khi quay về màn hình chính

    private class MaxHeightScroll(ctx: Context, private val maxPx: Int) : ScrollView(ctx) {
        override fun onMeasure(w: Int, h: Int) =
            super.onMeasure(w, MeasureSpec.makeMeasureSpec(maxPx, MeasureSpec.AT_MOST))
    }

    /** Kiểm tra ngầm; chỉ hiện hộp thoại khi có bản mới hơn bản đang chạy. */
    fun check(activity: AppCompatActivity) {
        Thread {
            val info = (try { Updater.fetchLatest() } catch (_: Exception) { null }) ?: return@Thread
            val cur = Updater.current(activity)
            if (info.versionCode <= cur.code || info.versionCode == askedFor) return@Thread
            val log = try { Updater.fetchChangelog(info.changelogUrl) } catch (_: Exception) { null }
            activity.runOnUiThread {
                if (!activity.isFinishing && !activity.isDestroyed) show(activity, info, cur, log)
            }
        }.start()
    }

    private fun show(activity: AppCompatActivity, info: Updater.Info, cur: Updater.Current, changelog: String?) {
        askedFor = info.versionCode
        val d = activity.resources.displayMetrics.density
        fun dp(v: Int) = (v * d + 0.5f).toInt()
        fun tv(s: String, sp: Float, color: Int, bold: Boolean = false) = TextView(activity).apply {
            text = s; setTextSize(TypedValue.COMPLEX_UNIT_SP, sp); setTextColor(color)
            if (bold) typeface = Typeface.DEFAULT_BOLD
        }
        fun rounded(color: Int, r: Int) = GradientDrawable().apply { setColor(color); cornerRadius = dp(r).toFloat() }
        fun button(label: String, bg: Int) = tv(label, 15f, Color.WHITE, true).apply {
            gravity = Gravity.CENTER
            minimumHeight = dp(48)
            setPadding(dp(16), dp(12), dp(16), dp(12))
            background = rounded(bg, 12)
            isClickable = true
        }

        val card = LinearLayout(activity).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(dp(20), dp(20), dp(20), dp(16))
            background = rounded(0xFF1A1F36.toInt(), 18)
        }
        card.addView(tv("🔔 Có bản cập nhật mới", 19f, Color.WHITE, true))
        val sub = buildString {
            append("Phiên bản mới: ").append(info.versionName.ifEmpty { "#${info.versionCode}" })
            if (info.shortCommit.isNotEmpty()) append("  (commit ${info.shortCommit})")
            append("\nĐang dùng: ").append(cur.name)
        }
        card.addView(tv(sub, 13f, 0xFFB4B8CC.toInt()).apply { setPadding(0, dp(6), 0, dp(12)) })

        card.addView(tv("NỘI DUNG CẬP NHẬT", 11f, 0xFF8B93B8.toInt(), true).apply { setPadding(0, 0, 0, dp(6)) })
        val logView = tv(changelog?.takeIf { it.isNotBlank() } ?: "Không có ghi chú cho bản này.", 14f, Color.WHITE).apply {
            setLineSpacing(0f, 1.25f)
            setPadding(dp(12), dp(10), dp(12), dp(10))
            background = rounded(0xFF0F1220.toInt(), 12)
            setTextIsSelectable(true)
        }
        val scroll = MaxHeightScroll(activity, (activity.resources.displayMetrics.heightPixels * 0.38f).toInt()).apply {
            addView(logView, ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        card.addView(scroll, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))

        val status = tv("", 13f, 0xFFB4B8CC.toInt()).apply { setPadding(0, dp(12), 0, 0); visibility = View.GONE }
        val progress = ProgressBar(activity, null, android.R.attr.progressBarStyleHorizontal).apply {
            max = 100; visibility = View.GONE
        }
        card.addView(status)
        card.addView(progress, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(6)).apply { topMargin = dp(6) })

        val later = button("Để sau", 0xFF3A4166.toInt())
        val primary = button("⬇ Tải xuống", 0xFF2563EB.toInt())
        val row = LinearLayout(activity).apply { orientation = LinearLayout.HORIZONTAL }
        row.addView(later, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f).apply { marginEnd = dp(5) })
        row.addView(primary, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.4f).apply { marginStart = dp(5) })
        card.addView(row, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(16) })

        val dialog = AlertDialog.Builder(activity).setView(card).create()
        dialog.window?.setBackgroundDrawable(ColorDrawable(Color.TRANSPARENT))
        val cancelled = AtomicBoolean(false)
        dialog.setOnDismissListener { cancelled.set(true) }
        later.setOnClickListener { dialog.dismiss() }

        var apk: File? = null
        var downloading = false

        fun tryInstall(f: File) {
            val ok = try { Updater.install(activity, f) } catch (e: Exception) {
                Toast.makeText(activity, "Không mở được trình cài đặt: ${e.message}", Toast.LENGTH_LONG).show(); true
            }
            if (!ok) Toast.makeText(activity, "Hãy bật \"Cho phép từ nguồn này\" rồi quay lại bấm Cài đặt", Toast.LENGTH_LONG).show()
        }

        primary.setOnClickListener {
            apk?.let { tryInstall(it); return@setOnClickListener }
            if (downloading) return@setOnClickListener
            downloading = true
            primary.text = "Đang tải…"; primary.alpha = 0.6f
            status.visibility = View.VISIBLE; status.text = "Đang tải… 0%"
            progress.visibility = View.VISIBLE; progress.progress = 0
            Thread {
                try {
                    val f = Updater.download(activity, info, { cancelled.get() }) { pct ->
                        activity.runOnUiThread { progress.progress = pct; status.text = "Đang tải… $pct%" }
                    }
                    activity.runOnUiThread {
                        downloading = false
                        if (cancelled.get()) return@runOnUiThread
                        apk = f
                        progress.visibility = View.GONE
                        status.text = "Đã tải xong. Bấm Cài đặt nếu trình cài đặt chưa hiện."
                        primary.text = "⬆ Cài đặt"; primary.alpha = 1f
                        tryInstall(f)
                    }
                } catch (e: Exception) {
                    activity.runOnUiThread {
                        downloading = false
                        if (cancelled.get()) return@runOnUiThread
                        progress.visibility = View.GONE
                        status.text = "Tải thất bại: ${e.message ?: "lỗi mạng"}"
                        primary.text = "⬇ Thử lại"; primary.alpha = 1f
                    }
                }
            }.start()
        }

        dialog.show()
        dialog.window?.setLayout((activity.resources.displayMetrics.widthPixels * 0.92f).toInt(), ViewGroup.LayoutParams.WRAP_CONTENT)
    }
}
