package com.tokyoxpa3.androidproxy

import android.os.Bundle
import android.os.SystemClock
import android.util.Log
import android.widget.*
import android.app.Activity
import android.content.pm.PackageManager
import android.net.Network
import android.net.ConnectivityManager
import android.net.wifi.WifiManager
import android.view.View
import android.view.ViewGroup
import android.widget.LinearLayout
import kotlinx.coroutines.*
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import android.content.Intent
import android.content.Context
import android.content.ClipData
import android.content.ClipboardManager
import android.graphics.Color
import android.graphics.drawable.ColorDrawable
import android.graphics.drawable.GradientDrawable
import android.view.Gravity
import android.content.res.ColorStateList
import android.graphics.Typeface
import com.tokyoxpa3.androidproxy.network.CellularNetworkManager
import com.tokyoxpa3.androidproxy.network.HotspotManager
import com.tokyoxpa3.androidproxy.network.PublicIPChecker

class DebugActivity : Activity() {
    
    private lateinit var statusText: TextView
    private lateinit var statusTitle: TextView
    private lateinit var statusDot: View
    private lateinit var wifiIPText: TextView
    private lateinit var hotspotIPText: TextView
    private lateinit var usbTetherIPText: TextView
    private lateinit var cellularIPText: TextView
    private lateinit var portInput: EditText
    private lateinit var authUserInput: EditText
    private lateinit var authPassInput: EditText
    private lateinit var mainButton: Button
    private lateinit var selfTestButton: Button
    private lateinit var copyDiagnosticsButton: Button
    private lateinit var udpVerboseCheck: CheckBox
    private lateinit var selfTestResultText: TextView
    private lateinit var trafficUploadValue: TextView
    private lateinit var trafficDownloadValue: TextView
    private lateinit var trafficUploadTotal: TextView
    private lateinit var trafficDownloadTotal: TextView
    private var trafficPollingJob: Job? = null
    private var lastTrafficSample: LongArray? = null
    private var lastTrafficTime = 0L
    
    private var isRunning = false
    private var pendingNotificationPermission = false
    private val REQUEST_NOTIFICATION_PERMISSION = 1002
    private val networkManager by lazy { CellularNetworkManager(this) }
    // 需要 ConnectivityManager 才能讀本機介面上的全域 IPv6（echo/STUN 全失敗時的備援）
    private val ipChecker by lazy {
        PublicIPChecker(getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager)
    }
    // [例外處理] SupervisorJob 讓單一子協程失敗不影響其他；CoroutineExceptionHandler
    // 攔下未捕捉例外並記 log，避免冒到 process 預設 handler 直接崩潰（背景 IP/網路查詢）。
    private val activityScope = CoroutineScope(
        Dispatchers.Main + SupervisorJob() + CoroutineExceptionHandler { _, e ->
            Log.e("DebugActivity", "activityScope uncaught exception", e)
        }
    )
    // [序列化] updateNetworkStatus 會被 onCreate/start/stop/refresh 多處呼叫；保留
    // 目前這次的 Job，新呼叫先取消舊的，避免多個協程競寫同一組 TextView。
    private var networkStatusJob: Job? = null
    // [狀態回呼] 保留自身回呼的參考，onDestroy 只清掉「還是自己」的那個；避免兩個
    // Activity 實例並存時，較舊的被銷毀卻把較新的 listener 清成 null。
    private val statusListener: (Socks5ProxyService.ProxyStatus) -> Unit = { status ->
        activityScope.launch { onProxyStatusChanged(status) }
    }
    
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        
        // 1. 同步 Service 運行狀態
        isRunning = Socks5ProxyService.isServiceRunning

        setupUI()
        
        // 訂閱 Service 狀態變更，UI 與真實狀態同步
        Socks5ProxyService.onStatusChanged = statusListener
        
        // 檢查上次退出原因
        checkLastExitReason()
        
        // 檢查是否有崩潰日誌紀錄
        val sp = getSharedPreferences("debug_log", Context.MODE_PRIVATE)
        val detailedLog = sp.getString("last_java_crash", null)

        if (detailedLog != null) {
            android.app.AlertDialog.Builder(this)
                .setTitle(getString(R.string.debug_crash_log_title))
                .setMessage(detailedLog)
                .setPositiveButton(getString(R.string.btn_copy_and_close)) { _, _ ->
                    // 複製到剪貼簿方便你傳給我
                    val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
                    val clip = ClipData.newPlainText("Crash Log", detailedLog)
                    clipboard.setPrimaryClip(clip)
                    
                    // 清除紀錄避免重複顯示
                    sp.edit().remove("last_java_crash").apply()
                    Toast.makeText(this, getString(R.string.toast_copied), Toast.LENGTH_SHORT).show()
                }
                .show()
        }

        // 2. 根據狀態更新 UI（已停止時保留「上次退出原因」的文字，不覆寫）
        if (Socks5ProxyService.currentStatus != Socks5ProxyService.ProxyStatus.STOPPED) {
            onProxyStatusChanged(Socks5ProxyService.currentStatus)
        }
        
        updateNetworkStatus()
    }

    // [IP 即時顯示] 出口 IP 由服務每 15~30 秒探測一次；這裡只輪詢結果、不另外發請求。
    // 過去只在 onCreate / 開始 / 停止時更新，換 IP 後畫面會一直停在舊值，
    // 看起來就像「Pro 沒跟上」，其實功能早就生效了。
    private val ipRefreshHandler = android.os.Handler(android.os.Looper.getMainLooper())
    private val ipRefreshRunnable = object : Runnable {
        override fun run() {
            try {
                val service = Socks5ProxyService.instance
                // echo/STUN 都不可用時退回本機 IPv6：issue #8 那種「行動數據正常、
                // 畫面卻只有 Failed」的情況，至少還有一個出口位址可以回報。
                val live = service?.currentEgressIp() ?: service?.currentLocalEgressIp()
                if (live != null && this@DebugActivity::cellularIPText.isInitialized) {
                    val text = getString(R.string.cellular_ip_format, live)
                    if (cellularIPText.text != text) cellularIPText.text = text
                    if (netCellIp != live) { netCellIp = live; renderConnectInfo() }
                }
            } catch (e: Exception) {
                Log.e("DebugActivity", "ip refresh failed", e)
            }
            ipRefreshHandler.postDelayed(this, 2000)
        }
    }

    override fun onResume() {
        super.onResume()
        ipRefreshHandler.removeCallbacks(ipRefreshRunnable)
        ipRefreshHandler.post(ipRefreshRunnable)
        // 回到前景就自動刷新連線資訊（熱點可能剛開或剛關）
        updateNetworkStatus()
    }

    override fun onPause() {
        ipRefreshHandler.removeCallbacks(ipRefreshRunnable)
        super.onPause()
    }

    override fun onDestroy() {
        ipRefreshHandler.removeCallbacks(ipRefreshRunnable)
        if (Socks5ProxyService.onStatusChanged === statusListener) {
            Socks5ProxyService.onStatusChanged = null
        }
        activityScope.cancel()
        super.onDestroy()
    }

    private fun onProxyStatusChanged(status: Socks5ProxyService.ProxyStatus) {
        val stopText = getString(R.string.btn_stop_proxy)
        val startText = getString(R.string.btn_start_proxy)
        when (status) {
            Socks5ProxyService.ProxyStatus.STARTING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(cRed)
                statusText.text = getString(R.string.status_starting)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cYellow, R.string.status_title_starting)
            }
            Socks5ProxyService.ProxyStatus.RUNNING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(cRed)
                statusText.text = getString(R.string.status_proxy_running)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cGreen, R.string.status_title_running)
                startTrafficPolling()
            }
            Socks5ProxyService.ProxyStatus.RESTARTING -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(cRed)
                statusText.text = getString(R.string.status_restarting)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cYellow, R.string.status_title_starting)
            }
            Socks5ProxyService.ProxyStatus.PAUSED -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(cRed)
                statusText.text = getString(R.string.status_waiting_network)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cYellow, R.string.status_title_waiting)
            }
            Socks5ProxyService.ProxyStatus.WAITING_HOTSPOT -> {
                isRunning = true
                mainButton.text = stopText
                mainButton.background = createButtonDrawable(cRed)
                statusText.text = getString(R.string.status_waiting_hotspot)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cYellow, R.string.status_title_waiting_hotspot)
                stopTrafficPolling()
            }
            Socks5ProxyService.ProxyStatus.STOPPED -> {
                isRunning = false
                mainButton.text = startText
                mainButton.background = createButtonDrawable(cAccent)
                statusText.text = getString(R.string.status_stopped)
                statusText.setTextColor(cTextSecondary)
                setStatusVisual(cGray, R.string.status_title_stopped)
                stopTrafficPolling()
            }
            Socks5ProxyService.ProxyStatus.FAILED -> {
                isRunning = false
                mainButton.text = startText
                mainButton.background = createButtonDrawable(cAccent)
                stopTrafficPolling()
                val error = Socks5ProxyService.lastErrorMessage
                statusText.text = if (error != null) {
                    getString(R.string.status_failed, error)
                } else {
                    getString(R.string.status_failed_generic)
                }
                statusText.setTextColor(cRed)
                setStatusVisual(cRed, R.string.status_title_error)
            }
        }
    }

    /** 狀態卡：圓點顏色 + 大字狀態；詳細訊息仍寫在 statusText。 */
    private fun setStatusVisual(color: Int, titleRes: Int) {
        if (!this::statusTitle.isInitialized) return
        (statusDot.background as GradientDrawable).setColor(color)
        statusTitle.text = getString(titleRes)
        statusTitle.setTextColor(if (color == cRed) cRed else cText)
    }

    private fun checkLastExitReason() {
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.R) {
            val activityManager = getSystemService(ACTIVITY_SERVICE) as android.app.ActivityManager
            val exitReasons = activityManager.getHistoricalProcessExitReasons(packageName, 0, 1)
            
            if (exitReasons.isNotEmpty()) {
                val reason = exitReasons[0]
                val reasonDescription = when (reason.reason) {
                    android.app.ApplicationExitInfo.REASON_LOW_MEMORY -> getString(R.string.exit_reason_low_mem)
                    android.app.ApplicationExitInfo.REASON_USER_REQUESTED -> getString(R.string.exit_reason_user_requested)
                    android.app.ApplicationExitInfo.REASON_USER_STOPPED -> getString(R.string.exit_reason_user_stopped)
                    android.app.ApplicationExitInfo.REASON_SIGNALED -> getString(R.string.exit_reason_signaled, reason.status)
                    android.app.ApplicationExitInfo.REASON_CRASH -> getString(R.string.exit_reason_crash)
                    android.app.ApplicationExitInfo.REASON_EXCESSIVE_RESOURCE_USAGE -> getString(R.string.exit_reason_resource)
                    else -> getString(R.string.exit_reason_other, reason.reason, reason.description ?: "")
                }
                
                statusText.text = getString(R.string.last_exit_prefix) + reasonDescription
                if (reason.reason != android.app.ApplicationExitInfo.REASON_USER_REQUESTED) {
                    statusText.setTextColor(android.graphics.Color.RED)
                }
            }
        }
    }

    private fun setupUI() {
        val rootLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(48, 64, 48, 48)
            setBackgroundColor(0xFFF8F9FA.toInt())
            // 高度為 WRAP_CONTENT：外層 ScrollView（setContentView 處）負責填滿與捲動
            layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
        }
        
        // Header
        val titleText = TextView(this).apply {
            text = getString(R.string.service_title)
            textSize = 28f
            setTextColor(0xFF212529.toInt())
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER
        }
        
        // Status Card
        statusDot = View(this).apply {
            background = GradientDrawable().apply { shape = GradientDrawable.OVAL; setColor(cGray) }
            layoutParams = LinearLayout.LayoutParams(dp(14), dp(14)).apply { rightMargin = dp(14) }
        }
        statusTitle = TextView(this).apply {
            text = getString(R.string.status_title_stopped)
            textSize = 20f
            setTypeface(typeface, Typeface.BOLD)
            setTextColor(cText)
        }
        statusText = TextView(this).apply {
            text = getString(R.string.service_not_started)
            textSize = 14f
            setTextColor(cTextSecondary)
            setPadding(0, dp(2), 0, 0)
        }
        val statusCard = createCard().apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(statusDot)
            addView(LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                addView(statusTitle)
                addView(statusText)
            }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        }
        
        // Port Config
        val portLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 48, 0, 48)
            gravity = Gravity.CENTER_VERTICAL
            
            val label = TextView(context).apply {
                text = getString(R.string.proxy_port_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt()) 
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            portInput = EditText(context).apply {
                // 還原上次使用的埠號（旋轉/重啟 Activity 不會退回預設 1080）
                setText(getSharedPreferences("proxy_config", Context.MODE_PRIVATE).getString("proxy_port", "1080") ?: "1080")
                inputType = android.text.InputType.TYPE_CLASS_NUMBER
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt()) 
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt()) 
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(portInput)
        }

        // 埠號即時持久化（比照帳密欄位的 TextWatcher 模式），
        // 旋轉螢幕或 Activity 重建後不會靜默改回 1080
        portInput.addTextChangedListener(object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                val value = s?.toString()?.trim().orEmpty()
                if (value.isNotEmpty()) {
                    getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                        .edit().putString("proxy_port", value).apply()
                }
            }
        })

        // Auth Config (可選帳密，留空 = 無認證)
        val authPrefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val authLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 16)
            gravity = Gravity.CENTER_VERTICAL

            val label = TextView(context).apply {
                text = getString(R.string.auth_user_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            authUserInput = EditText(context).apply {
                setText(authPrefs.getString("auth_user", "") ?: "")
                hint = getString(R.string.auth_user_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                setHintTextColor(0xFFADB5BD.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt())
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(authUserInput)
        }

        val passLayout = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            setPadding(0, 0, 0, 48)
            gravity = Gravity.CENTER_VERTICAL

            val label = TextView(context).apply {
                text = getString(R.string.auth_pass_label)
                textSize = 18f
                setTextColor(0xFF212529.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            }
            authPassInput = EditText(context).apply {
                setText(authPrefs.getString("auth_pass", "") ?: "")
                hint = getString(R.string.auth_pass_hint)
                inputType = android.text.InputType.TYPE_CLASS_TEXT or android.text.InputType.TYPE_TEXT_VARIATION_PASSWORD
                gravity = Gravity.CENTER
                setPadding(32, 24, 32, 24)
                setTextColor(0xFF212529.toInt())
                setHintTextColor(0xFFADB5BD.toInt())
                background = GradientDrawable().apply {
                    setColor(0xFFE9ECEF.toInt())
                    cornerRadius = 8f
                    setStroke(2, 0xFFCED4DA.toInt())
                }
                layoutParams = LinearLayout.LayoutParams(
                    0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f
                )
            }
            addView(label)
            addView(authPassInput)
        }

        val authWatcher = object : android.text.TextWatcher {
            override fun beforeTextChanged(s: CharSequence?, start: Int, count: Int, after: Int) {}
            override fun onTextChanged(s: CharSequence?, start: Int, before: Int, count: Int) {}
            override fun afterTextChanged(s: android.text.Editable?) {
                applyAuthChange()
            }
        }
        authUserInput.addTextChangedListener(authWatcher)
        authPassInput.addTextChangedListener(authWatcher)

        // [開機自動啟動] 預設關。代理預設無認證，預設開等於每次開機都在區域網路上
        // 自動開一個開放式 SOCKS5，使用者不在場也不會知道。實際啟動條件見 BootReceiver。
        //
        // 這裡用 CheckBox 而不是 Switch：勾選框「有打勾 = 開」在任何主題、任何螢幕尺寸下
        // 都能一眼看出狀態；滑桿只靠顏色與位置差異，深色模式或小螢幕上很容易誤讀。
        // 兩行式（標題 + 灰色說明）也讓「按過停止就不會還原」這條規則不必擠在標題裡。
        val autoStartLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, 16)
            addView(CheckBox(this@DebugActivity).apply {
                text = getString(R.string.label_auto_start_on_boot)
                textSize = 16f
                setTextColor(0xFF212529.toInt())
                setPadding(0, 0, 0, 0)
                // 預設 tint 在淺色底上偏灰，勾起來與沒勾差別很小 —— 明確指定主題紫，
                // 讓「有打勾」在任何亮度下都看得出來。
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF6200EE.toInt())
                // 先設 isChecked 再掛 listener，避免初始化時就觸發一次寫入。
                isChecked = authPrefs.getBoolean(Socks5ProxyService.PREF_AUTO_START, false)
                setOnCheckedChangeListener { _, checked ->
                    authPrefs.edit().putBoolean(Socks5ProxyService.PREF_AUTO_START, checked).apply()
                }
            })
            addView(TextView(this@DebugActivity).apply {
                text = getString(R.string.hint_auto_start_on_boot)
                textSize = 12f
                setTextColor(0xFF6C757D.toInt())
                setPadding(8, 0, 8, 0)
            })
        }
        
        // Main Action Button
        mainButton = Button(this).apply {
            text = getString(R.string.btn_start_proxy)
            textSize = 18f
            setTextColor(Color.WHITE)
            background = createButtonDrawable(cAccent)
            setPadding(0, 32, 0, 32)
            setOnClickListener { toggleProxy() }
        }

        val limitHintText = TextView(this).apply {
            text = getString(R.string.hint_connection_limit)
            textSize = 12f
            setTextColor(0xFF6C757D.toInt())
            setPadding(8, 16, 8, 0)
            gravity = Gravity.CENTER_HORIZONTAL
        }
        
        // IP Display Card
        val ipCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT, 
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 48
            }
            
            wifiIPText = TextView(context).apply {
                text = getString(R.string.wifi_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            hotspotIPText = TextView(context).apply {
                text = getString(R.string.hotspot_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            usbTetherIPText = TextView(context).apply {
                text = getString(R.string.usb_tether_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            cellularIPText = TextView(context).apply {
                text = getString(R.string.cellular_ip_fetching)
                textSize = 16f
                setPadding(0, 8, 0, 8)
                setTextColor(0xFF495057.toInt())
            }
            addView(wifiIPText)
            addView(hotspotIPText)
            addView(usbTetherIPText)
            addView(cellularIPText)
        }
        
        // Refresh Status Button
        val refreshButton = Button(this).apply {
            text = getString(R.string.btn_refresh_status)
            textSize = 14f
            setTextColor(0xFF6200EE.toInt())
            background = GradientDrawable().apply {
                setColor(Color.WHITE)
                cornerRadius = 12f
                setStroke(2, 0xFF6200EE.toInt())
            }
            setPadding(0, 24, 0, 24)
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }
            setOnClickListener { refreshStatus() }
        }
        

        // Traffic Card（範本 B：上傳/下載雙欄彩色大數字）
        val trafficCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply { topMargin = 24 }

            addView(TextView(context).apply {
                text = getString(R.string.traffic_card_title)
                textSize = 14f
                setTextColor(0xFF6C757D.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
                setPadding(0, 0, 0, 12)
            })

            val cols = LinearLayout(context).apply { orientation = LinearLayout.HORIZONTAL }

            val upCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            upCol.addView(TextView(context).apply {
                text = getString(R.string.traffic_upload_label)
                textSize = 12f
                setTextColor(0xFF6C757D.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            trafficUploadValue = TextView(context).apply {
                text = getString(R.string.traffic_na)
                textSize = 18f
                setTextColor(0xFF28A745.toInt())
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 2)
            }
            upCol.addView(trafficUploadValue)
            trafficUploadTotal = TextView(context).apply {
                text = getString(R.string.traffic_na)
                textSize = 12f
                setTextColor(0xFF6C757D.toInt())
            }
            upCol.addView(trafficUploadTotal)

            val downCol = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                gravity = Gravity.CENTER
                layoutParams = LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1.0f)
            }
            downCol.addView(TextView(context).apply {
                text = getString(R.string.traffic_download_label)
                textSize = 12f
                setTextColor(0xFF6C757D.toInt())
                setTypeface(null, android.graphics.Typeface.BOLD)
            })
            trafficDownloadValue = TextView(context).apply {
                text = getString(R.string.traffic_na)
                textSize = 18f
                setTextColor(0xFF0D6EFD.toInt())
                typeface = android.graphics.Typeface.create(android.graphics.Typeface.MONOSPACE, android.graphics.Typeface.BOLD)
                setPadding(0, 4, 0, 2)
            }
            downCol.addView(trafficDownloadValue)
            trafficDownloadTotal = TextView(context).apply {
                text = getString(R.string.traffic_na)
                textSize = 12f
                setTextColor(0xFF6C757D.toInt())
            }
            downCol.addView(trafficDownloadTotal)

            cols.addView(upCol)
            cols.addView(downCol)
            addView(cols)
        }

        // Diagnostics Card（自我檢測 + 複製診斷報告）
        val diagCard = createCard().apply {
            layoutParams = LinearLayout.LayoutParams(
                ViewGroup.LayoutParams.MATCH_PARENT,
                ViewGroup.LayoutParams.WRAP_CONTENT
            ).apply {
                topMargin = 24
            }

            // [開發者開關] 逐行 UDP session 拆除 log。預設關：一般使用者不需要，
            // 開了只會把 logcat 沖掉。拆除的「分類計數」不受此開關影響 ——
            // 它永遠寫進 engine_stats.log，所以預設也不會回到完全靜默。
            val udpVerboseRow = LinearLayout(context).apply {
                orientation = LinearLayout.VERTICAL
                setPadding(0, 0, 0, 8)
            }
            udpVerboseCheck = CheckBox(context).apply {
                text = getString(R.string.switch_udp_verbose_log)
                textSize = 14f
                setTextColor(0xFF495057.toInt())
                setPadding(0, 0, 0, 0)
                buttonTintList = android.content.res.ColorStateList.valueOf(0xFF6200EE.toInt())
                // 先設 isChecked 再掛 listener，避免初始化時就觸發一次寫入。
                isChecked = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                    .getBoolean(Socks5ProxyService.PREF_UDP_VERBOSE_LOG, false)
                setOnCheckedChangeListener { _, checked ->
                    getSharedPreferences("proxy_config", Context.MODE_PRIVATE).edit()
                        .putBoolean(Socks5ProxyService.PREF_UDP_VERBOSE_LOG, checked).apply()
                    // 立即套用：native 旗標執行期可切換，不必重啟服務。
                    NativeEngine.safeSetUdpVerboseLog(checked)
                }
            }
            udpVerboseRow.addView(udpVerboseCheck)
            addView(udpVerboseRow)

            selfTestButton = Button(context).apply {
                text = getString(R.string.btn_self_test)
                textSize = 16f
                setTextColor(Color.WHITE)
                background = createButtonDrawable(0xFF17A2B8.toInt())
                setPadding(0, 24, 0, 24)
                setOnClickListener { runSelfTest() }
            }
            addView(selfTestButton)

            copyDiagnosticsButton = Button(context).apply {
                text = getString(R.string.btn_copy_diagnostics)
                textSize = 14f
                setTextColor(0xFF6200EE.toInt())
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 12f
                    setStroke(2, 0xFF6200EE.toInt())
                }
                setPadding(0, 24, 0, 24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 16
                }
                setOnClickListener { copyDiagnostics() }
            }
            addView(copyDiagnosticsButton)

            // [回報用] 一鍵匯出「App 內日誌 + 本行程 logcat」。存在的理由：回報問題的人
            // 幾乎不會用 adb，而「Cellular IP: Failed」這種一行訊息完全看不出原因。
            val copyLogButton = Button(context).apply {
                text = getString(R.string.btn_copy_log)
                textSize = 14f
                setTextColor(0xFF17A2B8.toInt())
                background = GradientDrawable().apply {
                    setColor(Color.WHITE)
                    cornerRadius = 12f
                    setStroke(2, 0xFF17A2B8.toInt())
                }
                setPadding(0, 24, 0, 24)
                layoutParams = LinearLayout.LayoutParams(
                    ViewGroup.LayoutParams.MATCH_PARENT,
                    ViewGroup.LayoutParams.WRAP_CONTENT
                ).apply {
                    topMargin = 12
                }
                setOnClickListener { copyLog() }
            }
            addView(copyLogButton)

            addView(TextView(context).apply {
                text = getString(R.string.log_hint)
                textSize = 12f
                setTextColor(0xFF868E96.toInt())
                setPadding(0, 8, 0, 0)
            })

            selfTestResultText = TextView(context).apply {
                textSize = 14f
                setTextColor(0xFF495057.toInt())
                setPadding(0, 16, 0, 0)
                visibility = View.GONE
            }
            addView(selfTestResultText)
        }
        // ── GW 版：統一配色 ──
        // 首頁固定一頁：狀態卡 → 連線資訊 → 流量 →（空白）→ [設定][診斷] → 主按鈕（拇指區）。
        // 進階設定與診斷改成從下往上滑出的面板，首頁不會因展開而變長。
        actionBar?.hide()
        rootLayout.setBackgroundColor(cPageBg)
        rootLayout.setPadding(dp(16), dp(8), dp(16), dp(16))
        rootLayout.layoutParams = ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.MATCH_PARENT)
        listOf(portInput, authUserInput, authPassInput).forEach { styleField(it) }
        authPassInput.typeface = Typeface.DEFAULT
        listOf(portLayout, authLayout, passLayout).forEach { row ->
            row.setPadding(0, dp(6), 0, dp(6))
            (row.getChildAt(0) as TextView).apply {
                textSize = 15f
                setTextColor(cText)
                setPadding(0, 0, dp(12), 0)
                // 固定寬度，讓三個輸入框左緣對齊
                layoutParams = LinearLayout.LayoutParams(dp(104), ViewGroup.LayoutParams.WRAP_CONTENT)
            }
        }
        (autoStartLayout.getChildAt(0) as CheckBox).apply {
            setTextColor(cText); buttonTintList = ColorStateList.valueOf(cAccent)
            minHeight = dp(44)
        }
        (autoStartLayout.getChildAt(1) as TextView).setTextColor(cTextSecondary)
        // [GW 熱點連動] 熱點開→自動啟動代理，關→自動停（通知留著等下一次）
        val hotspotLinkLayout = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            setPadding(0, 0, 0, dp(8))
            addView(CheckBox(this@DebugActivity).apply {
                text = getString(R.string.label_hotspot_link)
                textSize = 16f
                minHeight = dp(44)
                setTextColor(cText)
                buttonTintList = ColorStateList.valueOf(cAccent)
                isChecked = authPrefs.getBoolean(Socks5ProxyService.PREF_HOTSPOT_LINK, false)
                setOnCheckedChangeListener { _, checked ->
                    authPrefs.edit().putBoolean(Socks5ProxyService.PREF_HOTSPOT_LINK, checked).apply()
                    // 勾起來就馬上開始等熱點（不用另外按啟動）；服務已在跑就即時套用
                    if (checked && !Socks5ProxyService.isServiceRunning) startProxyFlow()
                    else Socks5ProxyService.instance?.applyHotspotLink(checked)
                }
            })
            addView(TextView(this@DebugActivity).apply {
                text = getString(R.string.hint_hotspot_link)
                textSize = 12f
                setTextColor(cTextSecondary)
                setPadding(dp(4), 0, dp(4), 0)
            })
        }
        udpVerboseCheck.apply { setTextColor(cText); buttonTintList = ColorStateList.valueOf(cAccent); minHeight = dp(44) }
        selfTestResultText.setTextColor(cText)

        // App 名稱：小字，取代標題列
        val appLabel = TextView(this).apply {
            text = getString(R.string.service_title) + " GW"
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(dp(4), 0, 0, dp(8))
        }
        statusText.apply { maxLines = 2; ellipsize = android.text.TextUtils.TruncateAt.END }

        // 連線資訊：只放大 Client 要填的那組 IP:埠，其他網路縮成一行灰字
        ipCard.removeAllViews()
        connectPrimaryText = TextView(this).apply {
            text = getString(R.string.net_fetching)
            textSize = 24f
            setTypeface(Typeface.DEFAULT, Typeface.BOLD)
            fontFeatureSettings = "tnum"
            setTextColor(cText)
            setPadding(0, dp(2), 0, 0)
        }
        connectViaText = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary)
        }
        connectSummaryText = TextView(this).apply {
            textSize = 13f
            setTextColor(cTextSecondary)
            fontFeatureSettings = "tnum"
            setPadding(0, dp(8), 0, 0)
            maxLines = 2
        }
        val refreshLink = TextView(this).apply {
            text = getString(R.string.btn_refresh_short)
            textSize = 14f
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            minHeight = dp(44)
            setPadding(dp(12), 0, 0, 0)
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                refreshStatus()
            }
        }
        ipCard.addView(LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            gravity = Gravity.CENTER_VERTICAL
            addView(sectionLabel(getString(R.string.label_connect_info)).apply { setPadding(0, 0, 0, 0) },
                LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
            addView(refreshLink)
        })
        ipCard.addView(connectPrimaryText)
        ipCard.addView(connectViaText)
        ipCard.addView(TextView(this).apply {
            text = getString(R.string.hint_client_gateway)
            textSize = 13f
            setTextColor(cTextSecondary)
            setPadding(0, dp(8), 0, 0)
        })
        ipCard.addView(connectSummaryText)
        renderConnectInfo()

        // 流量：同一個字色、等寬數字；沒流量時只顯示一句話
        (trafficCard.getChildAt(0) as TextView).apply {
            setTextColor(cTextSecondary); textSize = 13f; setTypeface(Typeface.DEFAULT, Typeface.NORMAL)
            setPadding(0, 0, 0, dp(6))
        }
        trafficCols = trafficCard.getChildAt(1)
        listOf(trafficUploadValue, trafficDownloadValue).forEach {
            it.setTextColor(cText); it.textSize = 20f
            it.typeface = Typeface.DEFAULT_BOLD; it.fontFeatureSettings = "tnum"
        }
        listOf(trafficUploadTotal, trafficDownloadTotal).forEach {
            it.setTextColor(cTextSecondary); it.fontFeatureSettings = "tnum"
        }
        trafficEmptyText = TextView(this).apply {
            text = getString(R.string.traffic_empty)
            textSize = 15f
            setTextColor(cTextSecondary)
            setPadding(0, dp(4), 0, dp(4))
        }
        trafficCard.addView(trafficEmptyText)
        resetTrafficLabels()

        // 誰在用：標題含台數，最多列 3 台，點一下取名字
        clientsCard = createCard()
        clientsTitle = TextView(this).apply {
            textSize = 13f; setTextColor(cTextSecondary); setPadding(0, 0, 0, dp(2))
        }
        clientsList = LinearLayout(this).apply { orientation = LinearLayout.VERTICAL }
        clientsCard.addView(clientsTitle)
        clientsCard.addView(clientsList)
        renderClients()

        mainButton.apply {
            isAllCaps = false
            stateListAnimator = null
            textSize = 17f
            setTypeface(typeface, Typeface.BOLD)
            setPadding(0, 0, 0, 0)
            layoutParams = LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(56)).apply { topMargin = dp(12) }
            // 按下微縮，放開彈回
            setOnTouchListener { v, e ->
                when (e.actionMasked) {
                    android.view.MotionEvent.ACTION_DOWN ->
                        v.animate().scaleX(0.97f).scaleY(0.97f).setDuration(90).start()
                    android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL ->
                        v.animate().scaleX(1f).scaleY(1f).setDuration(220)
                            .setInterpolator(android.view.animation.OvershootInterpolator(2f)).start()
                }
                false
            }
        }
        listOf(selfTestButton, copyDiagnosticsButton).forEach { styleSecondaryButton(it) }
        (0 until diagCard.childCount).map { diagCard.getChildAt(it) }
            .filterIsInstance<Button>().forEach { styleSecondaryButton(it) }
        limitHintText.gravity = Gravity.START
        limitHintText.setTextColor(cTextSecondary)
        limitHintText.setPadding(0, dp(8), 0, 0)
        val advancedBody = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(portLayout); addView(authLayout); addView(passLayout); addView(autoStartLayout)
            addView(hotspotLinkLayout)
            addView(limitHintText)
        }
        diagCard.background = null
        diagCard.setPadding(0, 0, 0, 0)
        val advancedPanel = makePanel(R.string.label_advanced, advancedBody)
        val diagPanel = makePanel(R.string.label_diagnostics, diagCard)

        // 次要入口：一列兩個小按鈕
        fun entryButton(titleRes: Int, panel: android.app.Dialog) = TextView(this).apply {
            text = getString(titleRes) + "  ›"
            textSize = 15f
            setTextColor(cAccent)
            gravity = Gravity.CENTER
            background = rounded(cCardBg, 12)
            minHeight = dp(44)
            setOnClickListener {
                it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
                panel.show()
            }
        }
        val entryRow = LinearLayout(this).apply {
            orientation = LinearLayout.HORIZONTAL
            addView(entryButton(R.string.label_settings_short, advancedPanel),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { rightMargin = dp(6) })
            addView(entryButton(R.string.label_diagnostics, diagPanel),
                LinearLayout.LayoutParams(0, dp(44), 1f).apply { leftMargin = dp(6) })
        }
        listOf(ipCard, trafficCard, clientsCard).forEach {
            (it.layoutParams as? LinearLayout.LayoutParams)?.topMargin = dp(12)
        }

        rootLayout.addView(appLabel)
        rootLayout.addView(statusCard)
        rootLayout.addView(ipCard)
        rootLayout.addView(trafficCard)
        rootLayout.addView(clientsCard)
        rootLayout.addView(View(this), LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, 0, 1f))
        rootLayout.addView(entryRow, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(44)).apply { topMargin = dp(12) })
        rootLayout.addView(mainButton)

        // 保底：字體調很大、真的放不下時才能捲；一般情況 fillViewport 讓內容剛好填滿一頁
        val scrollView = ScrollView(this).apply {
            isFillViewport = true
            setBackgroundColor(cPageBg)
            isVerticalScrollBarEnabled = false
            overScrollMode = View.OVER_SCROLL_NEVER
        }
        scrollView.addView(rootLayout)
        setContentView(scrollView)
    }

    /** 從下往上滑出的面板：背景變暗、點外面或往下拉標題就收起。 */
    private fun makePanel(titleRes: Int, body: View): android.app.Dialog {
        val dialog = android.app.Dialog(this)
        dialog.requestWindowFeature(android.view.Window.FEATURE_NO_TITLE)
        val sheet = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            background = GradientDrawable().apply {
                setColor(cCardBg)
                val r = dp(20).toFloat()
                cornerRadii = floatArrayOf(r, r, r, r, 0f, 0f, 0f, 0f)
            }
            setPadding(dp(16), dp(8), dp(16), dp(24))
        }
        val grabber = View(this).apply {
            background = rounded(cStroke, 3)
        }
        val header = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(grabber, LinearLayout.LayoutParams(dp(36), dp(5)).apply {
                gravity = Gravity.CENTER_HORIZONTAL; bottomMargin = dp(8)
            })
            addView(LinearLayout(this@DebugActivity).apply {
                orientation = LinearLayout.HORIZONTAL
                gravity = Gravity.CENTER_VERTICAL
                addView(TextView(this@DebugActivity).apply {
                    text = getString(titleRes)
                    textSize = 18f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(cText)
                }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
                addView(TextView(this@DebugActivity).apply {
                    text = getString(R.string.btn_done)
                    textSize = 16f
                    setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                    setTextColor(cAccent)
                    gravity = Gravity.CENTER
                    minHeight = dp(44)
                    setPadding(dp(12), 0, 0, 0)
                    setOnClickListener { dialog.dismiss() }
                })
            })
        }
        // 往下拉標題列收起
        var downY = 0f
        header.setOnTouchListener { _, e ->
            when (e.actionMasked) {
                android.view.MotionEvent.ACTION_DOWN -> { downY = e.rawY; true }
                android.view.MotionEvent.ACTION_MOVE -> {
                    sheet.translationY = (e.rawY - downY).coerceAtLeast(0f); true
                }
                android.view.MotionEvent.ACTION_UP, android.view.MotionEvent.ACTION_CANCEL -> {
                    if (sheet.translationY > dp(80)) dialog.dismiss()
                    else sheet.animate().translationY(0f).setDuration(200).start()
                    true
                }
                else -> false
            }
        }
        sheet.addView(header)
        sheet.addView(ScrollView(this).apply {
            isVerticalScrollBarEnabled = false
            addView(body, ViewGroup.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT))
        }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT).apply { topMargin = dp(8) })
        dialog.setContentView(sheet)
        dialog.setCanceledOnTouchOutside(true)
        dialog.setOnShowListener { sheet.translationY = 0f }
        dialog.window?.apply {
            setBackgroundDrawable(android.graphics.drawable.ColorDrawable(Color.TRANSPARENT))
            setLayout(ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT)
            setGravity(Gravity.BOTTOM)
            setWindowAnimations(android.R.style.Animation_InputMethod)
            setDimAmount(0.45f)
            setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        return dialog
    }

    // ── 連線資訊（首頁大字）──
    private var netWifiIp: String? = null
    private var netHotspotIp: String? = null
    private var netUsbIp: String? = null
    private var netCellIp: String? = null
    private var netLoaded = false
    private lateinit var connectPrimaryText: TextView
    private lateinit var connectViaText: TextView
    private lateinit var connectSummaryText: TextView
    private var trafficCols: View? = null
    private var trafficEmptyText: TextView? = null
    private lateinit var clientsCard: LinearLayout
    private lateinit var clientsTitle: TextView
    private lateinit var clientsList: LinearLayout
    private var shownClientKeys: Set<String> = emptySet()
    private var clientsExpanded = false

    private fun renderClients() {
        if (!this::clientsList.isInitialized) return
        val list = if (Socks5ProxyService.isServiceRunning) ClientTracker.clients else emptyList()
        clientsTitle.text = getString(R.string.clients_title, list.size)
        // 有人正在用：整張卡片換成淡綠底、綠色細框、標題變綠，一眼看出「有人連上來」
        val anyActive = list.any { it.active }
        clientsCard.background = if (anyActive) {
            rounded(if (isDark) 0xFF13301D.toInt() else 0xFFE3F8E8.toInt(), 16, cGreen)
        } else rounded(cCardBg, 16)
        clientsTitle.setTextColor(if (anyActive) cGreen else cTextSecondary)
        clientsTitle.setTypeface(Typeface.DEFAULT, if (anyActive) Typeface.BOLD else Typeface.NORMAL)
        clientsList.removeAllViews()
        if (list.isEmpty()) {
            clientsList.addView(TextView(this).apply {
                text = getString(R.string.clients_empty)
                textSize = 15f; setTextColor(cTextSecondary); setPadding(0, dp(4), 0, dp(4))
            })
            shownClientKeys = emptySet()
            return
        }
        val newKeys = list.map { it.key }.toSet()
        val fresh = newKeys - shownClientKeys
        if (shownClientKeys.isNotEmpty() && fresh.isNotEmpty()) {
            clientsCard.performHapticFeedback(android.view.HapticFeedbackConstants.CONFIRM)
        }
        val limit = if (clientsExpanded) list.size else 3
        list.take(limit).forEach { c ->
            val row = clientRow(c)
            if (c.key in fresh && shownClientKeys.isNotEmpty()) {
                row.alpha = 0f; row.animate().alpha(1f).setDuration(250).start()
            }
            clientsList.addView(row)
        }
        if (list.size > limit) {
            clientsList.addView(TextView(this).apply {
                text = getString(R.string.clients_more, list.size - limit)
                textSize = 14f; setTextColor(cAccent)
                minHeight = dp(36); gravity = Gravity.CENTER_VERTICAL
                setOnClickListener { clientsExpanded = true; renderClients() }
            })
        }
        shownClientKeys = newKeys
    }

    private fun clientRow(c: ClientTracker.Client): View = LinearLayout(this).apply {
        orientation = LinearLayout.HORIZONTAL
        gravity = Gravity.CENTER_VERTICAL
        minimumHeight = dp(44)
        addView(View(this@DebugActivity).apply {
            background = GradientDrawable().apply {
                shape = GradientDrawable.OVAL; setColor(if (c.active) cGreen else cGray)
            }
        }, LinearLayout.LayoutParams(dp(8), dp(8)).apply { rightMargin = dp(10) })
        val name = ClientTracker.nameOf(this@DebugActivity, c) ?: ClientTracker.autoName(c)
        addView(LinearLayout(this@DebugActivity).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DebugActivity).apply {
                text = name ?: c.ip
                textSize = 16f; setTextColor(cText); setTypeface(Typeface.DEFAULT, Typeface.BOLD)
                fontFeatureSettings = "tnum"; maxLines = 1
            })
            if (name != null) addView(TextView(this@DebugActivity).apply {
                text = c.ip; textSize = 12f; setTextColor(cTextSecondary); fontFeatureSettings = "tnum"
            })
        }, LinearLayout.LayoutParams(0, ViewGroup.LayoutParams.WRAP_CONTENT, 1f))
        addView(TextView(this@DebugActivity).apply {
            text = when {
                c.active -> getString(R.string.clients_active)
                c.ageSec < 120 -> getString(R.string.clients_just_now)
                else -> getString(R.string.clients_min_ago, (c.ageSec / 60).toInt())
            }
            textSize = 13f; setTextColor(if (c.active) cGreen else cTextSecondary)
            fontFeatureSettings = "tnum"
        })
        setOnClickListener {
            it.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
            showRenameClient(c)
        }
    }

    private fun showRenameClient(c: ClientTracker.Client) {
        val input = EditText(this).apply {
            setText(ClientTracker.nameOf(this@DebugActivity, c) ?: c.reportedName ?: "")
            hint = getString(R.string.clients_rename_hint)
            isSingleLine = true
            textSize = 17f
            setTextColor(cText); setHintTextColor(cTextSecondary)
            background = rounded(cFieldBg, 10)
            setPadding(dp(12), 0, dp(12), 0)
        }
        lateinit var panel: android.app.Dialog
        val body = LinearLayout(this).apply {
            orientation = LinearLayout.VERTICAL
            addView(TextView(this@DebugActivity).apply {
                text = c.ip + (c.mac?.let { "  ·  $it" } ?: "")
                textSize = 13f; setTextColor(cTextSecondary); fontFeatureSettings = "tnum"
                setPadding(dp(4), 0, 0, dp(8))
            })
            addView(input, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)))
            addView(Button(this@DebugActivity).apply {
                text = getString(R.string.btn_save)
                isAllCaps = false; stateListAnimator = null
                setTextColor(Color.WHITE); textSize = 16f
                background = rounded(cAccent, 12)
                setOnClickListener {
                    ClientTracker.setName(this@DebugActivity, c, input.text.toString())
                    panel.dismiss(); renderClients()
                }
            }, LinearLayout.LayoutParams(ViewGroup.LayoutParams.MATCH_PARENT, dp(48)).apply { topMargin = dp(12) })
        }
        panel = makePanel(R.string.clients_rename_title, body)
        panel.setOnShowListener {
            input.requestFocus(); input.setSelection(input.text.length)
            panel.window?.setSoftInputMode(android.view.WindowManager.LayoutParams.SOFT_INPUT_STATE_VISIBLE or
                android.view.WindowManager.LayoutParams.SOFT_INPUT_ADJUST_RESIZE)
        }
        panel.show()
    }

    private fun renderConnectInfo() {
        if (!this::connectPrimaryText.isInitialized) return
        val port = portInput.text.toString().toIntOrNull() ?: 1080
        val (ip, viaRes) = when {
            netHotspotIp != null -> netHotspotIp to R.string.connect_via_hotspot
            netUsbIp != null -> netUsbIp to R.string.connect_via_usb
            netWifiIp != null -> netWifiIp to R.string.connect_via_wifi
            else -> null to 0
        }
        if (ip != null) {
            connectPrimaryText.text = "$ip:$port"
            connectViaText.text = getString(viaRes)
        } else {
            connectPrimaryText.text = getString(if (netLoaded) R.string.connect_none_title else R.string.net_fetching)
            connectViaText.text = if (netLoaded) getString(R.string.connect_none_hint) else ""
        }
        val off = getString(R.string.net_off)
        val cell = netCellIp ?: getString(R.string.net_fetching)
        connectSummaryText.text = getString(R.string.net_summary, netWifiIp ?: off, netUsbIp ?: off, cell)
    }

    // ── GW 版樣式工具：配色跟 5G Proxy Client GW 一致，依主題深淺切換 ──
    private fun dp(v: Int): Int = (v * resources.displayMetrics.density).toInt()
    private val isDark: Boolean by lazy {
        val a = obtainStyledAttributes(intArrayOf(android.R.attr.textColorPrimary))
        val c = a.getColor(0, Color.WHITE)
        a.recycle()
        (Color.red(c) + Color.green(c) + Color.blue(c)) / 3 > 128
    }
    private val cPageBg get() = if (isDark) 0xFF111216.toInt() else 0xFFF2F2F7.toInt()
    private val cCardBg get() = if (isDark) 0xFF1E1F24.toInt() else 0xFFFFFFFF.toInt()
    private val cFieldBg get() = if (isDark) 0xFF26272D.toInt() else 0xFFF7F7FA.toInt()
    private val cStroke get() = if (isDark) 0xFF3A3B43.toInt() else 0xFFD8D8DE.toInt()
    private val cText get() = if (isDark) 0xFFF5F5F7.toInt() else 0xFF1C1C1E.toInt()
    private val cTextSecondary get() = if (isDark) 0xFF9A9AA2.toInt() else 0xFF6C6C72.toInt()
    private val cAccent = 0xFF0A84FF.toInt()
    private val cGreen = 0xFF30D158.toInt()
    private val cYellow = 0xFFFFC300.toInt()
    private val cRed = 0xFFFF453A.toInt()
    private val cGray = 0xFF8E8E93.toInt()

    private fun rounded(color: Int, radiusDp: Int, strokeColor: Int? = null) = GradientDrawable().apply {
        setColor(color)
        cornerRadius = dp(radiusDp).toFloat()
        if (strokeColor != null) setStroke(dp(1), strokeColor)
    }

    private fun createCard() = LinearLayout(this).apply {
        orientation = LinearLayout.VERTICAL
        setPadding(dp(16), dp(14), dp(16), dp(14))
        background = rounded(cCardBg, 16)
        layoutParams = LinearLayout.LayoutParams(
            ViewGroup.LayoutParams.MATCH_PARENT, ViewGroup.LayoutParams.WRAP_CONTENT
        )
    }

    private fun createButtonDrawable(color: Int) = rounded(color, 14)

    private fun sectionLabel(text: String) = TextView(this).apply {
        this.text = text
        textSize = 13f
        setTextColor(cTextSecondary)
        setPadding(0, 0, 0, dp(6))
    }

    private fun styleField(et: EditText) {
        et.background = rounded(cFieldBg, 10, cStroke)
        et.setPadding(dp(12), dp(10), dp(12), dp(10))
        et.setTextColor(cText)
        et.setHintTextColor(cTextSecondary)
        et.gravity = Gravity.CENTER_VERTICAL or Gravity.START
    }

    private fun styleSecondaryButton(b: Button) {
        b.background = rounded(cFieldBg, 10)
        b.isAllCaps = false
        b.stateListAnimator = null
        b.textSize = 15f
        b.setTextColor(ColorStateList(
            arrayOf(intArrayOf(-android.R.attr.state_enabled), intArrayOf()),
            intArrayOf(cGray, cAccent)
        ))
    }

    private fun toggleProxy() {
        mainButton.performHapticFeedback(android.view.HapticFeedbackConstants.VIRTUAL_KEY)
        if (!isRunning) {
            startProxyFlow()
        } else {
            stopProxyFlow()
        }
    }
    
    private fun applyAuthChange() {
        val user = authUserInput.text.toString().trim()
        val pass = authPassInput.text.toString()
        getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_user", user)
            .putString("auth_pass", pass)
            .apply()
        if (Socks5ProxyService.isServiceRunning) {
            NativeEngine.setSocks5Auth(user, pass)
            Toast.makeText(this, getString(R.string.auth_applied_live), Toast.LENGTH_SHORT).show()
        }
    }

    private fun startProxyFlow() {
        // 註：這裡刻意不做「有 VPN 就先擋」的前置檢查。VPN 存在 ≠ 它接管了本 App 的
        // 路由（per-app VPN 未包含本 App 時代理照常可用），用存在與否當前提會誤殺
        // 可用的設定。能不能出去由服務端實際 bind 一次蜂巢式出口來決定，
        // 失敗時會以 FAILED 狀態把原因顯示在畫面上（見 onDestroy 保留 FAILED）。
        if (!PowerPermissionHelper.isWhitelisted(this)) {
            // 未加入電池白名單：說明風險，並允許使用者「仍然繼續」
            PowerPermissionHelper.showOptimizationDialog(this) {
                Toast.makeText(this, getString(R.string.toast_battery_warning), Toast.LENGTH_LONG).show()
                continueStartProxyFlow()
            }
            return
        }
        continueStartProxyFlow()
    }

    private fun continueStartProxyFlow() {
        // Android 13+：要求通知權限，否則前景服務通知不會顯示
        if (android.os.Build.VERSION.SDK_INT >= android.os.Build.VERSION_CODES.TIRAMISU &&
            checkSelfPermission(android.Manifest.permission.POST_NOTIFICATIONS) != PackageManager.PERMISSION_GRANTED
        ) {
            pendingNotificationPermission = true
            requestPermissions(arrayOf(android.Manifest.permission.POST_NOTIFICATIONS), REQUEST_NOTIFICATION_PERMISSION)
            return
        }
        startProxyInternal()
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<out String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (requestCode == REQUEST_NOTIFICATION_PERMISSION && pendingNotificationPermission) {
            pendingNotificationPermission = false
            if (grantResults.isNotEmpty() && grantResults[0] == PackageManager.PERMISSION_GRANTED) {
                Log.d("DebugActivity", "Notification permission granted")
            } else {
                Toast.makeText(this, getString(R.string.toast_notification_denied), Toast.LENGTH_LONG).show()
            }
            startProxyInternal()
        }
    }

    private fun startProxyInternal() {
        val port = portInput.text.toString().toIntOrNull() ?: 1080

        getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
            .edit()
            .putString("auth_user", authUserInput.text.toString().trim())
            .putString("auth_pass", authPassInput.text.toString())
            .apply()

        isRunning = true
        mainButton.text = getString(R.string.btn_stop_proxy)
        mainButton.background = createButtonDrawable(cRed)
        statusText.text = getString(R.string.status_starting)
        
        val intent = Intent(this, Socks5ProxyService::class.java).apply {
            action = Socks5ProxyService.ACTION_START_PROXY
            putExtra(Socks5ProxyService.EXTRA_PORT, port)
        }
        startService(intent)
        
        activityScope.launch {
            delay(3000)
            updateNetworkStatus()
        }
    }
    
    private fun stopProxyFlow() {
        isRunning = false
        mainButton.text = getString(R.string.btn_start_proxy)
        mainButton.background = createButtonDrawable(cAccent)
        
        val intent = Intent(this, Socks5ProxyService::class.java).apply {
            action = Socks5ProxyService.ACTION_STOP_PROXY
        }
        startService(intent)
        statusText.text = getString(R.string.status_stopped)
        updateNetworkStatus()
    }
    
    private fun refreshStatus() {
        statusText.text = getString(R.string.status_refreshing)
        updateNetworkStatus()
        // 以 Service 實際回報的狀態更新，不依賴 Activity 本地猜測
        onProxyStatusChanged(Socks5ProxyService.currentStatus)
    }

    // ================= 流量統計（範本 B） =================

    private fun startTrafficPolling() {
        if (trafficPollingJob?.isActive == true) return
        lastTrafficSample = null
        lastTrafficTime = 0L
        trafficPollingJob = activityScope.launch {
            while (Socks5ProxyService.isServiceRunning) {
                updateTrafficStats()
                renderClients()
                delay(1000)
            }
        }
    }

    private fun stopTrafficPolling() {
        trafficPollingJob?.cancel()
        trafficPollingJob = null
        lastTrafficSample = null
        lastTrafficTime = 0L
        resetTrafficLabels()
    }

    private fun resetTrafficLabels() {
        val na = getString(R.string.traffic_na)
        trafficUploadValue.text = na
        trafficDownloadValue.text = na
        trafficUploadTotal.text = na
        trafficDownloadTotal.text = na
        trafficCols?.visibility = View.GONE
        trafficEmptyText?.visibility = View.VISIBLE
        renderClients()
    }

    private fun updateTrafficStats() {
        val bytes = NativeEngine.safeGetTrafficBytes() ?: return
        trafficCols?.visibility = View.VISIBLE
        trafficEmptyText?.visibility = View.GONE
        val tx = bytes[0]
        val rx = bytes[1]
        val prev = lastTrafficSample
        val now = SystemClock.elapsedRealtime()

        // 引擎重啟會把計數器歸零；總數倒退代表發生 reset，此時只重設基準、
        // 不計算速率，避免顯示負值/突波。
        if (prev != null && (tx < prev[0] || rx < prev[1])) {
            lastTrafficSample = longArrayOf(tx, rx)
            lastTrafficTime = now
            trafficUploadValue.text = formatSpeed(0.0)
            trafficDownloadValue.text = formatSpeed(0.0)
            trafficUploadTotal.text = formatBytes(tx)
            trafficDownloadTotal.text = formatBytes(rx)
            return
        }

        var upRate = 0.0
        var downRate = 0.0
        if (prev != null && lastTrafficTime > 0) {
            val dtSec = (now - lastTrafficTime) / 1000.0
            if (dtSec > 0) {
                upRate = (tx - prev[0]) / dtSec
                downRate = (rx - prev[1]) / dtSec
            }
        }
        lastTrafficSample = longArrayOf(tx, rx)
        lastTrafficTime = now

        trafficUploadValue.text = formatSpeed(upRate)
        trafficDownloadValue.text = formatSpeed(downRate)
        trafficUploadTotal.text = formatBytes(tx)
        trafficDownloadTotal.text = formatBytes(rx)
    }

    private fun formatSpeed(bytesPerSec: Double): String {
        return when {
            bytesPerSec >= 1024 * 1024 -> String.format(Locale.US, "%.1f MB/s", bytesPerSec / (1024 * 1024))
            bytesPerSec >= 1024 -> String.format(Locale.US, "%.1f KB/s", bytesPerSec / 1024)
            else -> String.format(Locale.US, "%.0f B/s", bytesPerSec)
        }
    }

    private fun formatBytes(bytes: Long): String {
        return when {
            bytes >= 1024L * 1024 * 1024 -> String.format(Locale.US, "%.2f GB", bytes / (1024.0 * 1024 * 1024))
            bytes >= 1024L * 1024 -> String.format(Locale.US, "%.1f MB", bytes / (1024.0 * 1024))
            bytes >= 1024 -> String.format(Locale.US, "%.1f KB", bytes / 1024.0)
            else -> "$bytes B"
        }
    }

    private fun runSelfTest() {
        if (!Socks5ProxyService.isServiceRunning) {
            selfTestResultText.text = getString(R.string.self_test_not_running)
            selfTestResultText.setTextColor(0xFFDC3545.toInt())
            selfTestResultText.visibility = View.VISIBLE
            return
        }

        val port = portInput.text.toString().toIntOrNull() ?: 1080
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val authUser = prefs.getString("auth_user", "") ?: ""
        val authPass = prefs.getString("auth_pass", "") ?: ""

        selfTestButton.isEnabled = false
        selfTestButton.text = getString(R.string.self_test_running)
        selfTestResultText.visibility = View.VISIBLE
        selfTestResultText.text = ""

        activityScope.launch {
            val result = SelfTest.run(port, authUser, authPass)
            selfTestButton.isEnabled = true
            selfTestButton.text = getString(R.string.btn_self_test)

            val sb = StringBuilder()
            sb.append(getString(if (result.overallPass) R.string.self_test_pass else R.string.self_test_fail))
                .append("\n\n")
            for (step in result.steps) {
                val label = when (step.kind) {
                    SelfTest.StepKind.CONNECT_SOCKET -> getString(R.string.self_test_step_connect_socket)
                    SelfTest.StepKind.GREETING -> getString(R.string.self_test_step_greeting)
                    SelfTest.StepKind.AUTH -> getString(R.string.self_test_step_auth)
                    SelfTest.StepKind.CONNECT -> getString(R.string.self_test_step_connect)
                    SelfTest.StepKind.DATA -> getString(R.string.self_test_step_data)
                }
                sb.append(if (step.pass) "✅ " else "❌ ").append(label)
                    .append(": ").append(step.detail).append("\n")
            }
            selfTestResultText.text = sb.toString().trimEnd()
            selfTestResultText.setTextColor(if (result.overallPass) 0xFF28A745.toInt() else 0xFFDC3545.toInt())
        }
    }

    private fun copyDiagnostics() {
        val report = buildDiagnosticsReport()
        val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
        val clip = ClipData.newPlainText("Diagnostics", report)
        clipboard.setPrimaryClip(clip)
        Toast.makeText(this, getString(R.string.diagnostics_copied), Toast.LENGTH_SHORT).show()
    }

    /**
     * 匯出「App 內環形緩衝 + 本行程 logcat」到剪貼簿。
     *
     * 這是 `adb logcat -s PublicIPChecker CellularNetwork Socks5ProxyService` 的 App 內
     * 替代品：使用者不必裝 platform-tools，貼到 issue 就等於把 logcat 帶過來。
     * 組字串（含 logcat 子行程）放到 IO 執行緒，避免最長 2.5 秒的等待卡住 UI。
     */
    private fun copyLog() {
        activityScope.launch {
            val text = withContext(Dispatchers.IO) { LogExporter.build() }
            val clipboard = getSystemService(Context.CLIPBOARD_SERVICE) as ClipboardManager
            clipboard.setPrimaryClip(ClipData.newPlainText("5G Proxy Pro log", text))
            val lines = text.count { it == '\n' } + 1
            Toast.makeText(
                this@DebugActivity,
                getString(R.string.log_copied, lines),
                Toast.LENGTH_SHORT
            ).show()
            // 摘要寫進 logcat：萬一使用者說「複製了但貼出來是空的」，
            // 這一行能區分「匯出真的空」與「剪貼簿被系統攔掉」；
            // logcat_lines=-1 則代表這台 ROM 不讓 App 讀 logcat，只剩 AppLog 那份。
            AppLog.i(
                "LogExport",
                "copied $lines lines, ${text.length} chars, logcat_lines=${LogExporter.lastLogcatLines}"
            )
        }
    }

    private fun buildDiagnosticsReport(): String {
        val sb = StringBuilder()
        sb.append("===== ").append(getString(R.string.diagnostics_title)).append(" =====\n")
        sb.append("Time: ")
            .append(SimpleDateFormat("yyyy-MM-dd HH:mm:ss", Locale.getDefault()).format(Date()))
            .append("\n")
        try {
            sb.append("Version: ")
                .append(packageManager.getPackageInfo(packageName, 0).versionName ?: "unknown")
                .append("\n")
        } catch (e: Exception) {
            sb.append("Version: unknown\n")
        }

        val statusName = when (Socks5ProxyService.currentStatus) {
            Socks5ProxyService.ProxyStatus.STARTING -> "STARTING"
            Socks5ProxyService.ProxyStatus.RUNNING -> "RUNNING"
            Socks5ProxyService.ProxyStatus.RESTARTING -> "RESTARTING"
            Socks5ProxyService.ProxyStatus.PAUSED -> "PAUSED"
            Socks5ProxyService.ProxyStatus.STOPPED -> "STOPPED"
            Socks5ProxyService.ProxyStatus.FAILED -> "FAILED"
            Socks5ProxyService.ProxyStatus.WAITING_HOTSPOT -> "WAITING_HOTSPOT"
        }
        sb.append("Status: ").append(statusName).append("\n")
        sb.append("Restarts: ").append(Socks5ProxyService.restartCount).append("\n")
        Socks5ProxyService.lastHealthCheck?.let { sb.append("LastHealthCheck: ").append(it).append("\n") }
        // 健康探測失敗的「原因」：端點名稱 + 例外型別 + 耗時。只有 FAILED (3/3)
        // 分不出「三個端點都被地區封鎖」與「真的收不到位元組」（issue #8）。
        Socks5ProxyService.lastHealthProbeDetail?.let { sb.append("HealthProbe: ").append(it).append("\n") }
        Socks5ProxyService.lastErrorMessage?.let { sb.append("LastError: ").append(it).append("\n") }

        val port = portInput.text.toString().toIntOrNull() ?: 1080
        sb.append("Port: ").append(port).append("\n")
        val prefs = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
        val user = prefs.getString("auth_user", "") ?: ""
        val pass = prefs.getString("auth_pass", "") ?: ""
        val authOn = user.isNotEmpty() && pass.isNotEmpty()
        sb.append("Auth: ").append(
            if (authOn) "enabled (user=$user, pass=${mask(pass)})" else "disabled"
        ).append("\n")

        sb.append(wifiIPText.text).append("\n")
        sb.append(hotspotIPText.text).append("\n")
        sb.append(usbTetherIPText.text).append("\n")
        sb.append(cellularIPText.text).append("\n")
        // 出口探測摘要：位址 / 走哪一層（STUN 或 echo）/ 耗時。issue #8 這類
        // 「IP 顯示 Failed」的報告，一眼就能看出是哪一層掛掉、花了多久。
        Socks5ProxyService.instance?.currentProbeSummary()?.let {
            sb.append("EgressProbe: ").append(it).append("\n")
        }

        sb.append("NativeStats: ").append(NativeEngine.safeGetStats()).append("\n")
        // [非同步解析] UDP 網域解析計數。drop/qfull/jobfull/lost 只要非 0，
        // 就代表有 frame 沒被送出（上層只能重傳）—— 這是「驗證頁卡住」的量化證據。
        sb.append("DnsStats: ").append(NativeEngine.safeGetDnsStats()).append("\n")
        // DnsStats 只統計 native 的 UDP 網域佇列；API 29+ 走 DnsResolver 時它永遠是 0。
        // 沒有這一行，讀報告的人會把全 0 讀成「DNS 沒跑過」，而實際上每個 CONNECT 都查了。
        Socks5ProxyService.instance?.let {
            sb.append("DnsPath: ").append(it.currentDnsPath()).append("\n")
        }

        // 附上滾動落檔的歷史痕跡（release 版可直接看，不需 root / debug 版）
        try {
            val logFile = java.io.File(filesDir, "engine_stats.log")
            if (logFile.exists()) {
                val content = logFile.readText()
                if (content.isNotBlank()) {
                    sb.append("\n----- engine_stats.log (tail) -----\n")
                    sb.append(content.takeLast(4096))
                    sb.append("\n")
                }
            }
        } catch (e: Exception) {
            sb.append("\nengine_stats.log: unreadable (").append(e.message).append(")\n")
        }

        // [回報用] 附上最近 200 行 App 內日誌。使用者按「複製診斷報告」時，
        // 失敗原因（探測端點、健康狀態、自檢步驟）會跟著一起過來，不必再多按一次。
        // 只取 tail：完整日誌另外由「複製日誌」提供，避免這段無限膨脹。
        val logTail = AppLog.tail(200)
        if (logTail.isNotBlank()) {
            sb.append("\n----- app log (tail 200) -----\n")
            sb.append(logTail).append("\n")
        }

        return sb.toString()
    }

    private fun mask(s: String): String =
        if (s.length <= 2) "*" else s.take(1) + "*".repeat(s.length - 2) + s.takeLast(1)

    private fun updateNetworkStatus() {
        networkStatusJob?.cancel()
        networkStatusJob = activityScope.launch {
            try {
                val wifiManager = applicationContext.getSystemService(WIFI_SERVICE) as WifiManager
                val ip = wifiManager.connectionInfo.ipAddress
                if (ip != 0) {
                    val ipString = String.format("%d.%d.%d.%d", (ip and 0xff), (ip shr 8 and 0xff), (ip shr 16 and 0xff), (ip shr 24 and 0xff))
                    val port = portInput.text.toString().toIntOrNull() ?: 1080
                    wifiIPText.text = getString(R.string.wifi_proxy_format, ipString, port)
                    netWifiIp = ipString
                } else {
                    wifiIPText.text = getString(R.string.wifi_not_connected)
                    netWifiIp = null
                }
            } catch (e: Exception) {
                wifiIPText.text = getString(R.string.wifi_fetch_failed)
                netWifiIp = null
            }

            // 以下區段先前沒有 try/catch：CellularNetworkManager 的 ConnectivityManager
            // 轉型失敗或網路查詢拋例外時會直接冒到 activityScope。這裡整段包起來。
            try {
                val hotspotIP = withContext(Dispatchers.IO) {
                    HotspotManager.getHotspotIP(applicationContext)
                }
                if (hotspotIP != null) {
                    val port = portInput.text.toString().toIntOrNull() ?: 1080
                    hotspotIPText.text = getString(R.string.hotspot_ip_format, hotspotIP, port)
                } else {
                    hotspotIPText.text = getString(R.string.hotspot_ip_disabled)
                }
                netHotspotIp = hotspotIP

                val usbTetherIP = withContext(Dispatchers.IO) {
                    HotspotManager.getUsbTetherIP(applicationContext)
                }
                if (usbTetherIP != null) {
                    val port = portInput.text.toString().toIntOrNull() ?: 1080
                    usbTetherIPText.text = getString(R.string.usb_tether_ip_format, usbTetherIP, port)
                } else {
                    usbTetherIPText.text = getString(R.string.usb_tether_ip_disabled)
                }
                netUsbIp = usbTetherIP
                netLoaded = true
                renderConnectInfo()

                cellularIPText.text = getString(R.string.cellular_ip_fetching)
                val cellularNetwork = withContext(Dispatchers.IO) {
                    networkManager.requestCellularNetwork(5000)
                }

                if (cellularNetwork != null) {
                    // 三層探測（本機 IPv6 → STUN → HTTPS echo），失敗時 detail 會進 AppLog
                    val result = ipChecker.probe(cellularNetwork)
                    cellularIPText.text = getString(
                        R.string.cellular_ip_format,
                        result.ip ?: getString(R.string.cellular_ip_failed)
                    )
                    netCellIp = result.ip ?: getString(R.string.net_failed)
                } else {
                    cellularIPText.text = getString(R.string.cellular_ip_not_locked)
                    netCellIp = getString(R.string.net_failed)
                }
                renderConnectInfo()
            } catch (e: Exception) {
                Log.e("DebugActivity", "updateNetworkStatus failed", e)
            }
        }
    }


}