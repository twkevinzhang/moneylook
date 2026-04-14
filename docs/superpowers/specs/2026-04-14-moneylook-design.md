# Moneylook — 存摺 App 設計文件

**Date:** 2026-04-14  
**Status:** Approved

---

## 概述

Moneylook 是一個 Android 存摺聚合 App。使用者安裝由第三方開發的 bank extension（JavaScript 腳本），App 在使用者授權登入後，平行執行所有 extension 爬取各銀行帳戶餘額，並在 Dashboard 聚合顯示。

---

## 架構：Multi-Module

沿用 NewsHub 的 multi-module 結構：

```
:app                  # UI 層（Compose screens、ViewModels、Navigation）
:marketplace          # Extension 發現、下載、管理
:extension-runtime    # QuickJS 引擎、Extension SDK bridge、Session 管理
:core:data            # Room DB、Repository interfaces、Domain models
:core:network         # OkHttp client（供 marketplace 和 runtime 共用）
```

**依賴方向：**
```
:app → :marketplace, :extension-runtime, :core:data
:marketplace → :core:network, :core:data
:extension-runtime → :core:network, :core:data
:core:network  (no upstream deps)
:core:data     (no upstream deps)
```

DI 框架：Hilt。

---

## Extension 分發

### 兩個 GitHub Repo（需於實作階段建立）

- **`twkevinzhang/moneylook-extensions-source`**：TypeScript 原始碼，包含 GitHub Actions CI/CD，push main 時自動編譯並推送到 output repo。
- **`twkevinzhang/moneylook-extensions`**：App 實際下載的 repo（compiled output）。

### `moneylook-extensions` Repo 結構

```
index.min.json
tw.bot/
  manifest.json
  extension-script.min.js
  icon.png
tw.esun/
  manifest.json
  extension-script.min.js
  icon.png
```

### `index.min.json` 格式

```json
[
  { "id": "tw.bot", "name": "台灣銀行", "version": 1, "versionName": "1.0.0", "path": "tw.bot" },
  { "id": "tw.esun", "name": "玉山銀行", "version": 1, "versionName": "1.0.0", "path": "tw.esun" }
]
```

### `manifest.json` 格式（每個 extension）

```json
{
  "id": "tw.bot",
  "name": "台灣銀行",
  "version": 1,
  "versionName": "1.0.0",
  "description": "台灣銀行存摺爬蟲",
  "iconUrl": "icon.png",
  "loginUrl": "https://www.bot.com.tw/ibanking/",
  "targetDomains": ["bot.com.tw"],
  "scriptPath": "extension-script.min.js"
}
```

### TypeScript SDK Interface（`moneylook-extensions-source` repo）

```typescript
// sdk.d.ts — extension 開發者 import 此 type declaration
declare const sdk: {
  http: {
    get(url: string, headers?: Record<string, string>): HttpResponse
    post(url: string, body: string, headers?: Record<string, string>): HttpResponse
  }
}

interface HttpResponse {
  status: number
  body: string
  headers: Record<string, string>
}

interface AccountData {
  name: string       // e.g. "活期存款"
  balance: number
  currency: string   // e.g. "TWD"
}

type ExtensionMain = () => ExtensionResult

interface ExtensionResult {
  accounts: AccountData[]
}
```

---

## `:marketplace` Module

### 資料模型

```kotlin
data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val loginUrl: String,
    val targetDomains: List<String>,
    val scriptPath: String,
    val iconUrl: String?,
)

@Entity
data class InstalledExtension(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val repoUrl: String,         // 原始 GitHub URL
    val scriptCachePath: String, // 本地 script 路徑
    val loginUrl: String,
    val targetDomains: String,   // JSON array string
    val iconUrl: String?,
)
```

### 補充資料模型

```kotlin
data class ExtensionIndexEntry(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val path: String,  // e.g. "tw.bot"
)
```

### Repository Interface

```kotlin
interface MarketplaceRepository {
    suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry>
    suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest
    suspend fun downloadScript(manifest: ExtensionManifest, rawBase: String): File
    fun getInstalledExtensions(): Flow<List<InstalledExtension>>
    suspend fun installExtension(repoUrl: String, path: String)
    suspend fun uninstallExtension(id: String)
}
```

URL 轉換：`https://github.com/owner/repo` → `https://raw.githubusercontent.com/owner/repo/main`（沿用 NewsHub 的 `toRawBase()` 模式）。

---

## `:extension-runtime` Module

### JS Runtime

- 函式庫：**quickjs-wrapper** (`com.github.HarlonWang:quickjs-android-wrapper`)
- Extension script 執行於 `Dispatchers.IO`
- SDK bridge 採**同步阻塞**模式（避免 quickjs-wrapper async callback 的 function object 失效陷阱）

### ExtensionSdk（注入到 QuickJS global）

Extension script 可呼叫的唯一 API：

```javascript
sdk.http.get(url, headers?)   // → { status, body, headers }
sdk.http.post(url, body, headers?)
```

`sdk.openLogin()` **不存在**於 SDK 中（登入由使用者在同步前手動完成）。

### ExtensionRunner

```kotlin
data class AccountData(
    val name: String,
    val balance: Double,
    val currency: String,
)

data class HttpResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)

sealed class SyncResult {
    data class Success(val accounts: List<AccountData>) : SyncResult()
    data class Error(val message: String) : SyncResult()
    // Error cases: session not found / expired (HTTP 401/403), script runtime error, parse error
}

class ExtensionRunner(
    private val httpBridge: HttpBridge,
    private val sessionStore: SessionStore,
) {
    suspend fun run(extension: InstalledExtension): SyncResult
}
```

Session 有效性判斷：
- **同步前**：`SessionStore` 中無任何 session → `SyncResult.Error("session not found")`，卡片顯示「未登入」
- **同步中**：HTTP bridge 收到 401 / 403 response → `ExtensionRunner` 拋出 SessionExpiredException → `SyncResult.Error("session expired")`，卡片顯示「Session 過期」
- 兩種情況皆不觸發任何 UI，使用者需手動前往該銀行卡片點擊「登入」

### Session 管理

**SessionStore**：以 `extensionId` 為 key，儲存捕捉到的 session 資料。

**三層 Session Capture**（在 `LoginWebViewActivity` 的 `WebViewClient` 中執行）：

| 層 | 時機 | 擷取方式 |
|----|------|---------|
| 1 | `onPageFinished` | `CookieManager.getInstance().getCookie(url)` |
| 2 | `shouldOverrideUrlLoading` | 解析 URL query params 中的 `access_token`、`token`、`auth_token`、`code`、`id_token` |
| 3 | `shouldInterceptRequest` response headers | 偵測 `Authorization`、`X-Auth-Token`、`X-Session-Token`、`X-Access-Token` |

Session capture 在使用者關閉 WebView 時（`onDestroy`）完成最後一次全量擷取。

**LoginWebViewActivity**：
- 使用者自行關閉（返回鍵或關閉按鈕），App 不控制關閉時機
- Extension JS 不可觸發或關閉 WebView

### HttpBridge（同步阻塞）

```kotlin
class HttpBridge(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val extensionId: String,
    private val targetDomains: List<String>,
) {
    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult
    fun post(url: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult

    // Session 注入：只對 targetDomains 內的 URL 注入
    // 注入內容：Cookie header + 捕捉到的 token headers
}
```

---

## `:core:data` Module

### Room DB

```kotlin
@Entity
data class Account(
    @PrimaryKey val id: String,       // "{extensionId}_{accountName}"
    val extensionId: String,
    val extensionName: String,        // "台灣銀行"
    val accountName: String,          // "活期存款"
    val balance: Double,
    val currency: String,             // "TWD"
    val lastSyncAt: Long,             // epoch millis
)
```

---

## UI（`:app` module）

### Screens

**HomeScreen（Dashboard）**
- 每個已安裝 extension 顯示一張卡片，包含：銀行名稱、帳戶列表與餘額、登入狀態（已登入 / 未登入 / Session 過期）、最後同步時間
- 未登入的銀行顯示「登入」按鈕 → 開啟 `LoginWebViewActivity`
- 右上角「同步」按鈕：平行執行所有 extension，各卡片顯示 loading / success / error 狀態
- 同步失敗（session missing 或 script error）：卡片顯示錯誤訊息，不影響其他銀行

**MarketplaceScreen**
- 輸入 GitHub repo URL → fetch `index.min.json` → 顯示可安裝的 extension 清單
- 每個 extension 顯示：圖示、名稱、版本、安裝狀態（未安裝 / 已安裝 / 有更新）
- 點擊安裝 → 下載 `manifest.json` + `extension-script.min.js` 到本地

**LoginWebViewActivity**
- 全螢幕 WebView，頂部顯示銀行名稱
- 使用者自行關閉（返回鍵或頂部關閉按鈕）
- 關閉時觸發三層 session capture

### Sync 流程

```kotlin
// ViewModel 中
viewModelScope.launch {
    installedExtensions.map { ext ->
        async(Dispatchers.IO) {
            val result = extensionRunner.run(ext)
            when (result) {
                is SyncResult.Success -> accountDao.upsertAll(result.accounts.map { it.toEntity(ext) })
                is SyncResult.Error -> updateSyncState(ext.id, SyncState.Failed(result.message))
            }
        }
    }.awaitAll()
}
```

---

## 範疇（v1）

**包含：**
- Extension 安裝 / 更新 / 移除
- 銀行登入（WebView，三層 session capture）
- 帳戶餘額顯示（多銀行 Dashboard）
- 平行同步，個別 extension 失敗不影響其他

**不包含（v1 以後）：**
- 交易明細
- 本地資料加密
- Extension 簽章驗證
- 推播通知（餘額異動）
