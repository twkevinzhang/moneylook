# Moneylook

一個 Android 存摺聚合 App，讓你在同一個畫面查看所有銀行帳戶餘額。

## 功能

- **Dashboard**：一覽所有已安裝銀行的帳戶餘額，支援同時同步多家銀行
- **Marketplace**：從 GitHub repo 安裝銀行 extension，支援更新與移除
- **自動登入**：App 原生保存網銀帳密，依使用者排程登入、辨識圖片驗證碼並同步
- **暫態 Session**：Cookie 只存在單次同步記憶體中，由 HTTP 代理注入，不持久化也不暴露給擴充腳本
- **沙盒執行**：銀行爬蟲腳本在 Webview 沙盒內執行，無法存取裝置本地資源

## 運作原理

每家銀行對應一個 **Extension**，由社群開發者維護。App 依 manifest 中的宣告式 selector 在原生 WebView 完成登入，再將當次 Cookie 交給受控 HTTP 代理。Extension 腳本只能透過 `sdk.http.get/post` 呼叫允許的 HTTPS 網域，無法取得帳密或 Cookie 值。

```
Native login + captcha OCR
  └─ ephemeral cookies (memory only)
       └─ Extension script (JS)
  └─ sdk.http.get(url)
       └─ HttpBridge (Kotlin, Dispatchers.IO)
            ├─ 驗證 HTTPS／網域／Header 後注入 Cookie
            └─ OkHttp → 銀行 API
```

## 安裝 Extension

1. 開啟 App，點選右下角 **+** 進入 Marketplace
2. 貼上 Extension 來源的 GitHub repo URL（例如 `https://github.com/twkevinzhang/moneylook-extensions`）
3. 點選「新增」，成功後即可看到可安裝的 Extension 清單
4. 點選「安裝」

安裝後回到首頁，檢查登入／代理網域並設定網銀帳密與排程。儲存時會記錄使用者核准的網域；擴充更新若變更網域，必須重新確認。每次手動或排程同步都會重新登入，不會復用上次 Cookie。

> 擴充為未受官方背書的外部程式。它無法讀取帳密或 Cookie，但取得暫態登入能力後仍可對銀行允許網域發出請求；請只安裝你信任且已檢視來源的擴充。

## 開發 Extension

請參考 [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source)。

Extension 以 TypeScript 撰寫，型別定義於 `sdk.d.ts`，使用 esbuild 打包後部署至 distribution repo。

```typescript
// extensions/my-bank/src/index.ts
(function(): ExtensionResult {
    
  // 腳本中一切的 http request 只能使用 sdk.http 呼叫，無法使用其他第三方 request lib，例如 axios/fetch 等。
  const res = sdk.http.get('https://mybank.com/api/accounts') 
  
  const data = JSON.parse(res.body)
  return {
    accounts: data.accounts.map((a: any) => ({
      name: a.accountName,
      balance: a.balance,
      currency: 'TWD',
    }))
  }
})()
```

`manifest.json` 範例：

```json5
{
  "id": "tw.mybank",
  "name": "My Bank",
  "version": 1,
  "versionName": "1.0.0",
  "loginUrl": "https://mybank.com/login",
  "loginAutomation": {
    "usernameSelector": "#username",
    "passwordSelector": "#password",
    "captchaImageSelector": "#captcha-image",
    "captchaInputSelector": "#captcha",
    "submitSelector": "button[type=submit]",
    "successUrlContains": "/accounts",
    "postSubmitDelayMs": 500
  },
  "targetDomains": ["mybank.com"], // 腳本只能呼叫這些網域。
  "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
  "schedule": {
    "suggestedCron": "0 8 * * *",
    "suggestedTimezone": "Asia/Taipei"
  },
  "iconUrl": null
}
```

## 建置

需要 Android Studio Meerkat 或以上，Android SDK 36。

```bash
# 放在被 Git 忽略的 local.properties，或由 CI 環境變數提供；請勿提交實際網址。
MONEYLOOK_OCR_BASE_URL=https://your-ocr-service.example

./gradlew assembleDebug
```

## 模組結構

```
:app                  — UI 層（Compose、ViewModel、Navigation）
:marketplace          — Extension 清單、下載、repo URL 管理
:extension-runtime    — 原生登入、CaptchaSolver、WebView 執行器、暫態 HttpBridge
:core:data            — Room 資料庫（Account、InstalledExtension）
:core:network         — OkHttp、Gson DI 模組
```

## 官方 Extension Repo

| Repo | 說明 |
|---|---|
| [moneylook-extensions](https://github.com/twkevinzhang/moneylook-extensions) | 已編譯的 Extension 腳本（Distribution） |
| [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source) | TypeScript 原始碼與 CI/CD 工具鏈 |
