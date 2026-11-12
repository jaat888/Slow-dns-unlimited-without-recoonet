package app.sd

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent

/** Notification channels and small helpers shared by every part of the app (any process). */
object Notif {
    const val CH_VPN = "v"
    const val CH_WORK = "vj_work"
    const val CH_ALERT = "vj_alert"

    fun channels(c: Context) {
        val nm = c.getSystemService(NotificationManager::class.java) ?: return
        nm.createNotificationChannel(NotificationChannel(CH_VPN, "VPN connection", NotificationManager.IMPORTANCE_LOW))
        nm.createNotificationChannel(NotificationChannel(CH_WORK, "Account creation progress", NotificationManager.IMPORTANCE_LOW))
        val alert = NotificationChannel(CH_ALERT, "Captcha and account alerts", NotificationManager.IMPORTANCE_HIGH)
        alert.enableVibration(true)
        nm.createNotificationChannel(alert)
    }

    /** Tapping the notification opens the app. */
    fun open(c: Context): PendingIntent {
        val i = Intent(c, MainActivity::class.java)
            .addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP or Intent.FLAG_ACTIVITY_SINGLE_TOP)
        return PendingIntent.getActivity(c, 90, i, PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
    }

    /** A heads-up notification (captcha needed, account ready, error ...). */
    fun alert(c: Context, id: Int, title: String, text: String) {
        try {
            channels(c)
            val n = Notification.Builder(c, CH_ALERT)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(title)
                .setContentText(text)
                .setStyle(Notification.BigTextStyle().bigText(text))
                .setContentIntent(open(c))
                .setAutoCancel(true)
                .build()
            c.getSystemService(NotificationManager::class.java)?.notify(id, n)
        } catch (e: Throwable) { }
    }

    fun cancel(c: Context, id: Int) {
        try { c.getSystemService(NotificationManager::class.java)?.cancel(id) } catch (e: Throwable) { }
    }
}
