package vn.huskydg.cravesagax

import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent

/** Nhận bấm nút trên thông báo lỗi Gemma (retry / auto / stop) và chuyển về trang game đang chờ. */
class GemmaActionReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val action = intent.getStringExtra(EXTRA_ACTION) ?: return
        ExtensionHub.sendToContent("{\"type\":\"GEMMA_USER_ACTION\",\"action\":\"$action\"}")
    }

    companion object {
        const val EXTRA_ACTION = "action" // retry | auto | stop
    }
}
