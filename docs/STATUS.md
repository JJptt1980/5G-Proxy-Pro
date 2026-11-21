# 狀態（GW fork）

## 目前進度
- 分支 `gw`（從上游 cff797e 開出）：主畫面改版（狀態卡、藍色啟動／紅色停止、Client 連線資訊卡、進階設定與診斷收合）、App 圖示加「GW」標籤。
- `./gradlew assembleDebug testDebugUnitTest`（JDK 17）通過；模擬器上看過未啟動、運行中、兩個收合區塊展開的畫面。

## 已定案的決定
- 保留原 LICENSE（MIT，版權屬原作者），說明寫在 README-GW.md。
- 套件名稱不加 `.gw`：Client GW 的同機共存靠 `com.tokyoxpa3.androidproxy` 偵測 Pro。代價是要先解除安裝原版才能裝。
- commit 使用 JJptt1980 的 GitHub noreply 信箱。
- 不對上游開 PR，只維護自己的 fork（比照 Client）。

## 遠端
- `origin` = https://github.com/tokyoxpa3/5G-Proxy-Pro （原專案；fork 之後會改名為 upstream）

## 下一步
- fork 到 JJptt1980 並推送 `gw`，fork 預設分支設為 `gw`（等使用者確認）。
- 實機安裝（要先解除安裝原版 Pro）。

## 已知問題
- 無。
