# Moneylook — Claude Code Context

Android passbook aggregator. Users install JS extension scripts from GitHub repos; scripts run in a QuickJS sandbox with a native HTTP bridge that transparently injects session cookies/tokens.

## Module Graph

```
:app → :extension-runtime, :marketplace, :core:data, :core:network
:marketplace → :core:network
:extension-runtime → :core:network, :core:data
:core:data, :core:network — no upward deps
```

## Build

- AGP 9.1.1, Kotlin 2.2.10, minSdk 24, compileSdk 36
- **No `kotlinOptions` block** — use `compileOptions` only
- **No `kotlin-android` plugin** — already applied via `android.library`/`android.application`
- Hilt 2.59.2 (`ksp` processor, not `kapt`)
- Room 2.7.0 with `@Upsert`, `fallbackToDestructiveMigration(dropAllTables = true)`

```bash
./gradlew assembleDebug          # build
./gradlew testDebugUnitTest      # unit tests
./gradlew :app:compileDebugKotlin  # type-check only
```

## Key Architecture Decisions

### SessionStore (`extension-runtime`)
- `ConcurrentHashMap<String, SessionData>` — main thread writes (WebView callbacks), IO thread reads (HTTP bridge)
- All R-M-W via `compute()` — never `get` + `put`
- `SessionData(cookies: String?, tokens: Map<String, String>)`
- `hasSession()` returns true if either cookies or tokens are non-empty

### HttpBridge (`extension-runtime`)
- Domain matching: `urlHost == domain || urlHost.endsWith(".$domain")` — NOT `url.contains(domain)` (would allow exfiltration)
- Synchronous only — QuickJS cannot handle async callbacks
- Must run on `Dispatchers.IO`

### ExtensionRunner (`extension-runtime`)
- QuickJS: `com.whl.quickjs:wrapper-android:3.2.3`
- `QuickJSContext.create()` / `.destroy()` in `finally`
- All `JSObject` must be `.release()`d — in `finally` AND on every early return path
- Script must return `{ accounts: [{ name, balance, currency }] }` from a top-level IIFE

### LoginWebViewActivity (`app`)
- Three-layer session capture:
  1. `CookieManager` on `onPageFinished`
  2. URL query params on `shouldOverrideUrlLoading`
  3. OAuth fragment (`#access_token=...`) from the same callback
- `onDestroy` guard: `if (!::extensionId.isInitialized) return` — prevents crash when `onCreate` bails early
- Final sweep in `onDestroy` over `targetDomains`

### CancellationException Rule
Every `catch (e: Exception)` in a coroutine context **must** be preceded by:
```kotlin
catch (e: CancellationException) { throw e }
```
This applies in ViewModels, ExtensionRunnerImpl, MarketplaceRepositoryImpl — everywhere.

### HttpResult (`extension-runtime`)
`private constructor` + companion `invoke` factory — enforces `headers.toMap()` defensive copy. Never bypass with direct constructor call.

### HomeViewModel sync flow
- `refreshSessionStates()` uses `_syncStatuses.update { current -> ... }` and **merges** with existing state via `current[ext.id]?.copy(hasSession = ...)` — never replaces the whole map wholesale (would reset SYNCING indicators)
- `syncAll()` marks SYNCING then fans out with `async { }.awaitAll()`
- `openLogin()` uses injected `@ApplicationContext` + `FLAG_ACTIVITY_NEW_TASK` — no Activity context parameter

### MarketplaceViewModel
- `loadExtensionsSuspend(repoUrl)` is the core suspend logic; `loadExtensions()` wraps it in `viewModelScope.launch`. `addRepo()` and `install()` call `loadExtensionsSuspend` directly (already in a coroutine) to avoid nested launch
- `uninstall()` calls both `installedExtensionDao.deleteById()` AND `accountDao.deleteByExtensionId()` — orphan prevention

### Gson / DI
- `provideGson()` lives in `:core:network`'s `NetworkModule` — single source used by both `:marketplace` and `:extension-runtime`
- Do NOT add another `@Provides Gson` elsewhere — duplicate binding crash

### OkHttp logging
- Gated on `BuildConfig.DEBUG` in `NetworkModule` — never log in release (request URLs reveal bank identities)
- `:core:network` has `buildFeatures { buildConfig = true }` to generate `BuildConfig`

## Path Traversal Defense

`MarketplaceRepositoryImpl.downloadScript`:
```kotlin
val scriptFile = File(context.filesDir, "extensions/$extensionId/script.js")
check(scriptFile.canonicalPath.startsWith(context.filesDir.canonicalPath)) { ... }
```

`toRawBase()` requires GitHub URLs only — `require(url.startsWith("https://github.com/..."))`.

## Test Modules

- `:extension-runtime` — `SessionStoreTest` (pure JUnit, no Android)
- `:marketplace` — `MarketplaceRepositoryImplTest` (Robolectric)
- `:core:data` — `AccountTest` (pure JUnit)
- `:app` — `ExampleUnitTest` (placeholder, `testImplementation(libs.junit4)`)

## GitHub Repos

| Repo | Purpose |
|---|---|
| `twkevinzhang/moneylook-extensions` | Compiled extension distribution (index.min.json + scripts) |
| `twkevinzhang/moneylook-extensions-source` | TypeScript source + esbuild CI/CD pipeline |

CI pushes `dist/` from source repo to distribution repo via `EXTENSIONS_DEPLOY_TOKEN` secret (PAT with `repo` write scope on `moneylook-extensions`).

## Extension Contract

Script runs in QuickJS, receives `sdk.http.{get,post}`, must return:
```js
(function() {
  // ...
  return { accounts: [{ name: "帳戶名", balance: 12345.67, currency: "TWD" }] }
})()
```

Session auto-injection: cookies and tokens from `SessionStore` are injected as HTTP headers for any request whose host matches `targetDomains` (host-segment match, not substring).

## Known Limitations (post-v1)

- Layer 3 session capture (response headers via `shouldInterceptRequest`) deferred — WebView API only exposes request, not response
- Multi-repo Marketplace: UI only shows first repo's extensions; install always uses first repo URL
- Hardcoded UI strings (not in `strings.xml`) — localization deferred
