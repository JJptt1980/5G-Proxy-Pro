package com.tokyoxpa3.androidproxy

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.util.Log
import java.io.File

/**
 * 誰在用：從 native 拿「最近連進來的 IP」，用熱點的 ARP 表換成 MAC 認裝置，
 * 新裝置開始用代理時跳通知；使用者可替每台裝置取名字（以 MAC 為準，讀不到 MAC 才用 IP）。
 */
object ClientTracker {
    private const val TAG = "ClientTracker"
    const val CHANNEL_ID = "client_connected"
    private const val NOTIFY_ID_BASE = 2000
    /** 多久內有新連線算「使用中」 */
    const val ACTIVE_SEC = 60L
    /** 超過這麼久沒動靜就從清單拿掉 */
    const val FORGET_SEC = 30 * 60L
    /** 同一台裝置閒置多久後再連上才重新提醒 */
    private const val REALERT_IDLE_SEC = 10 * 60L
    private const val PREFS = "client_names"

    data class Client(val ip: String, val mac: String?, val ageSec: Long) {
        val key: String get() = mac ?: ip
        val active: Boolean get() = ageSec <= ACTIVE_SEC
    }

    @Volatile var clients: List<Client> = emptyList()
        private set

    /** key → 上一次看到「使用中」的時間（epoch 秒）；用來判斷是不是「新連上」 */
    private val lastActive = HashMap<String, Long>()
    private var primed = false

    fun parse(raw: String, arp: Map<String, String>): List<Client> =
        raw.split(';').mapNotNull { part ->
            val f = part.split(',')
            if (f.size != 2) return@mapNotNull null
            val age = f[1].toLongOrNull() ?: return@mapNotNull null
            Client(f[0], arp[f[0]], age)
        }.filter { it.ageSec <= FORGET_SEC }.sortedBy { it.ageSec }

    /** /proc/net/arp：IP → MAC（只取有效項目）。讀不到就回空表，改用 IP 認裝置。 */
    fun readArp(): Map<String, String> = try {
        File("/proc/net/arp").readLines().drop(1).mapNotNull { line ->
            val c = line.trim().split(Regex("\\s+"))
            if (c.size >= 4 && c[3] != "00:00:00:00:00:00") c[0] to c[3].lowercase() else null
        }.toMap()
    } catch (e: Exception) { emptyMap() }

    /** 服務每幾秒呼叫一次。 */
    fun tick(ctx: Context) {
        val raw = try { NativeEngine.getClients() } catch (e: Throwable) { return }
        val list = parse(raw, readArp())
        clients = list
        val now = System.currentTimeMillis() / 1000
        for (c in list) {
            if (!c.active) continue
            val prev = lastActive[c.key]
            lastActive[c.key] = now - c.ageSec
            // 第一次 tick 只建立基準，不對「服務一開就已經在用」的裝置狂跳通知
            if (primed && (prev == null || (now - c.ageSec) - prev > REALERT_IDLE_SEC)) {
                notifyConnected(ctx, c)
            }
        }
        primed = true
    }

    fun reset() {
        lastActive.clear(); primed = false; clients = emptyList()
    }

    fun nameOf(ctx: Context, c: Client): String? =
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).getString(c.key, null)?.takeIf { it.isNotBlank() }

    fun setName(ctx: Context, c: Client, name: String) {
        ctx.getSharedPreferences(PREFS, Context.MODE_PRIVATE).edit().apply {
            if (name.isBlank()) remove(c.key) else putString(c.key, name.trim())
        }.apply()
    }

    fun displayName(ctx: Context, c: Client): String = nameOf(ctx, c) ?: c.ip

    private fun notifyConnected(ctx: Context, c: Client) {
        try {
            val nm = ctx.getSystemService(NotificationManager::class.java)
            if (nm.getNotificationChannel(CHANNEL_ID) == null) {
                nm.createNotificationChannel(NotificationChannel(
                    CHANNEL_ID, ctx.getString(R.string.channel_client_connected),
                    NotificationManager.IMPORTANCE_HIGH))
            }
            val name = nameOf(ctx, c)
            val text = if (name != null) "$name（${c.ip}）" else ctx.getString(R.string.client_unnamed_fmt, c.ip)
            val pi = PendingIntent.getActivity(ctx, 1, Intent(ctx, DebugActivity::class.java),
                PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE)
            val n = Notification.Builder(ctx, CHANNEL_ID)
                .setSmallIcon(android.R.drawable.ic_dialog_info)
                .setContentTitle(ctx.getString(R.string.notify_client_connected))
                .setContentText(text)
                .setContentIntent(pi)
                .setAutoCancel(true)
                .build()
            nm.notify(NOTIFY_ID_BASE + (c.key.hashCode() and 0xff), n)
            Log.i(TAG, "client connected: ${c.ip} mac=${c.mac}")
        } catch (e: Exception) { Log.w(TAG, "notify failed", e) }
    }
}
