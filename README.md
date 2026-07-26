# Moneylook

一個 Android 存摺聚合 App，讓你在同一個畫面查看所有銀行帳戶餘額。

## 功能

- **Dashboard**：一覽所有已安裝銀行的帳戶餘額，支援同時同步多家銀行
- **Marketplace**：從 GitHub repo 安裝銀行 extension，支援使用者自主更新與移除
- **登入資料與排程**：App 依各 Extension 宣告的欄位顯示表單，在私有 Room 資料庫以明碼 JSON 保存，並以 WorkManager 啟動同步
- **Extension 執行環境**：Extension 自行負責登入、驗證碼、Cookie/session 與資料爬取
- **Native HTTP bridge**：提供不受 WebView CORS 限制的任意 HTTP request 能力
- **Ephemeral browser bridge**：需要真實瀏覽器 anti-bot stack 時，可在 fresh WebView profile 執行 XHR 或原生表單 POST 導覽

## 信任模型與運作原理

每家銀行對應一個由社群開發者維護的 **Extension**。Extension 是完全受信任程式碼；每次執行都可透過 `sdk.credential` 讀取該 Extension 定義的完整登入資料明碼，並可透過 `sdk.http.request` 向任意網址發出 request。Bridge 不限制 domain、method、header 或 redirect，也不阻止 extension 將登入資料外傳。

Kotlin App 在登入與爬蟲 domain 僅負責依 manifest 顯示泛用表單、持久化 credential JSON，以及啟動 Extension；登入流程、驗證碼辨識、Cookie/session 管理與銀行資料解析都由 Extension 實作。同步完成後，App 仍負責保存 Extension 回傳的 accounts、transfers 與執行狀態。

```text
Room plaintext credential JSON
  └─ Extension script
       ├─ deeply frozen sdk.credential { extension-defined string fields }
       ├─ await sdk.http.request(...) → native bridge → any destination
       ├─ await sdk.browser.open(...) → isolated WebView profile → bank origin
       ├─ await sdk.browser.request(...) → page XMLHttpRequest / browser anti-bot stack
       ├─ await sdk.browser.post(...) → native form POST / redirects / page JavaScript
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

若銀行的 WAF／anti-bot token 必須由真實瀏覽器環境產生，extension 可使用 `sdk.browser`。每次 extension invocation 都會使用新的 WebView profile，並在結束時銷毀；銀行頁面不會取得 Moneylook 的 native bridges 或完整 credential，只有 extension 明確放入 XHR 或表單 POST 的資料會送入該頁面。`sdk.browser.request` 固定使用銀行頁 main world 的 `XMLHttpRequest`；`sdk.browser.post` 則以主 frame 的 `application/x-www-form-urlencoded` POST 導覽，讓 Cookie、redirect、頁面 JavaScript 與瀏覽器 network stack 生效。

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

Browser bridge 範例：

```typescript
await sdk.browser.open({
  url: 'https://mybank.com/login',
  userAgent: 'Mozilla/5.0 Extension-Compatible-UA/1.0',
  timeoutMs: 30_000,
  settleMs: 1_000,
})

const response = await sdk.browser.request({
  method: 'POST',
  url: 'https://mybank.com/api/pre-login',
  headers: { 'Content-Type': 'application/json' },
  body: JSON.stringify({ customerId: sdk.credential.customerId }),
  withCredentials: true,
})

// 銀行要求真正的 HTML form navigation，而不是 XHR 時使用。
const navigated = await sdk.browser.post({
  url: 'https://mybank.com/login/submit',
  body: new URLSearchParams({
    customerId: sdk.credential.customerId,
    encryptedPassword: 'extension-generated-value',
  }).toString(),
  timeoutMs: 30_000,
  settleMs: 1_500,
})

sdk.browser.close()
```

`sdk.browser` contract：

- `open({ url, userAgent?, timeoutMs?, settleMs? }) → Promise<{ url, origin }>`
- `post({ url, body, timeoutMs?, settleMs? }) → Promise<{ url, origin }>`
- `request({ url, method?, headers?, body?, bodyEncoding?, responseEncoding?, timeoutMs?, withCredentials? }) → Promise<HttpResponse & { url }>`
- `close() → void`
- `open`、`post` 與 `request` 的 URL 都必須是 absolute HTTP(S) URL；不接受 `file:`、`data:`、`javascript:` 或 relative URL。
- `open.userAgent` 是可選的 1–512 字元 printable ASCII 字串；不得包含 CR、LF 或其他 control character。未指定時使用 WebView 預設 User-Agent。
- `post` 的 `body` 必須由 extension 先編碼成 UTF-8 `application/x-www-form-urlencoded` 字串；它會等待主 frame 的 HTTP redirect 與頁面 JavaScript 導覽穩定，只回傳 final URL/origin，不回傳 HTML 或 response body。
- Browser XHR 遵守正常 same-origin/CORS、forbidden-header 與 automatic redirect 規則。跨 origin endpoint 若未開放 CORS，extension 必須先 `open` 該 origin；真正不受 CORS 限制的 request 請使用 `sdk.http.request`。
- XHR response 不會公開 `Set-Cookie`，但 Cookie 會由該 invocation 的 WebView profile 自動保存並供後續同 session request 使用。
- `sdk.http.request`、`sdk.browser.open`、`sdk.browser.post` 與 `sdk.browser.request` 每次 invocation 共用 100 次 operation 上限。整個 script 最長 60 秒；單次 request／navigation 最長 30 秒；request body 上限 2 MiB、response body 上限 10 MiB。
- Browser XHR 會在 progress 已知總長或已下載量超過 10 MiB 時提早 abort，並在 onload 再做精確 byte-size 驗證。若 WebView/provider 不回報 progress，未知長度 response 仍可能先由 renderer 載入後才被拒絕。
- 若裝置的 Android System WebView 不支援 isolated multi-profile，browser API 會回 `BROWSER_PROFILE_UNSUPPORTED`；不會降級成可能與其他同步工作共享 Cookie 的全域 profile。

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

### 本機簽章設定

專案可讓 `debug` 與 `release` 共用同一組本機簽章，方便以 `adb install -r` 更新已安裝的開發裝置 App 並保留私有資料。簽章設定依序讀取環境變數與使用者層級的 `~/.gradle/gradle.properties`；環境變數優先，Android Studio 的 Gradle Sync／Build 會自動讀取後者。

```properties
MONEYLOOK_KEYSTORE_PATH=/absolute/path/to/moneylook-release.jks
MONEYLOOK_KEY_ALIAS=<key-alias>
MONEYLOOK_KEYSTORE_PASSWORD=<keystore-password>
MONEYLOOK_KEY_PASSWORD=<key-password>
```

請使用絕對路徑；Gradle property 中的 `~` 不會自動展開。上述設定只應存放在使用者層級的 Gradle properties，不可加入 repository，並建議限制檔案權限：

```bash
chmod 600 ~/.gradle/gradle.properties
```

CI 或臨時 shell 可改用既有的 `KEYSTORE_PATH`、`KEY_ALIAS`、`KEYSTORE_PASSWORD`、`KEY_PASSWORD` 環境變數覆寫。若設定了 keystore path，其他三個簽章值也必須完整提供；設定不完整或檔案不存在時，Gradle 會直接停止，避免意外產生不同簽章的 APK。未提供任何 keystore path 時，`debug` 維持 Android 預設 debug signing，`release` 維持未簽章。

## 模組結構

```text
:app                  — Compose 泛用 credential UI、同步結果持久化與 WorkManager 排程
:marketplace          — Extension 清單、下載與 repo URL 管理
:extension-runtime    — WebView 執行器、unrestricted native HTTP 與 ephemeral browser-XHR bridge
:core:data            — Room 資料庫（Account、Transfer、InstalledExtension、CredentialProfile）
:core:network         — OkHttp、Gson DI 模組
```

## 官方 Extension Repo

| Repo | 說明 |
|---|---|
| [moneylook-extensions](https://github.com/twkevinzhang/moneylook-extensions) | 已編譯的 Extension 腳本（Distribution） |
| [moneylook-extensions-source](https://github.com/twkevinzhang/moneylook-extensions-source) | TypeScript 原始碼與 CI/CD 工具鏈 |
