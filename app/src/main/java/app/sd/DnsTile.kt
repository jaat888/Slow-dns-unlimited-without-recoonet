package app.sd

import android.content.Intent
import android.net.VpnService
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService

/** "DNS" button in the quick settings shade: connect / disconnect the VPN and show whether it is on. */
class DnsTile : TileService() {
    private val h = Handler(Looper.getMainLooper())
    private val tick = object : Runnable {
        override fun run() { paint(); h.postDelayed(this, 1000) }
    }

    override fun onStartListening() { super.onStartListening(); h.removeCallbacks(tick); tick.run() }
    override fun onStopListening() { h.removeCallbacks(tick); super.onStopListening() }

    private fun paint() {
        val t = qsTile ?: return
        val on = VpnSvc.running
        val conn = if (on) VpnSvc.connected() else 0
        t.label = "DNS"
        t.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        if (Build.VERSION.SDK_INT >= 29) t.subtitle = if (!on) "Off" else if (conn > 0) "On" else "Connecting"
        try { t.updateTile() } catch (e: Throwable) { }
    }

    override fun onClick() {
        super.onClick()
        if (VpnSvc.running) {
            try { startService(Intent(this, VpnSvc::class.java).setAction("stop")) } catch (e: Throwable) { }
        } else if (VpnService.prepare(this) != null) {
            // the VPN permission has never been given: open the app once, it asks for it and connects
            openApp()
        } else {
            try { startForegroundService(Intent(this, VpnSvc::class.java)) } catch (e: Throwable) { openApp() }
        }
        paint()
    }

    private fun openApp() {
        val i = Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP).putExtra("tile_start", true)
        if (Build.VERSION.SDK_INT >= 34) {
            val pi = android.app.PendingIntent.getActivity(this, 7, i, android.app.PendingIntent.FLAG_UPDATE_CURRENT or android.app.PendingIntent.FLAG_IMMUTABLE)
            startActivityAndCollapse(pi)
        } else {
            @Suppress("DEPRECATION") startActivityAndCollapse(i)
        }
    }
}
