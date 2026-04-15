# Moneylook

一個 Android 存摺聚合 App，讓你在同一個畫面查看所有銀行帳戶餘額。

## 功能

- **Dashboard**：一覽所有已安裝銀行的帳戶餘額，支援同時同步多家銀行
- **Marketplace**：從 GitHub repo 安裝銀行 extension，支援更新與移除
- **安全登入**：內建 WebView 登入流程，自動擷取 Cookie/OAuth Token，不儲存明文密碼
- **沙盒執行**：銀行爬蟲腳本在 Webview 沙盒內執行，無法存取裝置本地資源

## 運作原理

每家銀行對應一個 **Extension**，由社群開發者維護。Extension 是一段 TypeScript 腳本，透過 `sdk.http.get/post` 呼叫銀行 API，App 在執行時自動注入登入 session，最終回傳帳戶名稱、餘額、幣別。

```
Extension script (JS)
  └─ sdk.http.get(url)
       └─ HttpBridge (Kotlin, Dispatchers.IO)
            ├─ 自動注入 Cookie / Token
            └─ OkHttp → 銀行 API
```

## 安裝 Extension

1. 開啟 App，點選右下角 **+** 進入 Marketplace
2. 貼上 Extension 來源的 GitHub repo URL（例如 `https://github.com/twkevinzhang/moneylook-extensions`）
3. 點選「新增」，成功後即可看到可安裝的 Extension 清單
4. 點選「安裝」

安裝後回到首頁，點選對應銀行的「登入」按鈕完成登入，之後即可點選右上角同步按鈕更新餘額。

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
  "targetDomains": ["mybank.com"], // 腳本只能呼叫這些網域。
  "iconUrl": null
}
```

## 建置

需要 Android Studio Meerkat 或以上，Android SDK 36。

```bash
./gradlew assembleDebug
```

## 模組結構

```
:app                  — UI 層（Compose、ViewModel、Navigation）
:marketplace          — Extension 清單、下載、repo URL 管理
:extension-runtime    — Webview 執行器、HttpBridge、SessionStore
:core:data            — Room 資料庫（Account、InstalledExtension）
:core:network         — OkHttp、Gson DI 模組
```

## 官方 Extension Repo

| Repo | 說明 |
|---|---|
| [moneylook-extensions](https://github.com/twkevinzhang/moneylook-extensions) | 已編譯的 Extension 腳本（Distribution） |
| [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source) | TypeScript 原始碼與 CI/CD 工具鏈 |
