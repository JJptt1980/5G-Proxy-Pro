package com.tokyoxpa3.androidproxy

import android.content.Context
import android.content.Intent
import android.os.Build
import android.os.Handler
import android.os.Looper
import android.service.quicksettings.Tile
import android.service.quicksettings.TileService
import android.util.Log

/**
 * 快速設定方塊「5G Pro」（只有 GW 版）：點一下開、再點一下關，亮起＝運行中。
 * 啟動用上次的埠號（proxy_port），跟開機自動啟動一樣。
 */
class ProTileService : TileService() {
    private val handler = Handler(Looper.getMainLooper())

    override fun onStartListening() {
        super.onStartListening()
        updateTile()
    }

    override fun onClick() {
        super.onClick()
        val intent = Intent(this, Socks5ProxyService::class.java)
        try {
            if (Socks5ProxyService.isServiceRunning) {
                Log.i(TAG, "Tile: stop proxy")
                startService(intent.setAction(Socks5ProxyService.ACTION_STOP_PROXY))
            } else {
                val port = getSharedPreferences("proxy_config", Context.MODE_PRIVATE)
                    .getString("proxy_port", "1080")?.toIntOrNull() ?: 1080
                Log.i(TAG, "Tile: start proxy port=$port")
                startForegroundService(
                    intent.setAction(Socks5ProxyService.ACTION_START_PROXY)
                        .putExtra(Socks5ProxyService.EXTRA_PORT, port)
                )
            }
        } catch (e: Exception) {
            Log.e(TAG, "Tile: toggle failed: ${e.message}")
        }
        // 服務啟停要一點時間，晚一下再更新方塊狀態。
        handler.postDelayed({ updateTile() }, 800)
        handler.postDelayed({ updateTile() }, 2500)
    }

    private fun updateTile() {
        val tile = qsTile ?: return
        val on = Socks5ProxyService.isServiceRunning
        tile.state = if (on) Tile.STATE_ACTIVE else Tile.STATE_INACTIVE
        tile.label = "5G Pro"
        if (Build.VERSION.SDK_INT >= 29) tile.subtitle = if (on) "運行中" else "已停止"
        tile.updateTile()
    }

    companion object {
        private const val TAG = "ProTile"
    }
}
