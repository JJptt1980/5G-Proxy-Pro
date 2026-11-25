package com.tokyoxpa3.androidproxy

import android.app.Activity
import android.app.PendingIntent
import android.content.BroadcastReceiver
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.net.Uri
import android.os.Build
import android.provider.Settings
import android.view.Gravity
import android.widget.TextView
import org.json.JSONObject
import java.io.File
import java.net.HttpURLConnection
import java.net.URL
import java.security.MessageDigest

/**
 * App 內更新（照 Weather One 的做法）：讀 GitHub 固定 release「feed」裡的 update.json，
 * 有新版就下載 APK、核對 SHA-256，交給系統安裝畫面。只有 debug（GW 實機）版會出現。
 * 發版用 tools/release.sh。
 */
object SelfUpdate {
    const val REPO = "JJptt1980/5G-Proxy-Pro"
    const val APK_NAME = "5G-Proxy-Pro-GW.apk"

    data class Manifest(
        val versionCode: Long,
        val versionName: String,
        val apkUrl: String,
        val sha256: String,
        val notes: String,
    )

    fun manifestUrl(repo: String = REPO) = "https://github.com/$repo/releases/download/feed/update.json"

    /** 解析 update.json；格式不對回 null。 */
    fun parse(json: String): Manifest? = runCatching {
        val o = JSONObject(json)
        Manifest(
            versionCode = o.getLong("versionCode"),
            versionName = o.optString("versionName"),
            apkUrl = o.getString("apkUrl"),
            sha256 = o.getString("sha256").lowercase(),
            notes = o.optString("notes"),
        )
    }.getOrNull()

    /** 這份 manifest 能不能用：APK 一定要從這個 repo 的 release 下載、檢查碼要是 64 碼 hex。 */
    fun isTrusted(m: Manifest, repo: String = REPO): Boolean =
        m.apkUrl.startsWith("https://github.com/$repo/releases/download/") &&
            m.sha256.matches(Regex("[0-9a-f]{64}"))

    fun isNewer(m: Manifest, installedCode: Long): Boolean = m.versionCode > installedCode

    fun sha256Hex(bytes: ByteArray): String = bytes.joinToString("") { "%02x".format(it) }

    fun percent(read: Long, total: Long): Int? =
        if (total <= 0) null else ((read * 100) / total).toInt().coerceIn(0, 100)

    fun enabledFor(appFlags: Int) = (appFlags and android.content.pm.ApplicationInfo.FLAG_DEBUGGABLE) != 0
}

/** 首頁最下面那一行：「目前版本 X · 檢查更新」，按下去依狀態檢查／下載／安裝。 */
class SelfUpdater(
    private val activity: Activity,
    private val repo: String = SelfUpdate.REPO,
    private val apkName: String = SelfUpdate.APK_NAME,
) {
    private sealed interface State {
        data object Idle : State
        data object Checking : State
        data object UpToDate : State
        data class Available(val m: SelfUpdate.Manifest) : State
        data class Downloading(val m: SelfUpdate.Manifest, val percent: Int?) : State
        data class NeedPermission(val m: SelfUpdate.Manifest) : State
        data class Installing(val m: SelfUpdate.Manifest) : State
        data class Failed(val m: SelfUpdate.Manifest?, val reason: String) : State
    }

    private var state: State = State.Idle
    private var apk: File? = null
    private lateinit var tv: TextView

    private val installedName: String = runCatching {
        activity.packageManager.getPackageInfo(activity.packageName, 0).versionName ?: "?"
    }.getOrDefault("?")

    private val installedCode: Long = runCatching {
        val info = activity.packageManager.getPackageInfo(activity.packageName, 0)
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.P) info.longVersionCode
        else @Suppress("DEPRECATION") info.versionCode.toLong()
    }.getOrDefault(0L)

    fun view(textColor: Int, accent: Int, minHeightPx: Int): TextView {
        tv = TextView(activity).apply {
            textSize = 13f
            gravity = Gravity.CENTER
            minHeight = minHeightPx
            setTextColor(textColor)
            setOnClickListener { onTap() }
        }
        this.accent = accent
        this.normal = textColor
        render()
        return tv
    }

    private var accent = 0
    private var normal = 0

    /** 從系統設定（允許安裝）回來時呼叫：剛允許就直接接著安裝。 */
    fun onResume() {
        val s = state
        if (s is State.NeedPermission && activity.packageManager.canRequestPackageInstalls()) install(s.m)
    }

    private fun onTap() {
        when (val s = state) {
            State.Idle, State.UpToDate, is State.Failed -> {
                val m = (s as? State.Failed)?.m
                if (m != null && apk?.exists() == true) install(m) else if (m != null) download(m) else check()
            }
            is State.Available -> download(s.m)
            is State.NeedPermission -> install(s.m)
            is State.Installing -> install(s.m)
            State.Checking, is State.Downloading -> Unit
        }
    }

    private fun set(s: State) = activity.runOnUiThread { state = s; render() }

    private fun render() {
        if (!::tv.isInitialized) return
        val head = "目前版本 $installedName"
        val (text, link) = when (val s = state) {
            State.Idle -> "$head · 檢查更新" to true
            State.Checking -> "$head · 檢查中…" to false
            State.UpToDate -> "$head · 已是最新（再按一次重新檢查）" to true
            is State.Available -> "有新版 ${s.m.versionName} · 下載更新" to true
            is State.Downloading -> "下載 ${s.m.versionName} " + (s.percent?.let { "$it%" } ?: "中…") to false
            is State.NeedPermission -> "請允許「安裝不明 App」後回來，或再按一次" to true
            is State.Installing -> "已開啟安裝畫面 · 沒看到就再按一次" to true
            is State.Failed -> "${s.reason} · 再試一次" to true
        }
        tv.text = text
        tv.setTextColor(if (link) accent else normal)
    }

    private fun check() {
        set(State.Checking)
        Thread {
            try {
                val body = get(SelfUpdate.manifestUrl(repo)).use { it.readBytes().toString(Charsets.UTF_8) }
                val m = SelfUpdate.parse(body)
                when {
                    m == null || !SelfUpdate.isTrusted(m, repo) -> set(State.Failed(null, "更新資訊格式不對"))
                    SelfUpdate.isNewer(m, installedCode) -> set(State.Available(m))
                    else -> set(State.UpToDate)
                }
            } catch (e: Exception) {
                android.util.Log.i("SelfUpdate", "SelfUpdate: check failed: $e")
                set(State.Failed(null, "查不到更新（網路？）"))
            }
        }.start()
    }

    private fun download(m: SelfUpdate.Manifest) {
        set(State.Downloading(m, 0))
        Thread {
            try {
                val dir = File(activity.cacheDir, "updates").apply { mkdirs() }
                dir.listFiles()?.forEach { it.delete() }
                val target = File(dir, apkName)
                val digest = MessageDigest.getInstance("SHA-256")
                val conn = open(m.apkUrl)
                val total = conn.contentLengthLong
                var read = 0L
                var last: Int? = -1
                conn.inputStream.use { input ->
                    target.outputStream().use { out ->
                        val buf = ByteArray(64 * 1024)
                        while (true) {
                            val n = input.read(buf)
                            if (n < 0) break
                            out.write(buf, 0, n)
                            digest.update(buf, 0, n)
                            read += n
                            val p = SelfUpdate.percent(read, total)
                            if (p != last) { last = p; set(State.Downloading(m, p)) }
                        }
                    }
                }
                if (SelfUpdate.sha256Hex(digest.digest()) != m.sha256) {
                    target.delete()
                    set(State.Failed(m, "下載的檔案檢查碼不符"))
                    return@Thread
                }
                apk = target
                activity.runOnUiThread { install(m) }
            } catch (e: Exception) {
                android.util.Log.i("SelfUpdate", "SelfUpdate: download failed: $e")
                set(State.Failed(m, "下載失敗"))
            }
        }.start()
    }

    private fun install(m: SelfUpdate.Manifest) {
        val file = apk
        if (file == null || !file.exists()) { download(m); return }
        if (!activity.packageManager.canRequestPackageInstalls()) {
            set(State.NeedPermission(m))
            runCatching {
                activity.startActivity(
                    Intent(Settings.ACTION_MANAGE_UNKNOWN_APP_SOURCES, Uri.parse("package:${activity.packageName}"))
                )
            }
            return
        }
        set(State.Installing(m))
        Thread {
            try {
                val installer = activity.packageManager.packageInstaller
                val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
                    setAppPackageName(activity.packageName)
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                        setRequireUserAction(PackageInstaller.SessionParams.USER_ACTION_NOT_REQUIRED)
                    }
                }
                val id = installer.createSession(params)
                installer.openSession(id).use { session ->
                    file.inputStream().use { input ->
                        session.openWrite("base.apk", 0, file.length()).use { out ->
                            input.copyTo(out)
                            session.fsync(out)
                        }
                    }
                    val intent = Intent(activity, SelfUpdateReceiver::class.java).setPackage(activity.packageName)
                    val flags = PendingIntent.FLAG_UPDATE_CURRENT or
                        (if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) PendingIntent.FLAG_MUTABLE else 0)
                    session.commit(PendingIntent.getBroadcast(activity, id, intent, flags).intentSender)
                }
            } catch (e: Exception) {
                android.util.Log.i("SelfUpdate", "SelfUpdate: install failed: $e")
                set(State.Failed(m, "安裝失敗"))
            }
        }.start()
    }

    private fun open(url: String): HttpURLConnection =
        (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 15_000
            readTimeout = 30_000
            instanceFollowRedirects = true
            setRequestProperty("Cache-Control", "no-cache")
            if (responseCode !in 200..299) throw java.io.IOException("HTTP $responseCode")
        }

    private fun get(url: String) = open(url).inputStream
}

/** 收安裝結果；系統要使用者確認時把安裝畫面叫出來。 */
class SelfUpdateReceiver : BroadcastReceiver() {
    override fun onReceive(context: Context, intent: Intent) {
        val status = intent.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        android.util.Log.i("SelfUpdate", "SelfUpdate: install status=$status ${intent.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE) ?: ""}")
        if (status == PackageInstaller.STATUS_PENDING_USER_ACTION) {
            val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
                intent.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
            } else {
                @Suppress("DEPRECATION")
                intent.getParcelableExtra(Intent.EXTRA_INTENT)
            }
            confirm?.let { context.startActivity(it.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)) }
        }
    }
}
