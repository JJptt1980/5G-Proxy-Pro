# 狀態（GW fork）

## 目前進度
- 分支 `gw`（從上游 cff797e 開出）：主畫面改版（狀態卡、藍色啟動／紅色停止、Client 連線資訊卡、進階設定與診斷收合）、App 圖示加「GW」標籤。
- `./gradlew assembleDebug testDebugUnitTest`（JDK 17）通過；模擬器上看過未啟動、運行中、兩個收合區塊展開的畫面。
- 首頁一頁放得下（X200 Pro 實機確認）：並發提示收進進階設定、刷新狀態收進診斷、進階設定和診斷並排一行、IP 與流量字縮小。
- 修正 16 KB 分頁警告：CMake 加 `-Wl,-z,max-page-size=16384`（LOAD 對齊 0x4000），build.gradle 改 `useLegacyPackaging true`（.so 壓縮打包）。X200 Pro 實機確認不再跳警告。
- 跟熱點連動（2026-11-23）：進階設定的開關，熱點開→自動啟動代理，熱點關→停代理、留通知等下一次（新狀態 WAITING_HOTSPOT）。X200 Pro 實機確認：開熱點 4 秒內自動啟動，關熱點後回到「等待熱點」、通知還在。
- 2026-11-21 已裝到 X200 Pro（先解除安裝原版 1.6.3，設定已清空）。

## 已定案的決定
- 保留原 LICENSE（MIT，版權屬原作者），說明寫在 README-GW.md。
- 熱點連動等待時只靠前台服務＋常駐通知保活，不持有 wakelock（省電）；Pro 不能自己開熱點（Android 8+ 限制）。
- 套件名稱不加 `.gw`：Client GW 的同機共存靠 `com.tokyoxpa3.androidproxy` 偵測 Pro。代價是要先解除安裝原版才能裝。
- commit 使用 JJptt1980 的 GitHub noreply 信箱。
- 不對上游開 PR，只維護自己的 fork（比照 Client）。

## 遠端
- `origin` = https://github.com/JJptt1980/5G-Proxy-Pro （fork，預設分支 `gw`）
- `upstream` = https://github.com/tokyoxpa3/5G-Proxy-Pro （原專案）

## 下一步
- 實機測試：X200 Pro 開熱點跑 Pro GW，平板用 Client GW 填 gateway 連線。

## 已知問題
- 熱點連動在螢幕關閉、長時間等待下會不會被 vivo 殺掉，還沒實測。
