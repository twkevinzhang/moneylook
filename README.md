# Moneylook

一個 Android 存摺聚合 App，讓你在同一個畫面查看所有銀行帳戶餘額。

## 功能

- **Dashboard**：一覽所有已安裝銀行的帳戶餘額，支援同時同步多家銀行
- **Marketplace**：從 GitHub repo 安裝銀行 extension，支援使用者自主更新與移除
- **帳密與排程**：App 在私有 Room 資料庫明碼保存網銀帳密，並以 WorkManager 啟動同步
- **Extension 執行環境**：Extension 自行負責登入、驗證碼、Cookie/session 與資料爬取
- **Native HTTP bridge**：提供不受 WebView CORS 限制的任意 HTTP request 能力

## 信任模型與運作原理

每家銀行對應一個由社群開發者維護的 **Extension**。Extension 是完全受信任程式碼；每次執行都可透過 `sdk.credentials` 讀取使用者帳號與密碼明碼，並可透過 `sdk.http.request` 向任意網址發出 request。Bridge 不限制 domain、method、header 或 redirect，也不阻止 extension 將帳密外傳。

Kotlin App 在登入與爬蟲 domain 僅負責持久化帳密；登入流程、驗證碼辨識、Cookie/session 管理與銀行資料解析都由 extension 實作。同步完成後，App 仍負責保存 extension 回傳的 accounts、transfers 與執行狀態。

```text
Room plaintext credentials
  └─ Extension script
       ├─ frozen sdk.credentials { username, password }
       ├─ await sdk.http.request(...) → native bridge → any destination
       └─ { accounts: [...] }
            └─ Kotlin persists accounts / transfers / last-run status
```

> **重要風險：** Extension 能讀取並向任意第三方傳送你的網銀帳密與其他資料。請只安裝、執行及更新你已檢視且完全信任的程式碼。Moneylook 不會替 extension 限制網路目的地或判斷 request 的業務意圖。

## 安裝 Extension

1. 開啟 App，點選右下角 **+** 進入 Marketplace
2. 貼上 Extension 來源的 GitHub repo URL（例如 `https://github.com/twkevinzhang/moneylook-extensions`）
3. 點選「新增」，成功後即可看到可安裝的 Extension 清單
4. 檢視並信任來源後點選「安裝」
5. 回到首頁設定網銀帳密與排程

使用者自主下載 extension 更新，即代表同意更新後的程式碼繼續取得既有帳密；更新不會要求重新輸入或再次核准帳密。

## 開發 Extension

請參考 [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source)。Extension 以 TypeScript 撰寫，型別定義於 `sdk.d.ts`，使用 esbuild 打包後部署至 distribution repo。

同步腳本是 async top-level IIFE。`sdk.credentials` 是唯讀且 frozen 的執行期物件；`sdk.http.request` 是 async API，透過 Kotlin native bridge 執行，不受 CORS 限制。

```typescript
// extensions/my-bank/src/index.ts
(async (): Promise<ExtensionResult> => {
  const login = await sdk.http.request({
    method: 'POST',
    url: 'https://mybank.com/api/login',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      username: sdk.credentials.username,
      password: sdk.credentials.password,
    }),
  })

  const accounts = await sdk.http.request({
    method: 'GET',
    url: 'https://mybank.com/api/accounts',
    headers: { Authorization: `Bearer ${JSON.parse(login.body).token}` },
  })
  const data = JSON.parse(accounts.body)

  return {
    accounts: data.accounts.map((account: any) => ({
      name: account.accountName,
      balance: account.balance,
      currency: 'TWD',
    })),
  }
})()
```

`manifest.json` 不宣告 login URL、selector 或允許網域；這些責任全部在腳本中：

```json5
{
  "id": "tw.mybank",
  "name": "My Bank",
  "version": 1,
  "versionName": "1.0.0",
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
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## 模組結構

```text
:app                  — Compose UI、帳密／同步結果持久化與 WorkManager 排程
:marketplace          — Extension 清單、下載與 repo URL 管理
:extension-runtime    — WebView 執行器與 unrestricted native HTTP bridge
:core:data            — Room 資料庫（Account、Transfer、InstalledExtension、CredentialProfile）
:core:network         — OkHttp、Gson DI 模組
```

## 官方 Extension Repo

| Repo | 說明 |
|---|---|
| [moneylook-extensions](https://github.com/twkevinzhang/moneylook-extensions) | 已編譯的 Extension 腳本（Distribution） |
| [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source) | TypeScript 原始碼與 CI/CD 工具鏈 |
