# Moneylook — Claude Context

Android passbook aggregator. Users install fully trusted JS extension scripts from GitHub repositories. The app persists plaintext banking credentials and passes them to extensions; extensions own login, captcha, session, and scraping behavior and have unrestricted native HTTP request capability.

## Module Graph

```text
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

### Trust model

- Extensions are fully trusted code, not sandboxed capability clients.
- Every invocation exposes the extension-defined plaintext credential JSON through recursively frozen `sdk.credential`.
- Extensions may send credentials and other data to any destination. The app does not enforce domain, method, header, redirect, or business-intent policy.
- UI must clearly disclose this capability. Never claim that credentials, cookies, or network destinations are isolated from extensions.
- Installing or manually downloading an update is user consent for that version to continue using the saved credentials; no version-change re-approval is required.

### CredentialProfile (`core:data`)

- Exactly one credential profile per installed extension; `extensionId` is the primary/foreign key.
- A single extension-defined credential JSON object is intentionally stored as plaintext in the app-private Room database. The v1 contract is flat and every value is a string.
- Profiles do not store login hosts or domain-permission snapshots.
- Never include credentials in logs, exceptions, backups, or Compose summaries. Passing the minimal redacted `ExtensionCredential` wrapper to `ExtensionRunner` is the intended exception.
- User schedule is stored on the profile. Manifest schedule values are suggestions copied when credentials are first saved.
- Manifest `credential.fields` owns field keys, labels, types, required flags, and non-secret summaries. A blank password-type field while editing retains the existing value for the same key.

### Extension runtime

- `ExtensionRunner` receives only the installed extension and minimal `ExtensionCredential`.
- Scripts are async top-level IIFEs and receive recursively frozen `sdk.credential` plus async `sdk.http.request`. There is no `sdk.credentials` compatibility alias.
- The Kotlin bridge exists only to bypass WebView CORS and execute arbitrary HTTP(S) requests. It has no domain allowlist and permits arbitrary HTTP methods, headers (including credentials), bodies, and extension-controlled redirect behavior.
- Size, timeout, and per-run request-count bounds remain operational safeguards; they are not a security boundary between the extension and banking credentials.
- Native login, native captcha OCR, `EphemeralSession`, declarative login selectors, and the policy-enforcing `HttpBridge` are removed.
- Each invocation gets a fresh runtime; destroy the WebView in `finally` and do not persist runtime cookies or bridge state.
- Script returns `{ accounts: [{ name, balance, currency, no?, transfers? }] }`.

### Kotlin app responsibilities

- In the login/scraping domain, Kotlin persists credentials and starts the extension only; extensions perform login, captcha handling, Cookie/session management, requests, and parsing.
- Kotlin continues to persist returned accounts, transfers, schedules, and last-run status.
- Home exposes only credential summaries; it never publishes password values to Compose state.
- WorkManager uses unique work per extension, network constraints, bounded retry/backoff, and UNIX five-field cron in the user's timezone.

### CancellationException Rule

Every `catch (e: Exception)` in a coroutine context **must** be preceded by:

```kotlin
catch (e: CancellationException) { throw e }
```

### Gson / DI

- `provideGson()` lives only in `:core:network`'s `NetworkModule`.
- Do not add another Gson binding.

### OkHttp logging

- Global debug logging is BASIC only; never BODY/HEADERS.
- Never log in release.

## Marketplace Defenses

- GitHub repository URLs must use HTTPS and approved GitHub hosts.
- Manifest is untrusted input and must be validated/normalized before use, even though installed code is fully trusted at execution time.
- Reject invalid timezones and unsafe script paths.
- Downloaded script canonical path must stay inside `filesDir`.
- Manifest no longer contains `loginUrl`, `loginAutomation`, or `targetDomains`.

## Test Modules

- `:extension-runtime` — credential injection, async unrestricted request bridge, async result handling, and runtime cleanup
- `:marketplace` — manifest parsing/validation and repository download defenses
- `:core:data` — account, transfer, credential profile, and installed extension models
- `:app` — credential handoff, result persistence, unique work, and retry behavior

## GitHub Repos

| Repo | Purpose |
|---|---|
| `twkevinzhang/moneylook-extensions` | Compiled extension distribution |
| `twkevinzhang/moneylook-extensions-source` | TypeScript source + esbuild CI/CD |

## Known Limitations

- A malicious or compromised extension can exfiltrate banking credentials and all data available to it.
- WorkManager is best-effort; scheduled bank login is not guaranteed to run at an exact wall-clock time.
- Bank login, captcha, and DOM/API changes are entirely the extension author's responsibility.
- Hardcoded UI strings are not localized yet.
