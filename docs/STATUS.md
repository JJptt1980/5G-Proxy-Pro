# 狀態（GW fork）

## 目前進度
- 分支 `gw`（從上游 cff797e 開出）：主畫面改版（狀態卡、藍色啟動／紅色停止、Client 連線資訊卡、進階設定與診斷收合）、App 圖示加「GW」標籤。
- `./gradlew assembleDebug testDebugUnitTest`（JDK 17）通過；模擬器上看過未啟動、運行中、兩個收合區塊展開的畫面。
- 首頁一頁放得下（X200 Pro 實機確認）：並發提示收進進階設定、刷新狀態收進診斷、進階設定和診斷並排一行、IP 與流量字縮小。
- 修正 16 KB 分頁警告：CMake 加 `-Wl,-z,max-page-size=16384`（LOAD 對齊 0x4000），build.gradle 改 `useLegacyPackaging true`（.so 壓縮打包）。X200 Pro 實機確認不再跳警告。
- 熱點連動改成「勾著就一直等熱點」（2026-11-23）：勾起開關馬上開始（不用先按啟動）；開機或 App 更新後自動回來（BootReceiver 在連動開著時視同開機自動啟動）；按「停止」或在等待中取消勾選才完全結束。X200 Pro 實測：取消再勾→代理立即啟動；重裝 APK→MY_PACKAGE_REPLACED 自動恢復運作。
- 首頁固定一頁（2026-11-23，/ui 12 點全做）：拿掉標題列；狀態卡 → 連線資訊（大字只顯示 Client 要填的 IP:埠，優先順序熱點 > USB > Wi-Fi，其他網路縮成一行）→ 流量（沒流量顯示「還沒有流量」、等寬數字、單一字色）→ [設定][診斷] → 主按鈕在最下面（按下微縮＋震動）。進階設定、診斷改成從下往上滑出的面板（點外面、按完成、往下拉標題都會收起）。拿掉 emoji 圖示；回到前景自動刷新連線資訊。只有字體很大放不下時才可捲動。X200 Pro 實機截圖確認首頁、兩個面板。
- 跟熱點連動（2026-11-23）：進階設定的開關，熱點開→自動啟動代理，熱點關→停代理、留通知等下一次（新狀態 WAITING_HOTSPOT）。X200 Pro 實機確認：開熱點 4 秒內自動啟動，關熱點後回到「等待熱點」、通知還在。
- 誰在用（2026-11-24）：C 端 accept 時記下來源 IP 與時間（最多 16 台），JNI `getClients()` 給 Java；服務每 3 秒看一次，新裝置開始用（或閒置 10 分鐘後再用）就跳「有裝置連上 5G 代理」通知（獨立通知類別「有人連上」）。首頁加「誰在用（N）」卡片：綠點＝使用中（60 秒內有新連線）、灰點＋幾分鐘前，30 分鐘沒動靜移除，最多列 3 台；有人使用中時整張卡變淡綠底＋綠框。點一台可取名字（從下滑出的面板）。X200 Pro 實測：V2126 連上時通知有跳。
- VPN 退路（2026-11-24）：Tailscale 這類不可略過的 VPN 會讓綁 5G 出口 EPERM。只要 VPN 沒接管預設路由（split tunnel），改成不綁網路、走系統預設路由（Wi-Fi 沒開時就是 5G），DNS 也改走預設網路；綁得回來就自動恢復。啟動檢查和健康檢查都認這個退路。X200 Pro 開著 Tailscale 實測：Pro 啟動成功、V2126 透過 Pro 正常上網。
- 閒置放掉喚醒鎖（2026-11-24）：代理在跑時每 3 秒看流量，連續 5 分鐘沒流量就放掉喚醒鎖（IDLE_RELEASE_MS），一有流量就拿回來。X200 Pro 實測（螢幕關、接 USB）：16:23 啟動拿鎖，之後 16:30、16:33 有流量又重新拿回（代表中間有放掉）；17:52 V2126 在放鎖狀態下連回熱點，Pro 17:52:40 自動拿回、Client 17:52:46 隧道已啟用、可上網。
- 誰在用改用 IP 最後一段認裝置（2026-11-24，方案 A）：熱點重開會換網段，但同一台通常拿到同一個最後一段，名字改存成 host:最後一段；舊的完整 IP 鑰匙第一次讀取時自動轉換。X200 Pro 實測：舊名字 10.63.154.76 自動轉成 host:76，V2126 在 10.195.54.76 直接顯示「V2126 V23e」。
- 誰在用：Client GW 報名（2026-11-24，方案 B）：Client 探測時 CONNECT「<8碼ID>.<名字hex>.gw-id.invalid」，原生端認得這個後綴就記在該 IP 的 tag、回成功、不連外；getClients 改成「ip,秒數,tag」。Java 端有 ID 就用 id:<ID> 當鑰匙，沒取名時顯示報上的名字；第一次用 ID 時沿用以前用 IP 最後一段存的名字。單元測試通過。X200 Pro＋V2126 實測：V2126 報上 ID ea86c8dc，Pro 把舊的 host:76 名字自動轉成 id:ea86c8dc，清單顯示「V2126 V23e」。
- 誰在用：看網域猜品牌（2026-11-24，方案 D）：原生端在 CONNECT 網域時比對系統網域對照表（vivo／Apple／Windows／Samsung／小米／OPPO／華為），每台只累計各品牌次數、不存網域；getClients 第 4 欄回次數最多的品牌代碼。沒取名、也沒報名字時顯示「vivo 手機（猜的）」這類預設名稱。C 與 Java 單元測試通過、已裝 X200 Pro；熱點上實機測試沒做（V2126 跳回家裡 Wi-Fi，使用者決定先不測）。限制：Client GW 預設送 IP 不送網域，所以主要對直接設 SOCKS5 代理的裝置有效。
- 2026-11-21 已裝到 X200 Pro（先解除安裝原版 1.6.3，設定已清空）。

## 已定案的決定
- vivo 要在「設定 → 自動啟動」把 5G Proxy Pro 打開，否則開機、App 更新後的廣播會被 vivo 擋掉（系統廣播記錄 dispatchTime=--，BootReceiver 不會被叫起來），Pro 不會自動恢復。2026-11-24 X200 Pro 已打開，實測更新後 1 秒內自動啟動。設定頁可用 adb 開：`am start -n com.vivo.permissionmanager/.activity.BgStartUpManagerActivity`。
- 保留原 LICENSE（MIT，版權屬原作者），說明寫在 README-GW.md。
- 熱點連動等待時只靠前台服務＋常駐通知保活，不持有 wakelock（省電）；Pro 不能自己開熱點（Android 8+ 限制）。
- 套件名稱不加 `.gw`：Client GW 的同機共存靠 `com.tokyoxpa3.androidproxy` 偵測 Pro。代價是要先解除安裝原版才能裝。
- commit 使用 JJptt1980 的 GitHub noreply 信箱。
- 不對上游開 PR，只維護自己的 fork（比照 Client）。

## 遠端
- `origin` = https://github.com/JJptt1980/5G-Proxy-Pro （fork，預設分支 `gw`）
- `upstream` = https://github.com/tokyoxpa3/5G-Proxy-Pro （原專案）

## 下一步
- 「誰在用」自動認裝置，依序做：（A 已完成）→ B（已完成） Client 打招呼時報上 ID → D 看網域猜品牌（只存結論）→ E 實驗降 targetSdk 用 netlink 讀 MAC（試不出來就放棄）。
- 實機測試：X200 Pro 開熱點跑 Pro GW，平板用 Client GW 填 gateway 連線。

## 已知問題
- 閒置放鎖只在接著 USB 的情況測過；拔掉電源、長時間深度休眠時，Client 連進來能不能叫醒 Pro 還沒測。
- 熱點連動在螢幕關閉、長時間等待下會不會被 vivo 殺掉，還沒實測。
- 「誰在用」原本想用 MAC 認裝置：adb run-as 讀得到 /proc/net/arp，但 App 實際執行時讀不到（mac=null），所以目前用 IP 最後一段認裝置；兩台撞號，或號碼被別台先拿走時會認錯；熱點和 USB 網路共享的裝置號碼相同時也會混在一起。
- DnsCacheTest.singleFlightDedupsConcurrentLookups 偶爾失敗（並發時機），重跑會過。
- VPN 退路時若 X200 Pro 同時連著 Wi-Fi，流量會走 Wi-Fi 而不是 5G。
