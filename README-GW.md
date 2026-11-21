# 5G Proxy Pro GW（非官方修改版）

> 這是 [tokyoxpa3/5G-Proxy-Pro](https://github.com/tokyoxpa3/5G-Proxy-Pro) 的 **非官方 fork**，
> 不是原作者發布的版本，有問題請不要回報給原專案。

## 授權與版權

- 原專案採用 **MIT 授權**，版權屬於原作者 tokyoxpa3。
- 本 fork 保留原本的 [LICENSE](LICENSE) 檔案，內容未做任何修改。
- 本 fork 的修改部分同樣以 MIT 授權釋出。

## 搭配

和 [5G Proxy Client GW](https://github.com/JJptt1980/5G-Proxy-Client) 一起用：
這支手機開 Wi-Fi 熱點並跑 5G Proxy Pro GW，平板連熱點後在 Client GW 的伺服器填 `gateway`，
就不用管熱點 IP 變成多少。

## 改了哪些東西

只改外觀，不動代理引擎和連線邏輯。

| 項目 | 修改內容 |
| --- | --- |
| 主畫面（`DebugActivity.kt`） | 狀態卡（灰＝未啟動、黃＝啟動中／等待網路、綠＝運行中、紅＝錯誤）；啟動／停止改成大的藍色／紅色按鈕；新增「Client 連線資訊」卡，熱點 IP 放大並提示 Client 填 `gateway`；連接埠、帳密、開機自動啟動收進「進階設定」；自我檢測、複製報告、複製日誌收進「診斷」；配色跟 Client GW 一致，依系統深淺色切換 |
| 字串（`values*/strings.xml`） | 新增狀態標題、區塊標題、gateway 提示；啟動／停止按鈕拿掉表情符號 |
| App 圖示（`mipmap-*/ic_launcher_preview.png`） | 原圖左上角加上橘色「GW」標籤 |
| `gradlew` | 加上執行權限 |

**套件名稱沒有改**（仍是 `com.tokyoxpa3.androidproxy`），Client 的「同機共存」功能靠這個名稱偵測 Pro。
因為簽章不同，安裝前要先解除安裝原版 5G Proxy Pro。

## 編譯

需要 JDK 17、Android SDK、NDK 26.3.11579264、CMake 3.22.1。

```bash
export JAVA_HOME="$(brew --prefix openjdk@17)/libexec/openjdk.jdk/Contents/Home" && ./gradlew assembleDebug
```

產出的 APK 在 `app/build/outputs/apk/debug/app-debug.apk`。
