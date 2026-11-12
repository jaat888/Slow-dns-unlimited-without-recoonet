package app.sd

import android.app.PendingIntent
import android.appwidget.AppWidgetManager
import android.appwidget.AppWidgetProvider
import android.content.ComponentName
import android.content.Context
import android.content.Intent
import android.net.VpnService
import android.widget.RemoteViews

/** Home-screen button "DNS": tap to connect / disconnect, shows On / Off / Connecting. */
class DnsWidget : AppWidgetProvider() {
    override fun onUpdate(c: Context, m: AppWidgetManager, ids: IntArray) { paint(c, m, ids) }

    override fun onReceive(c: Context, i: Intent?) {
        if (i?.action == ACT_TOGGLE) {
            try {
                if (VpnSvc.running) c.startService(Intent(c, VpnSvc::class.java).setAction("stop"))
                else c.startForegroundService(Intent(c, VpnSvc::class.java))
            } catch (e: Throwable) { }
            // the state changes a moment later: VpnSvc refreshes the widget itself
            refresh(c)
            return
        }
        super.onReceive(c, i)
    }

    companion object {
        const val ACT_TOGGLE = "app.sd.WIDGET_TOGGLE"

        fun refresh(c: Context) {
            try {
                val m = AppWidgetManager.getInstance(c)
                val ids = m.getAppWidgetIds(ComponentName(c, DnsWidget::class.java))
                if (ids.isNotEmpty()) paint(c, m, ids)
            } catch (e: Throwable) { }
        }

        private fun paint(c: Context, m: AppWidgetManager, ids: IntArray) {
            val on = VpnSvc.running
            val conn = if (on) VpnSvc.connected() else 0
            val rv = RemoteViews(c.packageName, R.layout.widget_dns)
            rv.setTextViewText(R.id.w_state, if (!on) "Off" else if (conn > 0) "On" else "Connecting")
            rv.setInt(R.id.w_root, "setBackgroundResource", if (on) R.drawable.w_on else R.drawable.w_off)
            val flags = PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
            val pi = if (!on && VpnService.prepare(c) != null) {
                // the VPN permission was never given: the app opens once, asks for it and connects
                PendingIntent.getActivity(c, 8, Intent(c, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tile_start", true), flags)
            } else {
                PendingIntent.getBroadcast(c, 9, Intent(c, DnsWidget::class.java).setAction(ACT_TOGGLE), flags)
            }
            rv.setOnClickPendingIntent(R.id.w_root, pi)
            for (id in ids) m.updateAppWidget(id, rv)
        }
    }
}
