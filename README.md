# Moneylook

一個 Android 存摺聚合 App，讓你在同一個畫面查看所有銀行帳戶餘額。

## 功能

- **Dashboard**：一覽所有已安裝銀行的帳戶餘額，支援同時同步多家銀行
- **Marketplace**：從 GitHub repo 安裝銀行 extension，支援使用者自主更新與移除
- **登入資料與排程**：App 依各 Extension 宣告的欄位顯示表單，在私有 Room 資料庫以明碼 JSON 保存，並以 WorkManager 啟動同步
- **Extension 執行環境**：Extension 自行負責登入、驗證碼、Cookie/session 與資料爬取
- **Native HTTP bridge**：提供不受 WebView CORS 限制的任意 HTTP request 能力

## 信任模型與運作原理

每家銀行對應一個由社群開發者維護的 **Extension**。Extension 是完全受信任程式碼；每次執行都可透過 `sdk.credential` 讀取該 Extension 定義的完整登入資料明碼，並可透過 `sdk.http.request` 向任意網址發出 request。Bridge 不限制 domain、method、header 或 redirect，也不阻止 extension 將登入資料外傳。

Kotlin App 在登入與爬蟲 domain 僅負責依 manifest 顯示泛用表單、持久化 credential JSON，以及啟動 Extension；登入流程、驗證碼辨識、Cookie/session 管理與銀行資料解析都由 Extension 實作。同步完成後，App 仍負責保存 Extension 回傳的 accounts、transfers 與執行狀態。

```text
Room plaintext credential JSON
  └─ Extension script
       ├─ deeply frozen sdk.credential { extension-defined string fields }
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
5. 回到首頁依 Extension 提供的欄位設定登入資料與排程

使用者自主下載 Extension 更新，即代表同意更新後的程式碼繼續取得既有登入資料；更新不會要求重新輸入或再次核准。舊版固定 `username`／`password` 資料會在資料庫升級時無損轉成同名 JSON 欄位。

## 開發 Extension

請參考 [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source)。Extension 以 TypeScript 撰寫，型別定義於 `sdk.d.ts`，使用 esbuild 打包後部署至 distribution repo。

同步腳本是 async top-level IIFE。`sdk.credential` 是依 manifest 欄位保存、遞迴 frozen 的執行期物件；v1 credential schema 是 flat JSON object，所有 value 都必須是 string。`sdk.http.request` 是 async API，透過 Kotlin native bridge 執行，不受 CORS 限制。

```typescript
// extensions/my-bank/src/index.ts
(async (): Promise<ExtensionResult> => {
  const login = await sdk.http.request({
    method: 'POST',
    url: 'https://mybank.com/api/login',
    headers: { 'Content-Type': 'application/json' },
    body: JSON.stringify({
      customerId: sdk.credential.customerId,
      userId: sdk.credential.userId,
      password: sdk.credential.password,
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
  "description": "My Bank 帳戶與交易查詢",
  "credential": {
    "fields": [
      { "key": "customerId", "label": "身分證字號／統編", "type": "text", "required": true, "summary": true },
      { "key": "userId", "label": "使用者代號", "type": "text", "required": true, "summary": true },
      { "key": "password", "label": "網銀密碼", "type": "password", "required": true, "summary": false }
    ]
  },
  "syncTrigger": { "scriptPath": "sync-trigger.min.js" },
  "schedule": {
    "suggestedCron": "0 8 * * *",
    "suggestedTimezone": "Asia/Taipei"
  },
  "iconUrl": null
}
```

`credential.fields` 的 `key` 必須符合 `[A-Za-z][A-Za-z0-9_]{0,63}`，最多 16 欄；`type` 只能是 `text` 或 `password`。`summary: true` 只可用於非密碼欄位，App 不會把 password value 發布到首頁狀態。編輯時 password 欄留空代表保留既有同名 key 的值。

## 建置

需要 Android Studio Meerkat 或以上，Android SDK 36。

```bash
./gradlew assembleDebug
./gradlew testDebugUnitTest
```

## 模組結構

```text
:app                  — Compose 泛用 credential UI、同步結果持久化與 WorkManager 排程
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
