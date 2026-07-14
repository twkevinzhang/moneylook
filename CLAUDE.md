# Moneylook — Claude Code Context

Android passbook aggregator. Users install untrusted JS extension scripts from GitHub repos. The app owns banking credentials, performs native WebView login with captcha OCR, and gives extensions only a short-lived authenticated HTTP capability through a policy-enforcing bridge.

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
./gradlew assembleDebug
./gradlew testDebugUnitTest
./gradlew :app:compileDebugKotlin
```

## Key Architecture Decisions

### CredentialProfile (`core:data`)
- Exactly one credential profile per installed extension; `extensionId` is the primary/foreign key.
- Username and password are intentionally stored as plaintext in the app-private Room database.
- Saving credentials records the login host and target-domain permission snapshot; manifest domain changes require explicit re-approval.
- Never include credentials in logs, exceptions, extension JS, HTTP bridge headers, backups, or UI summaries.
- User schedule is stored on the profile. Manifest schedule values are suggestions copied when credentials are first saved.

### Native login (`extension-runtime`)
- `WebViewNativeLoginRunner` receives credentials only from native app code.
- Extension scripts never run inside the login WebView and never receive credential values.
- Login URL must be HTTPS and match an approved target domain by host segment.
- Only normal image captchas are supported. OTP, SMS, FIDO, sliders, and dynamic keyboards are out of scope.
- Every run starts with an empty WebView cookie jar and clears/destroys it in `finally`.

### EphemeralSession / HttpBridge (`extension-runtime`)
- Cookies are held only in an immutable `EphemeralSession` for one sync run; never persist them.
- JavaScript cannot read Cookie, Authorization, or Set-Cookie values.
- Domain matching: `urlHost == domain || urlHost.endsWith(".$domain")`.
- HTTPS only; GET/POST only; redirects disabled; credential-bearing and hop-by-hop extension headers rejected.
- Extensions are dangerous external code and are not endorsed. The proxy limits capabilities but cannot prove that an authenticated POST is semantically read-only.

### ExtensionRunner (`extension-runtime`)
- Scripts currently run in a blank Android WebView and receive only `sdk.http.{get,post,all,allSettled}`.
- A fresh non-empty `EphemeralSession` is required for each invocation.
- Script returns `{ accounts: [{ name, balance, currency }] }` from a top-level IIFE.
- Destroy the WebView in `finally`.

### Captcha OCR
- `CaptchaSolver` owns OCR calls and must never log images or response bodies.
- The real service base URL comes from `MONEYLOOK_OCR_BASE_URL` in ignored `local.properties` or CI environment.
- Never commit the real OCR URL or a fallback value.
- Only HTTPS, bounded image/response sizes, bounded timeout, and no redirects.

### CancellationException Rule
Every `catch (e: Exception)` in a coroutine context **must** be preceded by:
```kotlin
catch (e: CancellationException) { throw e }
```

### Home / scheduling
- Home exposes only credential summaries; it never publishes password values to Compose state.
- A blank password while editing means retain the existing password.
- WorkManager uses unique work per extension, network constraints, bounded retry/backoff, and UNIX five-field cron in the user's timezone.
- Each manual or scheduled sync always performs login → OCR → ephemeral session → extension scrape.

### Gson / DI
- `provideGson()` lives only in `:core:network`'s `NetworkModule`.
- Do not add another Gson binding.

### OkHttp logging
- Global debug logging is BASIC only; never BODY/HEADERS.
- Captcha/OCR client must remove inherited logging interceptors.
- Never log in release.

## Marketplace defenses

- GitHub repository URLs must use HTTPS and approved GitHub hosts.
- Manifest is untrusted and must be validated/normalized before use.
- `loginUrl` host must match normalized `targetDomains`.
- Reject localhost, IP literals, blank selectors, invalid timezones, and unsafe script paths.
- Downloaded script canonical path must stay inside `filesDir`.

## Test Modules

- `:extension-runtime` — ephemeral session, HTTP bridge policy, captcha client, native login validation
- `:marketplace` — manifest parsing/validation and repository download defenses
- `:core:data` — account and credential profile models
- `:app` — scheduling/orchestration tests should cover unique work, retry, and no credential exposure

## GitHub Repos

| Repo | Purpose |
|---|---|
| `twkevinzhang/moneylook-extensions` | Compiled extension distribution |
| `twkevinzhang/moneylook-extensions-source` | TypeScript source + esbuild CI/CD |

## Known Limitations

- WorkManager is best-effort; scheduled bank login is not guaranteed to run at an exact wall-clock time.
- Only normal image captchas are automated.
- Declarative DOM selectors can break whenever a bank changes its login page.
- An authenticated extension can make requests within its approved bank domains; domain/header checks cannot infer business intent.
- Hardcoded UI strings are not localized yet.
