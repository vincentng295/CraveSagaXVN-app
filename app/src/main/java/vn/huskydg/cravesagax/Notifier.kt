package vn.huskydg.cravesagax

import android.Manifest
import android.app.Activity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.widget.Toast
import androidx.core.app.ActivityCompat
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import androidx.core.content.ContextCompat

/**
 * Thông báo hệ thống của app cho việc dịch bằng Gemma:
 *  - "progress": Đang dịch... (thanh chạy, không vuốt xóa được, tự gỡ sau 10 phút nếu quên clear)
 *  - "done"    : Dịch hoàn tất
 *  - "error"   : Lỗi dịch (tự biến mất sau ~10 giây)
 *  - "clear"   : gỡ thông báo "đang dịch"
 *  - "queue"   : hàng đợi dịch nền Gemma (đang chờ thử lại / đang dịch lại), "queue_clear" để gỡ
 * Chưa được cấp quyền thông báo (Android 13+) thì tạm dùng Toast để không mất tin.
 */
object Notifier {
    private const val CH_PROGRESS = "translate_progress"
    private const val CH_RESULT = "translate_result"
    private const val ID_PROGRESS = 2001
    private const val ID_RESULT = 2002
    private const val ID_QUEUE = 2003
    private const val REQ_PERMISSION = 7001

    private val main = Handler(Looper.getMainLooper())

    fun requestPermissionIfNeeded(activity: Activity) {
        if (Build.VERSION.SDK_INT < 33) return
        if (ContextCompat.checkSelfPermission(activity, Manifest.permission.POST_NOTIFICATIONS) ==
            PackageManager.PERMISSION_GRANTED) return
        ActivityCompat.requestPermissions(activity, arrayOf(Manifest.permission.POST_NOTIFICATIONS), REQ_PERMISSION)
    }

    private fun canNotify(ctx: Context): Boolean {
        if (Build.VERSION.SDK_INT >= 33 &&
            ContextCompat.checkSelfPermission(ctx, Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) return false
        return NotificationManagerCompat.from(ctx).areNotificationsEnabled()
    }

    private fun ensureChannels(ctx: Context) {
        if (Build.VERSION.SDK_INT < 26) return
        val nm = ctx.getSystemService(NotificationManager::class.java)
        nm.createNotificationChannel(
            NotificationChannel(CH_PROGRESS, "Đang dịch", NotificationManager.IMPORTANCE_LOW)
                .apply { setShowBadge(false) }
        )
        nm.createNotificationChannel(
            NotificationChannel(CH_RESULT, "Kết quả dịch / lỗi dịch", NotificationManager.IMPORTANCE_HIGH)
        )
    }

    private fun openGameIntent(ctx: Context): PendingIntent {
        val i = Intent(ctx, GameActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_REORDER_TO_FRONT or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(ctx, 0, i, PendingIntent.FLAG_IMMUTABLE or PendingIntent.FLAG_UPDATE_CURRENT)
    }

    fun show(ctx: Context, kind: String, text: String) {
        main.post {
            val nm = NotificationManagerCompat.from(ctx)
            if (kind == "clear") {
                nm.cancel(ID_PROGRESS)
                return@post
            }
            if (kind == "queue_clear") {
                nm.cancel(ID_QUEUE)
                return@post
            }
            if (text.isBlank()) return@post

            if (!canNotify(ctx)) {
                if (kind == "queue") return@post // hàng đợi nền cập nhật liên tục, không dùng Toast để khỏi spam
                // "progress" chỉ nhắc ngắn, tránh spam Toast mỗi lần cập nhật.
                Toast.makeText(ctx, text, if (kind == "error") Toast.LENGTH_LONG else Toast.LENGTH_SHORT).show()
                return@post
            }
            ensureChannels(ctx)

            when (kind) {
                "progress" -> {
                    val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("Crave Saga X VN")
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setProgress(0, 0, true)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentIntent(openGameIntent(ctx))
                        .setTimeoutAfter(10 * 60 * 1000L) // phòng khi trang bị đóng mà chưa kịp "clear"
                        .build()
                    try { nm.notify(ID_PROGRESS, n) } catch (_: SecurityException) {}
                }
                "queue" -> {
                    val n = NotificationCompat.Builder(ctx, CH_PROGRESS)
                        .setSmallIcon(android.R.drawable.stat_notify_sync)
                        .setContentTitle("Dịch nền Gemma")
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setProgress(0, 0, true)
                        .setOngoing(true)
                        .setOnlyAlertOnce(true)
                        .setPriority(NotificationCompat.PRIORITY_LOW)
                        .setCategory(NotificationCompat.CATEGORY_PROGRESS)
                        .setContentIntent(openGameIntent(ctx))
                        .setTimeoutAfter(5 * 60 * 1000L) // mỗi lần cập nhật được đặt lại; tối đa chờ 1p30s giữa 2 lần
                        .build()
                    try { nm.notify(ID_QUEUE, n) } catch (_: SecurityException) {}
                }
                "done", "error" -> {
                    val isError = kind == "error"
                    nm.cancel(ID_PROGRESS)
                    val n = NotificationCompat.Builder(ctx, CH_RESULT)
                        .setSmallIcon(if (isError) android.R.drawable.stat_notify_error else android.R.drawable.stat_sys_download_done)
                        .setContentTitle(if (isError) "Lỗi dịch" else "Dịch hoàn tất")
                        .setContentText(text)
                        .setStyle(NotificationCompat.BigTextStyle().bigText(text))
                        .setAutoCancel(true)
                        .setPriority(NotificationCompat.PRIORITY_HIGH)
                        .setCategory(if (isError) NotificationCompat.CATEGORY_ERROR else NotificationCompat.CATEGORY_STATUS)
                        .setContentIntent(openGameIntent(ctx))
                        .setTimeoutAfter(10_000L)
                        .build()
                    try { nm.notify(ID_RESULT, n) } catch (_: SecurityException) {}
                }
            }
        }
    }
}
