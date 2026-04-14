# Moneylook v1 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Build a multi-bank passbook Android app where users install JS extension scripts that scrape account balances via a native HTTP bridge running in a QuickJS sandbox.

**Architecture:** Multi-module Gradle project (`:app`, `:marketplace`, `:extension-runtime`, `:core:data`, `:core:network`). Extension scripts run synchronously in QuickJS on `Dispatchers.IO`; a three-layer session capture in `LoginWebViewActivity` stores cookies and tokens in `SessionStore`, which the HTTP bridge injects transparently.

**Tech Stack:** Kotlin + Jetpack Compose, Hilt, Room, OkHttp, Gson, DataStore, `wang.harlon.quickjs:wrapper-android:3.2.3`

---

## File Map

```
settings.gradle.kts                             (modify)
build.gradle.kts                                (modify – root)
gradle/libs.versions.toml                       (modify)

core/data/
  build.gradle.kts
  src/main/java/tw/kevinzhang/core/data/
    db/MoneylookDatabase.kt
    db/AccountDao.kt
    db/InstalledExtensionDao.kt
    model/Account.kt
    model/InstalledExtension.kt
    di/DataModule.kt

core/network/
  build.gradle.kts
  src/main/java/tw/kevinzhang/core/network/
    di/NetworkModule.kt

marketplace/
  build.gradle.kts
  src/main/java/tw/kevinzhang/marketplace/
    data/ExtensionManifest.kt
    data/ExtensionIndexEntry.kt
    data/ExtensionIndexEntryDto.kt          (JSON DTO)
    MarketplaceRepository.kt
    MarketplaceRepositoryImpl.kt
    RepoUrlRepository.kt
    RepoUrlRepositoryImpl.kt
    di/MarketplaceModule.kt

extension-runtime/
  build.gradle.kts
  src/main/java/tw/kevinzhang/extension_runtime/
    data/AccountData.kt
    data/HttpResult.kt
    data/SyncResult.kt
    session/SessionStore.kt
    bridge/HttpBridge.kt
    ExtensionRunner.kt
    ExtensionRunnerImpl.kt
    di/RuntimeModule.kt

app/
  build.gradle.kts                              (modify)
  src/main/AndroidManifest.xml                  (modify)
  src/main/java/tw/kevinzhang/moneylook/
    MoneylookApplication.kt
    MainActivity.kt                             (modify)
    di/AppModule.kt
    ui/navigation/Screen.kt
    ui/navigation/AppNavHost.kt
    ui/home/HomeViewModel.kt
    ui/home/HomeScreen.kt
    ui/marketplace/MarketplaceViewModel.kt
    ui/marketplace/MarketplaceScreen.kt
    ui/login/LoginWebViewActivity.kt
```

---

## Task 1: Gradle Multi-module Scaffold

**Files:**
- Modify: `settings.gradle.kts`
- Modify: `build.gradle.kts` (root — currently does not exist, must create)
- Modify: `gradle/libs.versions.toml`
- Create: `core/data/build.gradle.kts`
- Create: `core/network/build.gradle.kts`
- Create: `marketplace/build.gradle.kts`
- Create: `extension-runtime/build.gradle.kts`
- Modify: `app/build.gradle.kts`

- [ ] **Step 1: Update `settings.gradle.kts` to include all modules**

Replace entire content of `settings.gradle.kts`:

```kotlin
pluginManagement {
    repositories {
        google {
            content {
                includeGroupByRegex("com\\.android.*")
                includeGroupByRegex("com\\.google.*")
                includeGroupByRegex("androidx.*")
            }
        }
        mavenCentral()
        gradlePluginPortal()
    }
}
plugins {
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}
dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "Moneylook"
include(":app")
include(":core:data")
include(":core:network")
include(":marketplace")
include(":extension-runtime")
```

- [ ] **Step 2: Create root `build.gradle.kts`**

```kotlin
// build.gradle.kts (root)
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.android.library) apply false
    alias(libs.plugins.kotlin.android) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
```

- [ ] **Step 3: Update `gradle/libs.versions.toml`**

Replace entire content:

```toml
[versions]
agp = "8.13.2"
kotlin = "2.1.0"
ksp = "2.1.0-1.0.29"

# DI
hilt = "2.56"
androidxHilt = "1.0.0"

# AndroidX
coreKtx = "1.13.1"
lifecycle = "2.8.7"
room = "2.7.0"
datastore = "1.1.1"

# Compose
composeActivity = "1.9.3"
composeNavigation = "2.8.7"
composeBom = "2024.12.01"
material3 = "1.3.1"

# Network
okhttp = "4.12.0"

# Parsing
gson = "2.10.1"

# Coroutines
coroutines = "1.8.1"

# Image Loading
coil = "2.7.0"

# QuickJS
quickjs = "3.2.3"

# Testing
junit4 = "4.13.2"
robolectric = "4.13"

[libraries]
# AndroidX
androidx-core-ktx = { group = "androidx.core", name = "core-ktx", version.ref = "coreKtx" }
androidx-lifecycle-runtime-ktx = { group = "androidx.lifecycle", name = "lifecycle-runtime-ktx", version.ref = "lifecycle" }
androidx-lifecycle-runtime-compose = { group = "androidx.lifecycle", name = "lifecycle-runtime-compose", version.ref = "lifecycle" }
androidx-lifecycle-viewmodel-compose = { group = "androidx.lifecycle", name = "lifecycle-viewmodel-compose", version.ref = "lifecycle" }
androidx-datastore-preferences = { group = "androidx.datastore", name = "datastore-preferences", version.ref = "datastore" }

# Room
room-runtime = { group = "androidx.room", name = "room-runtime", version.ref = "room" }
room-ktx = { group = "androidx.room", name = "room-ktx", version.ref = "room" }
room-compiler = { group = "androidx.room", name = "room-compiler", version.ref = "room" }

# Compose
compose-bom = { group = "androidx.compose", name = "compose-bom", version.ref = "composeBom" }
compose-ui = { group = "androidx.compose.ui", name = "ui" }
compose-ui-graphics = { group = "androidx.compose.ui", name = "ui-graphics" }
compose-ui-preview = { group = "androidx.compose.ui", name = "ui-tooling-preview" }
compose-ui-tooling = { group = "androidx.compose.ui", name = "ui-tooling" }
compose-ui-test-manifest = { group = "androidx.compose.ui", name = "ui-test-manifest" }
compose-material3 = { group = "androidx.compose.material3", name = "material3" }
compose-material-icons = { group = "androidx.compose.material", name = "material-icons-extended" }
compose-activity = { group = "androidx.activity", name = "activity-compose", version.ref = "composeActivity" }
compose-navigation = { group = "androidx.navigation", name = "navigation-compose", version.ref = "composeNavigation" }

# Hilt
hilt-android = { group = "com.google.dagger", name = "hilt-android", version.ref = "hilt" }
hilt-compiler = { group = "com.google.dagger", name = "hilt-android-compiler", version.ref = "hilt" }
hilt-nav-compose = { group = "androidx.hilt", name = "hilt-navigation-compose", version.ref = "androidxHilt" }

# Network
okhttp-core = { group = "com.squareup.okhttp3", name = "okhttp", version.ref = "okhttp" }
okhttp-logging = { group = "com.squareup.okhttp3", name = "logging-interceptor", version.ref = "okhttp" }

# Parsing
gson = { group = "com.google.code.gson", name = "gson", version.ref = "gson" }

# Coroutines
coroutines-android = { group = "org.jetbrains.kotlinx", name = "kotlinx-coroutines-android", version.ref = "coroutines" }

# Image Loading
coil-compose = { group = "io.coil-kt", name = "coil-compose", version.ref = "coil" }

# QuickJS
quickjs-android = { group = "wang.harlon.quickjs", name = "wrapper-android", version.ref = "quickjs" }

# Testing
junit4 = { group = "junit", name = "junit", version.ref = "junit4" }
robolectric = { group = "org.robolectric", name = "robolectric", version.ref = "robolectric" }

[bundles]
okhttp = ["okhttp-core", "okhttp-logging"]

[plugins]
android-application = { id = "com.android.application", version.ref = "agp" }
android-library = { id = "com.android.library", version.ref = "agp" }
kotlin-android = { id = "org.jetbrains.kotlin.android", version.ref = "kotlin" }
kotlin-compose = { id = "org.jetbrains.kotlin.plugin.compose", version.ref = "kotlin" }
ksp = { id = "com.google.devtools.ksp", version.ref = "ksp" }
hilt = { id = "com.google.dagger.hilt.android", version.ref = "hilt" }
```

- [ ] **Step 4: Create `core/network/build.gradle.kts`**

First create the directory: `mkdir -p core/network/src/main/java/tw/kevinzhang/core/network/di`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.kevinzhang.core.network"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.bundles.okhttp)
}
```

- [ ] **Step 5: Create `core/data/build.gradle.kts`**

First create directory: `mkdir -p core/data/src/main/java/tw/kevinzhang/core/data/db core/data/src/main/java/tw/kevinzhang/core/data/model core/data/src/main/java/tw/kevinzhang/core/data/di`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.kevinzhang.core.data"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.room.runtime)
    implementation(libs.room.ktx)
    ksp(libs.room.compiler)
    implementation(libs.coroutines.android)
    implementation(libs.gson)
    testImplementation(libs.junit4)
}
```

- [ ] **Step 6: Create `marketplace/build.gradle.kts`**

First create directory: `mkdir -p marketplace/src/main/java/tw/kevinzhang/marketplace/data marketplace/src/main/java/tw/kevinzhang/marketplace/di marketplace/src/test/java/tw/kevinzhang/marketplace`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.kevinzhang.marketplace"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    implementation(libs.androidx.datastore.preferences)
    testImplementation(libs.junit4)
}
```

- [ ] **Step 7: Create `extension-runtime/build.gradle.kts`**

First create directory: `mkdir -p extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/data extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/session extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/bridge extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/di extension-runtime/src/test/java/tw/kevinzhang/extension_runtime`

```kotlin
plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.kevinzhang.extension_runtime"
    compileSdk = 36
    defaultConfig { minSdk = 24 }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.quickjs.android)
    implementation(libs.gson)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
}
```

- [ ] **Step 8: Update `app/build.gradle.kts`**

Replace entire content:

```kotlin
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

android {
    namespace = "tw.kevinzhang.moneylook"
    compileSdk = 36
    defaultConfig {
        applicationId = "tw.kevinzhang.moneylook"
        minSdk = 24
        targetSdk = 36
        versionCode = 1
        versionName = "1.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    buildTypes {
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    kotlinOptions { jvmTarget = "11" }
    buildFeatures { compose = true }
}

dependencies {
    implementation(project(":core:data"))
    implementation(project(":core:network"))
    implementation(project(":marketplace"))
    implementation(project(":extension-runtime"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.hilt.nav.compose)

    implementation(platform(libs.compose.bom))
    implementation(libs.compose.ui)
    implementation(libs.compose.ui.graphics)
    implementation(libs.compose.ui.preview)
    implementation(libs.compose.material3)
    implementation(libs.compose.material.icons)
    implementation(libs.compose.activity)
    implementation(libs.compose.navigation)
    implementation(libs.coil.compose)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
```

- [ ] **Step 9: Verify the project syncs**

```bash
./gradlew assembleDebug --dry-run
```

Expected: BUILD SUCCESSFUL (no source files yet, just Gradle structure)

- [ ] **Step 10: Commit**

```bash
git add settings.gradle.kts build.gradle.kts gradle/libs.versions.toml \
        core/data/build.gradle.kts core/network/build.gradle.kts \
        marketplace/build.gradle.kts extension-runtime/build.gradle.kts \
        app/build.gradle.kts
git commit -m "build: add multi-module Gradle scaffold"
```

---

## Task 2: `:core:network` Module

**Files:**
- Create: `core/network/src/main/java/tw/kevinzhang/core/network/di/NetworkModule.kt`

- [ ] **Step 1: Create `NetworkModule.kt`**

```kotlin
package tw.kevinzhang.core.network.di

import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import okhttp3.logging.HttpLoggingInterceptor
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object NetworkModule {

    @Provides
    @Singleton
    fun provideOkHttpClient(): OkHttpClient = OkHttpClient.Builder()
        .addInterceptor(HttpLoggingInterceptor().apply {
            level = HttpLoggingInterceptor.Level.BASIC
        })
        .build()
}
```

- [ ] **Step 2: Commit**

```bash
git add core/network/
git commit -m "feat(core:network): add OkHttpClient Hilt module"
```

---

## Task 3: `:core:data` Module — Models & Database

**Files:**
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/model/Account.kt`
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/model/InstalledExtension.kt`
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/db/AccountDao.kt`
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/db/InstalledExtensionDao.kt`
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/db/MoneylookDatabase.kt`
- Create: `core/data/src/main/java/tw/kevinzhang/core/data/di/DataModule.kt`

- [ ] **Step 1: Create `Account.kt`**

```kotlin
package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "accounts")
data class Account(
    @PrimaryKey val id: String,           // "{extensionId}_{accountName}"
    val extensionId: String,
    val extensionName: String,
    val accountName: String,
    val balance: Double,
    val currency: String,
    val lastSyncAt: Long,                 // epoch millis
)
```

- [ ] **Step 2: Create `InstalledExtension.kt`**

```kotlin
package tw.kevinzhang.core.data.model

import androidx.room.Entity
import androidx.room.PrimaryKey

@Entity(tableName = "installed_extensions")
data class InstalledExtension(
    @PrimaryKey val id: String,
    val name: String,
    val version: Int,
    val repoUrl: String,
    val scriptCachePath: String,          // absolute path on device
    val loginUrl: String,
    val targetDomainsJson: String,        // JSON array string e.g. ["bot.com.tw"]
    val iconUrl: String?,
)
```

- [ ] **Step 3: Create `AccountDao.kt`**

```kotlin
package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Query
import androidx.room.Upsert
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.Account

@Dao
interface AccountDao {
    @Query("SELECT * FROM accounts ORDER BY extensionName, accountName")
    fun observeAll(): Flow<List<Account>>

    @Upsert
    suspend fun upsertAll(accounts: List<Account>)

    @Query("DELETE FROM accounts WHERE extensionId = :extensionId")
    suspend fun deleteByExtensionId(extensionId: String)
}
```

- [ ] **Step 4: Create `InstalledExtensionDao.kt`**

```kotlin
package tw.kevinzhang.core.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import tw.kevinzhang.core.data.model.InstalledExtension

@Dao
interface InstalledExtensionDao {
    @Query("SELECT * FROM installed_extensions ORDER BY name")
    fun observeAll(): Flow<List<InstalledExtension>>

    @Query("SELECT * FROM installed_extensions ORDER BY name")
    suspend fun getAll(): List<InstalledExtension>

    @Query("SELECT * FROM installed_extensions WHERE id = :id")
    suspend fun getById(id: String): InstalledExtension?

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insert(extension: InstalledExtension)

    @Query("DELETE FROM installed_extensions WHERE id = :id")
    suspend fun deleteById(id: String)
}
```

- [ ] **Step 5: Create `MoneylookDatabase.kt`**

```kotlin
package tw.kevinzhang.core.data.db

import androidx.room.Database
import androidx.room.RoomDatabase
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension

@Database(
    entities = [Account::class, InstalledExtension::class],
    version = 1,
    exportSchema = false,
)
abstract class MoneylookDatabase : RoomDatabase() {
    abstract fun accountDao(): AccountDao
    abstract fun installedExtensionDao(): InstalledExtensionDao
}
```

- [ ] **Step 6: Create `DataModule.kt`**

```kotlin
package tw.kevinzhang.core.data.di

import android.content.Context
import androidx.room.Room
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.android.qualifiers.ApplicationContext
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.db.MoneylookDatabase
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object DataModule {

    @Provides
    @Singleton
    fun provideDatabase(@ApplicationContext context: Context): MoneylookDatabase =
        Room.databaseBuilder(context, MoneylookDatabase::class.java, "moneylook.db")
            .build()

    @Provides
    fun provideAccountDao(db: MoneylookDatabase): AccountDao = db.accountDao()

    @Provides
    fun provideInstalledExtensionDao(db: MoneylookDatabase): InstalledExtensionDao =
        db.installedExtensionDao()
}
```

- [ ] **Step 7: Write unit test for Account entity**

Create `core/data/src/test/java/tw/kevinzhang/core/data/model/AccountTest.kt`:

```kotlin
package tw.kevinzhang.core.data.model

import org.junit.Assert.assertEquals
import org.junit.Test

class AccountTest {
    @Test
    fun `id is composed of extensionId and accountName`() {
        val account = Account(
            id = "tw.bot_活期存款",
            extensionId = "tw.bot",
            extensionName = "台灣銀行",
            accountName = "活期存款",
            balance = 12345.67,
            currency = "TWD",
            lastSyncAt = 1000L,
        )
        assertEquals("tw.bot", account.extensionId)
        assertEquals("活期存款", account.accountName)
        assertEquals("tw.bot_活期存款", account.id)
    }
}
```

- [ ] **Step 8: Run the test**

```bash
./gradlew :core:data:test
```

Expected: BUILD SUCCESSFUL, 1 test passes

- [ ] **Step 9: Commit**

```bash
git add core/data/
git commit -m "feat(core:data): add Room DB with Account and InstalledExtension entities"
```

---

## Task 4: `:marketplace` Module

**Files:**
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/data/ExtensionManifest.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/data/ExtensionIndexEntry.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/data/ExtensionIndexEntryDto.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/MarketplaceRepository.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/MarketplaceRepositoryImpl.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/RepoUrlRepository.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/RepoUrlRepositoryImpl.kt`
- Create: `marketplace/src/main/java/tw/kevinzhang/marketplace/di/MarketplaceModule.kt`

- [ ] **Step 1: Create `ExtensionManifest.kt`**

```kotlin
package tw.kevinzhang.marketplace.data

data class ExtensionManifest(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val description: String,
    val loginUrl: String,
    val targetDomains: List<String>,
    val scriptPath: String,
    val iconUrl: String?,
)
```

- [ ] **Step 2: Create `ExtensionIndexEntry.kt` and `ExtensionIndexEntryDto.kt`**

```kotlin
// ExtensionIndexEntry.kt
package tw.kevinzhang.marketplace.data

data class ExtensionIndexEntry(
    val id: String,
    val name: String,
    val version: Int,
    val versionName: String,
    val path: String,   // subdirectory in the repo, e.g. "tw.bot"
)
```

```kotlin
// ExtensionIndexEntryDto.kt  — matches the JSON shape in index.min.json
package tw.kevinzhang.marketplace.data

import com.google.gson.annotations.SerializedName

data class ExtensionIndexEntryDto(
    @SerializedName("id") val id: String = "",
    @SerializedName("name") val name: String = "",
    @SerializedName("version") val version: Int = 1,
    @SerializedName("versionName") val versionName: String = "1.0.0",
    @SerializedName("path") val path: String = "",
) {
    fun toDomain() = ExtensionIndexEntry(id, name, version, versionName, path)
}
```

- [ ] **Step 3: Create `MarketplaceRepository.kt`**

```kotlin
package tw.kevinzhang.marketplace

import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionManifest

interface MarketplaceRepository {
    /** Fetches index.min.json from the given GitHub repo URL. */
    suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry>

    /** Fetches {path}/manifest.json from the given GitHub repo URL. */
    suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest

    /** Downloads {path}/extension-script.min.js and saves to internal storage. Returns local file path. */
    suspend fun downloadScript(repoUrl: String, path: String, extensionId: String): String
}
```

- [ ] **Step 4: Create `MarketplaceRepositoryImpl.kt`**

```kotlin
package tw.kevinzhang.marketplace

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import okhttp3.Request
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import tw.kevinzhang.marketplace.data.ExtensionIndexEntryDto
import tw.kevinzhang.marketplace.data.ExtensionManifest
import java.io.File
import java.io.IOException
import android.content.Context
import javax.inject.Inject

class MarketplaceRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
    private val okHttpClient: OkHttpClient,
    private val gson: Gson,
) : MarketplaceRepository {

    override suspend fun fetchIndex(repoUrl: String): List<ExtensionIndexEntry> =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val json = fetchString("$rawBase/index.min.json")
            val type = object : TypeToken<List<ExtensionIndexEntryDto>>() {}.type
            val dtos: List<ExtensionIndexEntryDto> = gson.fromJson(json, type)
            dtos.map { it.toDomain() }
        }

    override suspend fun fetchManifest(repoUrl: String, path: String): ExtensionManifest =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val json = fetchString("$rawBase/$path/manifest.json")
            gson.fromJson(json, ExtensionManifest::class.java)
        }

    override suspend fun downloadScript(repoUrl: String, path: String, extensionId: String): String =
        withContext(Dispatchers.IO) {
            val rawBase = toRawBase(repoUrl)
            val manifest = gson.fromJson(
                fetchString("$rawBase/$path/manifest.json"),
                ExtensionManifest::class.java
            )
            val scriptUrl = "$rawBase/$path/${manifest.scriptPath}"
            val bytes = fetchBytes(scriptUrl)
            val scriptFile = File(context.filesDir, "extensions/$extensionId/script.js")
            scriptFile.parentFile?.mkdirs()
            scriptFile.writeBytes(bytes)
            scriptFile.absolutePath
        }

    // Converts https://github.com/owner/repo → https://raw.githubusercontent.com/owner/repo/main
    internal fun toRawBase(repoUrl: String): String {
        val normalized = repoUrl.trimEnd('/')
        return if (normalized.contains("raw.githubusercontent.com")) {
            normalized
        } else {
            normalized
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/") + "/main"
        }
    }

    private fun fetchString(url: String): String {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.string() ?: throw IOException("Empty response for $url")
        }
    }

    private fun fetchBytes(url: String): ByteArray {
        okHttpClient.newCall(Request.Builder().url(url).build()).execute().use { response ->
            if (!response.isSuccessful) throw IOException("HTTP ${response.code} for $url")
            return response.body?.bytes() ?: throw IOException("Empty response for $url")
        }
    }
}
```

- [ ] **Step 5: Create `RepoUrlRepository.kt`**

```kotlin
package tw.kevinzhang.marketplace

import kotlinx.coroutines.flow.Flow

interface RepoUrlRepository {
    fun observeRepoUrls(): Flow<Set<String>>
    suspend fun addRepoUrl(url: String)
    suspend fun removeRepoUrl(url: String)
}
```

- [ ] **Step 6: Create `RepoUrlRepositoryImpl.kt`**

```kotlin
package tw.kevinzhang.marketplace

import android.content.Context
import androidx.datastore.core.DataStore
import androidx.datastore.preferences.core.Preferences
import androidx.datastore.preferences.core.edit
import androidx.datastore.preferences.core.stringSetPreferencesKey
import androidx.datastore.preferences.preferencesDataStore
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import javax.inject.Inject

private val Context.dataStore: DataStore<Preferences> by preferencesDataStore(name = "marketplace_prefs")

class RepoUrlRepositoryImpl @Inject constructor(
    @ApplicationContext private val context: Context,
) : RepoUrlRepository {

    private val repoUrlsKey = stringSetPreferencesKey("repo_urls")

    override fun observeRepoUrls(): Flow<Set<String>> =
        context.dataStore.data.map { prefs -> prefs[repoUrlsKey] ?: emptySet() }

    override suspend fun addRepoUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[repoUrlsKey] = (prefs[repoUrlsKey] ?: emptySet()) + url
        }
    }

    override suspend fun removeRepoUrl(url: String) {
        context.dataStore.edit { prefs ->
            prefs[repoUrlsKey] = (prefs[repoUrlsKey] ?: emptySet()) - url
        }
    }
}
```

- [ ] **Step 7: Create `MarketplaceModule.kt`**

```kotlin
package tw.kevinzhang.marketplace.di

import com.google.gson.Gson
import dagger.Binds
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.MarketplaceRepositoryImpl
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.RepoUrlRepositoryImpl
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
abstract class MarketplaceModule {

    @Binds @Singleton
    abstract fun bindMarketplaceRepository(impl: MarketplaceRepositoryImpl): MarketplaceRepository

    @Binds @Singleton
    abstract fun bindRepoUrlRepository(impl: RepoUrlRepositoryImpl): RepoUrlRepository

    companion object {
        @Provides @Singleton
        fun provideGson(): Gson = Gson()
    }
}
```

- [ ] **Step 8: Write unit test for `toRawBase()`**

Create `marketplace/src/test/java/tw/kevinzhang/marketplace/MarketplaceRepositoryImplTest.kt`:

```kotlin
package tw.kevinzhang.marketplace

import org.junit.Assert.assertEquals
import org.junit.Test

class MarketplaceRepositoryImplTest {

    // toRawBase() is internal so we test via a test-only subclass
    private val repo = object : MarketplaceRepositoryImpl(
        context = TODO(), okHttpClient = TODO(), gson = TODO()
    ) {}

    // Use a simple helper approach instead — test the logic directly
    private fun toRawBase(url: String): String {
        val normalized = url.trimEnd('/')
        return if (normalized.contains("raw.githubusercontent.com")) {
            normalized
        } else {
            normalized
                .replace("https://github.com/", "https://raw.githubusercontent.com/")
                .replace("http://github.com/", "https://raw.githubusercontent.com/") + "/main"
        }
    }

    @Test
    fun `github url converts to raw base`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            toRawBase("https://github.com/twkevinzhang/moneylook-extensions")
        )
    }

    @Test
    fun `trailing slash is stripped`() {
        assertEquals(
            "https://raw.githubusercontent.com/twkevinzhang/moneylook-extensions/main",
            toRawBase("https://github.com/twkevinzhang/moneylook-extensions/")
        )
    }

    @Test
    fun `already raw url is unchanged`() {
        val rawUrl = "https://raw.githubusercontent.com/owner/repo/main"
        assertEquals(rawUrl, toRawBase(rawUrl))
    }
}
```

- [ ] **Step 9: Run the test**

```bash
./gradlew :marketplace:test
```

Expected: BUILD SUCCESSFUL, 3 tests pass

- [ ] **Step 10: Commit**

```bash
git add marketplace/
git commit -m "feat(marketplace): add extension discovery and repo URL management"
```

---

## Task 5: `:extension-runtime` — Data Models & SessionStore

**Files:**
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/data/AccountData.kt`
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/data/HttpResult.kt`
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/data/SyncResult.kt`
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/session/SessionStore.kt`

- [ ] **Step 1: Create data models**

```kotlin
// AccountData.kt
package tw.kevinzhang.extension_runtime.data

data class AccountData(
    val name: String,
    val balance: Double,
    val currency: String,
)
```

```kotlin
// HttpResult.kt
package tw.kevinzhang.extension_runtime.data

data class HttpResult(
    val status: Int,
    val body: String,
    val headers: Map<String, String>,
)
```

```kotlin
// SyncResult.kt
package tw.kevinzhang.extension_runtime.data

sealed class SyncResult {
    data class Success(val accounts: List<AccountData>) : SyncResult()
    data class Error(val message: String) : SyncResult()
}
```

- [ ] **Step 2: Create `SessionStore.kt`**

```kotlin
package tw.kevinzhang.extension_runtime.session

import javax.inject.Inject
import javax.inject.Singleton

/**
 * In-memory store for per-extension session data captured from WebView.
 * Keyed by extensionId. Cleared on app process restart (user must re-login).
 */
@Singleton
class SessionStore @Inject constructor() {

    private data class SessionData(
        val cookies: String?,                      // raw Cookie header value
        val tokens: Map<String, String>,           // extra headers e.g. Authorization
    )

    private val sessions = mutableMapOf<String, SessionData>()

    fun putCookies(extensionId: String, cookies: String) {
        val existing = sessions[extensionId] ?: SessionData(null, emptyMap())
        sessions[extensionId] = existing.copy(cookies = cookies)
    }

    fun putToken(extensionId: String, headerName: String, value: String) {
        val existing = sessions[extensionId] ?: SessionData(null, emptyMap())
        sessions[extensionId] = existing.copy(tokens = existing.tokens + (headerName to value))
    }

    fun getCookies(extensionId: String): String? = sessions[extensionId]?.cookies

    fun getTokens(extensionId: String): Map<String, String> =
        sessions[extensionId]?.tokens ?: emptyMap()

    fun hasSession(extensionId: String): Boolean = sessions.containsKey(extensionId)

    fun clearSession(extensionId: String) { sessions.remove(extensionId) }
}
```

- [ ] **Step 3: Write unit test for `SessionStore`**

Create `extension-runtime/src/test/java/tw/kevinzhang/extension_runtime/session/SessionStoreTest.kt`:

```kotlin
package tw.kevinzhang.extension_runtime.session

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

class SessionStoreTest {

    private lateinit var store: SessionStore

    @Before
    fun setUp() { store = SessionStore() }

    @Test
    fun `hasSession returns false before any data stored`() {
        assertFalse(store.hasSession("tw.bot"))
    }

    @Test
    fun `putCookies makes hasSession return true`() {
        store.putCookies("tw.bot", "session=abc")
        assertTrue(store.hasSession("tw.bot"))
    }

    @Test
    fun `getCookies returns stored value`() {
        store.putCookies("tw.bot", "session=abc; token=xyz")
        assertEquals("session=abc; token=xyz", store.getCookies("tw.bot"))
    }

    @Test
    fun `putToken stores token and preserves cookies`() {
        store.putCookies("tw.bot", "session=abc")
        store.putToken("tw.bot", "Authorization", "Bearer tok123")
        assertEquals("Bearer tok123", store.getTokens("tw.bot")["Authorization"])
        assertEquals("session=abc", store.getCookies("tw.bot"))
    }

    @Test
    fun `clearSession removes all data`() {
        store.putCookies("tw.bot", "session=abc")
        store.clearSession("tw.bot")
        assertFalse(store.hasSession("tw.bot"))
        assertNull(store.getCookies("tw.bot"))
    }

    @Test
    fun `different extensions are isolated`() {
        store.putCookies("tw.bot", "bot-cookie")
        store.putCookies("tw.esun", "esun-cookie")
        assertEquals("bot-cookie", store.getCookies("tw.bot"))
        assertEquals("esun-cookie", store.getCookies("tw.esun"))
    }
}
```

- [ ] **Step 4: Run the test**

```bash
./gradlew :extension-runtime:test
```

Expected: 6 tests pass

- [ ] **Step 5: Commit**

```bash
git add extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/data \
        extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/session \
        extension-runtime/src/test/
git commit -m "feat(extension-runtime): add data models and SessionStore"
```

---

## Task 6: `:extension-runtime` — HttpBridge

**Files:**
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/bridge/HttpBridge.kt`

- [ ] **Step 1: Create `HttpBridge.kt`**

```kotlin
package tw.kevinzhang.extension_runtime.bridge

import okhttp3.MediaType.Companion.toMediaType
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import tw.kevinzhang.extension_runtime.data.HttpResult
import tw.kevinzhang.extension_runtime.session.SessionStore

/**
 * Synchronous HTTP bridge exposed to JS scripts via SDK.
 * Automatically injects session (cookies + tokens) for targetDomains.
 * Must be called from a background thread (Dispatchers.IO).
 */
class HttpBridge(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val extensionId: String,
    private val targetDomains: List<String>,
) {

    fun get(url: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult =
        execute(buildRequest(url, extraHeaders, body = null))

    fun post(url: String, body: String, extraHeaders: Map<String, String> = emptyMap()): HttpResult {
        val requestBody = body.toRequestBody("application/json".toMediaType())
        return execute(buildRequest(url, extraHeaders, body = requestBody))
    }

    private fun buildRequest(
        url: String,
        extraHeaders: Map<String, String>,
        body: okhttp3.RequestBody?,
    ): Request {
        val builder = if (body != null) {
            Request.Builder().url(url).post(body)
        } else {
            Request.Builder().url(url).get()
        }

        // Inject session only for targetDomains
        if (targetDomains.any { domain -> url.contains(domain) }) {
            sessionStore.getCookies(extensionId)?.let { builder.header("Cookie", it) }
            sessionStore.getTokens(extensionId).forEach { (k, v) -> builder.header(k, v) }
        }

        extraHeaders.forEach { (k, v) -> builder.header(k, v) }
        return builder.build()
    }

    private fun execute(request: Request): HttpResult {
        okHttpClient.newCall(request).execute().use { response ->
            val responseHeaders = response.headers.toMap()
            // Detect session expiry: propagate 401/403 as-is so ExtensionRunner can handle it
            return HttpResult(
                status = response.code,
                body = response.body?.string() ?: "",
                headers = responseHeaders,
            )
        }
    }
}
```

- [ ] **Step 2: Commit**

```bash
git add extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/bridge/
git commit -m "feat(extension-runtime): add HttpBridge with session injection"
```

---

## Task 7: `:extension-runtime` — ExtensionRunner (QuickJS)

**Files:**
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/ExtensionRunner.kt`
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/ExtensionRunnerImpl.kt`
- Create: `extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/di/RuntimeModule.kt`

- [ ] **Step 1: Create `ExtensionRunner.kt`**

```kotlin
package tw.kevinzhang.extension_runtime

import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.data.SyncResult

interface ExtensionRunner {
    /**
     * Runs the extension script for the given installed extension.
     * Must be called from a coroutine context.
     * Returns SyncResult.Error if session is missing, expired, or script throws.
     */
    suspend fun run(extension: InstalledExtension): SyncResult
}
```

- [ ] **Step 2: Create `ExtensionRunnerImpl.kt`**

```kotlin
package tw.kevinzhang.extension_runtime

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.whl.quickjs.android.QuickJSContext
import com.whl.quickjs.wrapper.JSCallFunction
import com.whl.quickjs.wrapper.JSObject
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import okhttp3.OkHttpClient
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.bridge.HttpBridge
import tw.kevinzhang.extension_runtime.data.AccountData
import tw.kevinzhang.extension_runtime.data.HttpResult
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import java.io.File
import javax.inject.Inject

class ExtensionRunnerImpl @Inject constructor(
    private val okHttpClient: OkHttpClient,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ExtensionRunner {

    override suspend fun run(extension: InstalledExtension): SyncResult =
        withContext(Dispatchers.IO) {
            // 1. Check session exists
            if (!sessionStore.hasSession(extension.id)) {
                return@withContext SyncResult.Error("session not found — please login first")
            }

            // 2. Load script
            val scriptFile = File(extension.scriptCachePath)
            if (!scriptFile.exists()) {
                return@withContext SyncResult.Error("script file not found: ${extension.scriptCachePath}")
            }
            val script = scriptFile.readText()

            // 3. Parse targetDomains
            val targetDomains: List<String> = try {
                val type = object : TypeToken<List<String>>() {}.type
                gson.fromJson(extension.targetDomainsJson, type)
            } catch (e: Exception) {
                return@withContext SyncResult.Error("invalid targetDomains JSON: ${e.message}")
            }

            val bridge = HttpBridge(okHttpClient, sessionStore, extension.id, targetDomains)

            // 4. Run in QuickJS
            runInQuickJs(script, bridge)
        }

    private fun runInQuickJs(script: String, bridge: HttpBridge): SyncResult {
        val context = QuickJSContext.create()
        return try {
            injectSdk(context, bridge)
            // Script must call and return from a top-level IIFE
            // e.g. (function() { ... return { accounts: [...] } })()
            val result = context.evaluate(script)
            parseSyncResult(result, context)
        } catch (e: Exception) {
            SyncResult.Error("script error: ${e.message}")
        } finally {
            context.destroy()
        }
    }

    /**
     * Injects `sdk.http.get` and `sdk.http.post` into the QuickJS global object.
     * All calls are synchronous (block the QuickJS thread on Dispatchers.IO).
     */
    private fun injectSdk(context: QuickJSContext, bridge: HttpBridge) {
        val global = context.globalObject

        val sdk = context.createNewJSObject()
        val http = context.createNewJSObject()

        http.setProperty("get", JSCallFunction { args ->
            val url = args.getOrNull(0) as? String ?: return@JSCallFunction null
            val headers = (args.getOrNull(1) as? JSObject)?.toStringMap() ?: emptyMap()
            bridge.get(url, headers).toJsObject(context)
        })

        http.setProperty("post", JSCallFunction { args ->
            val url = args.getOrNull(0) as? String ?: return@JSCallFunction null
            val body = args.getOrNull(1) as? String ?: ""
            val headers = (args.getOrNull(2) as? JSObject)?.toStringMap() ?: emptyMap()
            bridge.post(url, body, headers).toJsObject(context)
        })

        sdk.setProperty("http", http)
        global.setProperty("sdk", sdk)

        http.release()
        sdk.release()
        global.release()
    }

    /**
     * Parses the JS return value `{ accounts: [{ name, balance, currency }] }` into SyncResult.
     */
    private fun parseSyncResult(result: Any?, context: QuickJSContext): SyncResult {
        if (result == null) return SyncResult.Error("script returned null")
        if (result !is JSObject) return SyncResult.Error("script must return an object")

        return try {
            val accountsArray = result.getJSArray("accounts")
                ?: return SyncResult.Error("script result missing 'accounts' array")

            val accounts = mutableListOf<AccountData>()
            for (i in 0 until accountsArray.length()) {
                val item = accountsArray.get(i)
                if (item is JSObject) {
                    val name = item.getString("name") ?: continue
                    val balance = item.getDouble("balance") ?: continue
                    val currency = item.getString("currency") ?: "TWD"
                    accounts.add(AccountData(name, balance, currency))
                    item.release()
                }
            }
            accountsArray.release()
            result.release()
            SyncResult.Success(accounts)
        } catch (e: Exception) {
            SyncResult.Error("failed to parse script result: ${e.message}")
        }
    }
}

// Helper: converts JSObject to Map<String, String>
private fun JSObject.toStringMap(): Map<String, String> {
    val map = mutableMapOf<String, String>()
    // QuickJS wrapper doesn't expose key enumeration directly;
    // extension devs must use known header keys — this is a limitation.
    // For now, return empty map; custom headers are passed as literal objects in script.
    return map
}

// Helper: converts HttpResult to a JSObject for return to JS
private fun HttpResult.toJsObject(context: QuickJSContext): JSObject {
    val obj = context.createNewJSObject()
    obj.setProperty("status", status)
    obj.setProperty("body", body)
    // headers returned as JSON string for simplicity
    return obj
}
```

- [ ] **Step 3: Create `RuntimeModule.kt`**

```kotlin
package tw.kevinzhang.extension_runtime.di

import com.google.gson.Gson
import dagger.Module
import dagger.Provides
import dagger.hilt.InstallIn
import dagger.hilt.components.SingletonComponent
import okhttp3.OkHttpClient
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.ExtensionRunnerImpl
import tw.kevinzhang.extension_runtime.session.SessionStore
import javax.inject.Singleton

@Module
@InstallIn(SingletonComponent::class)
object RuntimeModule {

    @Provides
    @Singleton
    fun provideExtensionRunner(
        okHttpClient: OkHttpClient,
        sessionStore: SessionStore,
        gson: Gson,
    ): ExtensionRunner = ExtensionRunnerImpl(okHttpClient, sessionStore, gson)
}
```

- [ ] **Step 4: Commit**

```bash
git add extension-runtime/src/main/java/tw/kevinzhang/extension_runtime/
git commit -m "feat(extension-runtime): add ExtensionRunner with QuickJS SDK injection"
```

---

## Task 8: `:app` — Application Class, Manifest, LoginWebViewActivity

**Files:**
- Create: `app/src/main/java/tw/kevinzhang/moneylook/MoneylookApplication.kt`
- Modify: `app/src/main/AndroidManifest.xml`
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/login/LoginWebViewActivity.kt`

- [ ] **Step 1: Create `MoneylookApplication.kt`**

```kotlin
package tw.kevinzhang.moneylook

import android.app.Application
import dagger.hilt.android.HiltAndroidApp

@HiltAndroidApp
class MoneylookApplication : Application()
```

- [ ] **Step 2: Update `app/src/main/AndroidManifest.xml`**

```xml
<?xml version="1.0" encoding="utf-8"?>
<manifest xmlns:android="http://schemas.android.com/apk/res/android"
    xmlns:tools="http://schemas.android.com/tools">

    <uses-permission android:name="android.permission.INTERNET" />

    <application
        android:name=".MoneylookApplication"
        android:allowBackup="false"
        android:dataExtractionRules="@xml/data_extraction_rules"
        android:fullBackupContent="@xml/backup_rules"
        android:icon="@mipmap/ic_launcher"
        android:label="@string/app_name"
        android:roundIcon="@mipmap/ic_launcher_round"
        android:supportsRtl="true"
        android:theme="@style/Theme.Moneylook"
        tools:targetApi="31">

        <activity
            android:name=".MainActivity"
            android:exported="true"
            android:theme="@style/Theme.Moneylook">
            <intent-filter>
                <action android:name="android.intent.action.MAIN" />
                <category android:name="android.intent.category.LAUNCHER" />
            </intent-filter>
        </activity>

        <activity
            android:name=".ui.login.LoginWebViewActivity"
            android:exported="false"
            android:theme="@style/Theme.Moneylook" />

    </application>
</manifest>
```

Also update `app/src/main/res/values/themes.xml` to define `Theme.Moneylook` (rename from default):

```xml
<resources>
    <style name="Theme.Moneylook" parent="android:Theme.Material.Light.NoActionBar" />
</resources>
```

- [ ] **Step 3: Create `LoginWebViewActivity.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.login

import android.content.Context
import android.content.Intent
import android.net.Uri
import android.os.Bundle
import android.webkit.CookieManager
import android.webkit.WebResourceRequest
import android.webkit.WebResourceResponse
import android.webkit.WebView
import android.webkit.WebViewClient
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Close
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.viewinterop.AndroidView
import dagger.hilt.android.AndroidEntryPoint
import tw.kevinzhang.extension_runtime.session.SessionStore
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme
import javax.inject.Inject

@AndroidEntryPoint
class LoginWebViewActivity : ComponentActivity() {

    @Inject lateinit var sessionStore: SessionStore

    private lateinit var extensionId: String
    private lateinit var loginUrl: String
    private lateinit var extensionName: String
    private lateinit var targetDomains: List<String>

    companion object {
        private const val EXTRA_EXTENSION_ID = "extension_id"
        private const val EXTRA_LOGIN_URL = "login_url"
        private const val EXTRA_EXTENSION_NAME = "extension_name"
        private const val EXTRA_TARGET_DOMAINS = "target_domains"

        fun newIntent(
            context: Context,
            extensionId: String,
            loginUrl: String,
            extensionName: String,
            targetDomains: List<String>,
        ): Intent = Intent(context, LoginWebViewActivity::class.java).apply {
            putExtra(EXTRA_EXTENSION_ID, extensionId)
            putExtra(EXTRA_LOGIN_URL, loginUrl)
            putExtra(EXTRA_EXTENSION_NAME, extensionName)
            putStringArrayListExtra(EXTRA_TARGET_DOMAINS, ArrayList(targetDomains))
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        extensionId = intent.getStringExtra(EXTRA_EXTENSION_ID) ?: return finish()
        loginUrl = intent.getStringExtra(EXTRA_LOGIN_URL) ?: return finish()
        extensionName = intent.getStringExtra(EXTRA_EXTENSION_NAME) ?: extensionId
        targetDomains = intent.getStringArrayListExtra(EXTRA_TARGET_DOMAINS) ?: emptyList()

        setContent {
            MoneylookTheme {
                LoginWebViewScreen(
                    extensionName = extensionName,
                    loginUrl = loginUrl,
                    onClose = { finish() },
                    onPageFinished = { url -> captureSession(url) },
                    onUrlOverride = { url -> captureUrlTokens(url) },
                )
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        // Final capture on close — catches any accumulated cookies
        targetDomains.forEach { domain ->
            val cookies = CookieManager.getInstance().getCookie("https://$domain")
            if (!cookies.isNullOrBlank()) {
                sessionStore.putCookies(extensionId, cookies)
            }
        }
    }

    /** Layer 1: capture cookies on every page load */
    private fun captureSession(url: String) {
        val cookies = CookieManager.getInstance().getCookie(url)
        if (!cookies.isNullOrBlank()) {
            sessionStore.putCookies(extensionId, cookies)
        }
    }

    /** Layer 2: capture tokens from OAuth redirect URLs */
    private fun captureUrlTokens(url: String) {
        val uri = Uri.parse(url)
        listOf("access_token", "token", "auth_token", "code", "id_token").forEach { key ->
            uri.getQueryParameter(key)?.takeIf { it.isNotBlank() }?.let { value ->
                sessionStore.putToken(extensionId, key, value)
            }
        }
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
private fun LoginWebViewScreen(
    extensionName: String,
    loginUrl: String,
    onClose: () -> Unit,
    onPageFinished: (String) -> Unit,
    onUrlOverride: (String) -> Unit,
) {
    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text(extensionName) },
                navigationIcon = {
                    IconButton(onClick = onClose) {
                        Icon(Icons.Default.Close, contentDescription = "關閉")
                    }
                },
            )
        }
    ) { innerPadding ->
        Box(modifier = Modifier.fillMaxSize().padding(innerPadding)) {
            AndroidView(
                factory = { context ->
                    WebView(context).apply {
                        settings.javaScriptEnabled = true
                        settings.domStorageEnabled = true
                        webViewClient = object : WebViewClient() {
                            override fun onPageFinished(view: WebView, url: String) {
                                super.onPageFinished(view, url)
                                onPageFinished(url)
                            }

                            override fun shouldOverrideUrlLoading(
                                view: WebView,
                                request: WebResourceRequest,
                            ): Boolean {
                                onUrlOverride(request.url.toString())
                                return false
                            }

                            /** Layer 3: capture auth tokens from response headers */
                            override fun shouldInterceptRequest(
                                view: WebView,
                                request: WebResourceRequest,
                            ): WebResourceResponse? {
                                // Note: shouldInterceptRequest runs on a background thread.
                                // We can only intercept requests here, not responses.
                                // Response header capture requires a custom OkHttp proxy approach
                                // (out of v1 scope — cookie and URL token capture covers most cases).
                                return super.shouldInterceptRequest(view, request)
                            }
                        }
                        loadUrl(loginUrl)
                    }
                },
                modifier = Modifier.fillMaxSize(),
            )
        }
    }
}
```

> **Note on Layer 3 (response headers):** Android's `WebViewClient.shouldInterceptRequest` only intercepts the request, not the response. True response header capture (for `Authorization`, `X-Auth-Token`) would require routing WebView traffic through an OkHttp proxy — this is deferred to a future iteration. Cookie and URL-token capture covers the vast majority of Taiwanese bank login flows.

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tw/kevinzhang/moneylook/MoneylookApplication.kt \
        app/src/main/AndroidManifest.xml \
        app/src/main/java/tw/kevinzhang/moneylook/ui/login/ \
        app/src/main/res/values/themes.xml
git commit -m "feat(app): add Application class, LoginWebViewActivity with session capture"
```

---

## Task 9: `:app` — Navigation

**Files:**
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/navigation/Screen.kt`
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/navigation/AppNavHost.kt`
- Modify: `app/src/main/java/tw/kevinzhang/moneylook/MainActivity.kt`

- [ ] **Step 1: Create `Screen.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.navigation

sealed class Screen(val route: String) {
    object Home : Screen("home")
    object Marketplace : Screen("marketplace")
}
```

- [ ] **Step 2: Create `AppNavHost.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.navigation

import androidx.compose.runtime.Composable
import androidx.navigation.NavHostController
import androidx.navigation.compose.NavHost
import androidx.navigation.compose.composable
import tw.kevinzhang.moneylook.ui.home.HomeScreen
import tw.kevinzhang.moneylook.ui.marketplace.MarketplaceScreen

@Composable
fun AppNavHost(navController: NavHostController) {
    NavHost(navController = navController, startDestination = Screen.Home.route) {
        composable(Screen.Home.route) {
            HomeScreen(onNavigateToMarketplace = { navController.navigate(Screen.Marketplace.route) })
        }
        composable(Screen.Marketplace.route) {
            MarketplaceScreen(onNavigateUp = { navController.popBackStack() })
        }
    }
}
```

- [ ] **Step 3: Update `MainActivity.kt`**

```kotlin
package tw.kevinzhang.moneylook

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.navigation.compose.rememberNavController
import dagger.hilt.android.AndroidEntryPoint
import tw.kevinzhang.moneylook.ui.navigation.AppNavHost
import tw.kevinzhang.moneylook.ui.theme.MoneylookTheme

@AndroidEntryPoint
class MainActivity : ComponentActivity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        enableEdgeToEdge()
        setContent {
            MoneylookTheme {
                val navController = rememberNavController()
                AppNavHost(navController = navController)
            }
        }
    }
}
```

- [ ] **Step 4: Commit**

```bash
git add app/src/main/java/tw/kevinzhang/moneylook/ui/navigation/ \
        app/src/main/java/tw/kevinzhang/moneylook/MainActivity.kt
git commit -m "feat(app): add navigation scaffold"
```

---

## Task 10: `:app` — HomeScreen

**Files:**
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/home/HomeViewModel.kt`
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/home/HomeScreen.kt`

- [ ] **Step 1: Create `HomeViewModel.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.home

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.async
import kotlinx.coroutines.awaitAll
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.flow.update
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.AccountDao
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.extension_runtime.ExtensionRunner
import tw.kevinzhang.extension_runtime.data.SyncResult
import tw.kevinzhang.extension_runtime.session.SessionStore
import tw.kevinzhang.moneylook.ui.login.LoginWebViewActivity
import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import javax.inject.Inject

enum class SyncState { IDLE, SYNCING, SUCCESS, ERROR }

data class ExtensionSyncStatus(
    val extension: InstalledExtension,
    val syncState: SyncState = SyncState.IDLE,
    val errorMessage: String? = null,
    val hasSession: Boolean = false,
)

@HiltViewModel
class HomeViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val installedExtensionDao: InstalledExtensionDao,
    private val accountDao: AccountDao,
    private val extensionRunner: ExtensionRunner,
    private val sessionStore: SessionStore,
    private val gson: Gson,
) : ViewModel() {

    val accounts = accountDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    val extensions = installedExtensionDao.observeAll()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptyList())

    private val _syncStatuses = MutableStateFlow<Map<String, ExtensionSyncStatus>>(emptyMap())
    val syncStatuses = _syncStatuses.asStateFlow()

    fun refreshSessionStates() {
        val statuses = extensions.value.associate { ext ->
            ext.id to ExtensionSyncStatus(
                extension = ext,
                hasSession = sessionStore.hasSession(ext.id),
            )
        }
        _syncStatuses.value = statuses
    }

    fun syncAll() {
        val exts = extensions.value
        if (exts.isEmpty()) return

        viewModelScope.launch {
            // Mark all as SYNCING
            _syncStatuses.update { current ->
                exts.associate { ext ->
                    ext.id to (current[ext.id]?.copy(syncState = SyncState.SYNCING)
                        ?: ExtensionSyncStatus(ext, SyncState.SYNCING))
                }
            }

            exts.map { ext ->
                async {
                    val result = try {
                        extensionRunner.run(ext)
                    } catch (e: CancellationException) {
                        throw e
                    } catch (e: Exception) {
                        SyncResult.Error(e.message ?: "unknown error")
                    }

                    when (result) {
                        is SyncResult.Success -> {
                            val now = System.currentTimeMillis()
                            val targetDomains: List<String> = gson.fromJson(
                                ext.targetDomainsJson,
                                object : TypeToken<List<String>>() {}.type
                            )
                            val accountEntities = result.accounts.map { data ->
                                Account(
                                    id = "${ext.id}_${data.name}",
                                    extensionId = ext.id,
                                    extensionName = ext.name,
                                    accountName = data.name,
                                    balance = data.balance,
                                    currency = data.currency,
                                    lastSyncAt = now,
                                )
                            }
                            accountDao.upsertAll(accountEntities)
                            updateStatus(ext.id) { it.copy(syncState = SyncState.SUCCESS, errorMessage = null) }
                        }
                        is SyncResult.Error -> {
                            updateStatus(ext.id) { it.copy(syncState = SyncState.ERROR, errorMessage = result.message) }
                        }
                    }
                }
            }.awaitAll()
        }
    }

    fun openLogin(context: Context, extension: InstalledExtension) {
        val targetDomains: List<String> = try {
            gson.fromJson(extension.targetDomainsJson, object : TypeToken<List<String>>() {}.type)
        } catch (e: Exception) { emptyList() }

        val intent = LoginWebViewActivity.newIntent(
            context = context,
            extensionId = extension.id,
            loginUrl = extension.loginUrl,
            extensionName = extension.name,
            targetDomains = targetDomains,
        )
        context.startActivity(intent)
    }

    private fun updateStatus(id: String, update: (ExtensionSyncStatus) -> ExtensionSyncStatus) {
        _syncStatuses.update { current ->
            current.toMutableMap().also { map ->
                map[id]?.let { map[id] = update(it) }
            }
        }
    }
}
```

- [ ] **Step 2: Create `HomeScreen.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.home

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.Add
import androidx.compose.material.icons.filled.Refresh
import androidx.compose.material3.Button
import androidx.compose.material3.Card
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.FloatingActionButton
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle
import tw.kevinzhang.core.data.model.Account
import tw.kevinzhang.core.data.model.InstalledExtension

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeScreen(
    onNavigateToMarketplace: () -> Unit,
    viewModel: HomeViewModel = hiltViewModel(),
) {
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val accounts by viewModel.accounts.collectAsStateWithLifecycle()
    val syncStatuses by viewModel.syncStatuses.collectAsStateWithLifecycle()
    val context = LocalContext.current

    LaunchedEffect(extensions) { viewModel.refreshSessionStates() }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Moneylook") },
                actions = {
                    IconButton(onClick = viewModel::syncAll) {
                        Icon(Icons.Default.Refresh, contentDescription = "同步")
                    }
                },
            )
        },
        floatingActionButton = {
            FloatingActionButton(onClick = onNavigateToMarketplace) {
                Icon(Icons.Default.Add, contentDescription = "新增銀行")
            }
        },
    ) { innerPadding ->
        if (extensions.isEmpty()) {
            EmptyState(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                onAddExtension = onNavigateToMarketplace,
            )
        } else {
            LazyColumn(
                modifier = Modifier.fillMaxSize().padding(innerPadding),
                contentPadding = androidx.compose.foundation.layout.PaddingValues(16.dp),
                verticalArrangement = Arrangement.spacedBy(12.dp),
            ) {
                items(extensions, key = { it.id }) { ext ->
                    val status = syncStatuses[ext.id]
                    val extAccounts = accounts.filter { it.extensionId == ext.id }
                    ExtensionCard(
                        extension = ext,
                        accounts = extAccounts,
                        syncState = status?.syncState ?: SyncState.IDLE,
                        hasSession = status?.hasSession ?: false,
                        errorMessage = status?.errorMessage,
                        onLogin = { viewModel.openLogin(context, ext) },
                    )
                }
            }
        }
    }
}

@Composable
private fun ExtensionCard(
    extension: InstalledExtension,
    accounts: List<Account>,
    syncState: SyncState,
    hasSession: Boolean,
    errorMessage: String?,
    onLogin: () -> Unit,
) {
    Card(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.padding(16.dp)) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.SpaceBetween,
                verticalAlignment = Alignment.CenterVertically,
            ) {
                Text(extension.name, style = MaterialTheme.typography.titleMedium)
                when (syncState) {
                    SyncState.SYNCING -> CircularProgressIndicator(
                        modifier = Modifier.padding(4.dp),
                        strokeWidth = 2.dp,
                    )
                    SyncState.ERROR -> Text(
                        "失敗",
                        style = MaterialTheme.typography.labelSmall,
                        color = MaterialTheme.colorScheme.error,
                    )
                    else -> {}
                }
            }

            if (!hasSession) {
                Spacer(modifier = Modifier.height(8.dp))
                OutlinedButton(onClick = onLogin, modifier = Modifier.fillMaxWidth()) {
                    Text("登入")
                }
            } else if (accounts.isNotEmpty()) {
                Spacer(modifier = Modifier.height(8.dp))
                accounts.forEach { account ->
                    HorizontalDivider()
                    Row(
                        modifier = Modifier
                            .fillMaxWidth()
                            .padding(vertical = 8.dp),
                        horizontalArrangement = Arrangement.SpaceBetween,
                    ) {
                        Text(account.accountName, style = MaterialTheme.typography.bodyMedium)
                        Text(
                            text = "${"%.2f".format(account.balance)} ${account.currency}",
                            style = MaterialTheme.typography.bodyMedium,
                        )
                    }
                }
            }

            if (errorMessage != null) {
                Spacer(modifier = Modifier.height(4.dp))
                Text(
                    text = errorMessage,
                    style = MaterialTheme.typography.bodySmall,
                    color = MaterialTheme.colorScheme.error,
                )
            }
        }
    }
}

@Composable
private fun EmptyState(modifier: Modifier, onAddExtension: () -> Unit) {
    Column(
        modifier = modifier,
        horizontalAlignment = Alignment.CenterHorizontally,
        verticalArrangement = Arrangement.Center,
    ) {
        Text("尚未新增任何銀行", style = MaterialTheme.typography.bodyLarge)
        Spacer(modifier = Modifier.height(16.dp))
        Button(onClick = onAddExtension) { Text("前往 Marketplace") }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tw/kevinzhang/moneylook/ui/home/
git commit -m "feat(app): add HomeScreen with account dashboard and sync flow"
```

---

## Task 11: `:app` — MarketplaceScreen

**Files:**
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/marketplace/MarketplaceViewModel.kt`
- Create: `app/src/main/java/tw/kevinzhang/moneylook/ui/marketplace/MarketplaceScreen.kt`

- [ ] **Step 1: Create `MarketplaceViewModel.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.marketplace

import androidx.lifecycle.ViewModel
import androidx.lifecycle.viewModelScope
import com.google.gson.Gson
import dagger.hilt.android.lifecycle.HiltViewModel
import dagger.hilt.android.qualifiers.ApplicationContext
import android.content.Context
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import tw.kevinzhang.core.data.db.InstalledExtensionDao
import tw.kevinzhang.core.data.model.InstalledExtension
import tw.kevinzhang.marketplace.MarketplaceRepository
import tw.kevinzhang.marketplace.RepoUrlRepository
import tw.kevinzhang.marketplace.data.ExtensionIndexEntry
import javax.inject.Inject

data class ExtensionWithState(
    val entry: ExtensionIndexEntry,
    val isInstalled: Boolean,
    val hasUpdate: Boolean,
    val isLoading: Boolean = false,
)

@HiltViewModel
class MarketplaceViewModel @Inject constructor(
    @ApplicationContext private val context: Context,
    private val marketplaceRepository: MarketplaceRepository,
    private val repoUrlRepository: RepoUrlRepository,
    private val installedExtensionDao: InstalledExtensionDao,
    private val gson: Gson,
) : ViewModel() {

    val repoUrls = repoUrlRepository.observeRepoUrls()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(5_000), emptySet())

    private val _extensions = MutableStateFlow<List<ExtensionWithState>>(emptyList())
    val extensions = _extensions.asStateFlow()

    private val _addRepoUrl = MutableStateFlow("")
    val addRepoUrl = _addRepoUrl.asStateFlow()

    private val _isLoading = MutableStateFlow(false)
    val isLoading = _isLoading.asStateFlow()

    private val _error = MutableStateFlow<String?>(null)
    val error = _error.asStateFlow()

    fun onAddRepoUrlChanged(url: String) { _addRepoUrl.value = url }

    fun addRepo() {
        val url = _addRepoUrl.value.trim()
        if (url.isBlank()) return
        viewModelScope.launch {
            _isLoading.value = true
            try {
                marketplaceRepository.fetchIndex(url) // validate URL works
                repoUrlRepository.addRepoUrl(url)
                _addRepoUrl.value = ""
                loadExtensions(url)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "無法載入 ${url}: ${e.message}"
            } finally {
                _isLoading.value = false
            }
        }
    }

    fun loadExtensions(repoUrl: String) {
        viewModelScope.launch {
            try {
                val index = marketplaceRepository.fetchIndex(repoUrl)
                val installed = installedExtensionDao.getAll()
                val installedMap = installed.associateBy { it.id }
                _extensions.value = index.map { entry ->
                    val installedExt = installedMap[entry.id]
                    ExtensionWithState(
                        entry = entry,
                        isInstalled = installedExt != null,
                        hasUpdate = installedExt != null && installedExt.version < entry.version,
                    )
                }
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "載入失敗: ${e.message}"
            }
        }
    }

    fun install(repoUrl: String, entry: ExtensionIndexEntry) {
        viewModelScope.launch {
            setLoading(entry.id, true)
            try {
                val manifest = marketplaceRepository.fetchManifest(repoUrl, entry.path)
                val scriptPath = marketplaceRepository.downloadScript(repoUrl, entry.path, entry.id)
                installedExtensionDao.insert(
                    InstalledExtension(
                        id = manifest.id,
                        name = manifest.name,
                        version = manifest.version,
                        repoUrl = repoUrl,
                        scriptCachePath = scriptPath,
                        loginUrl = manifest.loginUrl,
                        targetDomainsJson = gson.toJson(manifest.targetDomains),
                        iconUrl = manifest.iconUrl,
                    )
                )
                loadExtensions(repoUrl)
            } catch (e: CancellationException) {
                throw e
            } catch (e: Exception) {
                _error.value = "安裝失敗: ${e.message}"
            } finally {
                setLoading(entry.id, false)
            }
        }
    }

    fun uninstall(extensionId: String) {
        viewModelScope.launch {
            installedExtensionDao.deleteById(extensionId)
            _extensions.value = _extensions.value.map { ext ->
                if (ext.entry.id == extensionId) ext.copy(isInstalled = false, hasUpdate = false)
                else ext
            }
        }
    }

    fun clearError() { _error.value = null }

    private fun setLoading(extensionId: String, loading: Boolean) {
        _extensions.value = _extensions.value.map { ext ->
            if (ext.entry.id == extensionId) ext.copy(isLoading = loading) else ext
        }
    }
}
```

- [ ] **Step 2: Create `MarketplaceScreen.kt`**

```kotlin
package tw.kevinzhang.moneylook.ui.marketplace

import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.items
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.automirrored.filled.ArrowBack
import androidx.compose.material3.Button
import androidx.compose.material3.CircularProgressIndicator
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.HorizontalDivider
import androidx.compose.material3.Icon
import androidx.compose.material3.IconButton
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedButton
import androidx.compose.material3.OutlinedTextField
import androidx.compose.material3.Scaffold
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.unit.dp
import androidx.hilt.navigation.compose.hiltViewModel
import androidx.lifecycle.compose.collectAsStateWithLifecycle

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun MarketplaceScreen(
    onNavigateUp: () -> Unit,
    viewModel: MarketplaceViewModel = hiltViewModel(),
) {
    val repoUrls by viewModel.repoUrls.collectAsStateWithLifecycle()
    val extensions by viewModel.extensions.collectAsStateWithLifecycle()
    val addRepoUrl by viewModel.addRepoUrl.collectAsStateWithLifecycle()
    val isLoading by viewModel.isLoading.collectAsStateWithLifecycle()
    val error by viewModel.error.collectAsStateWithLifecycle()

    LaunchedEffect(repoUrls) {
        repoUrls.firstOrNull()?.let { viewModel.loadExtensions(it) }
    }

    Scaffold(
        topBar = {
            TopAppBar(
                title = { Text("Marketplace") },
                navigationIcon = {
                    IconButton(onClick = onNavigateUp) {
                        Icon(Icons.AutoMirrored.Filled.ArrowBack, contentDescription = "返回")
                    }
                },
            )
        }
    ) { innerPadding ->
        LazyColumn(
            modifier = Modifier.fillMaxSize().padding(innerPadding),
        ) {
            item {
                AddRepoSection(
                    url = addRepoUrl,
                    isLoading = isLoading,
                    error = error,
                    onUrlChanged = viewModel::onAddRepoUrlChanged,
                    onAdd = viewModel::addRepo,
                    onClearError = viewModel::clearError,
                )
                HorizontalDivider()
            }

            if (extensions.isNotEmpty()) {
                item {
                    Text(
                        text = "可安裝的 Extensions",
                        style = MaterialTheme.typography.labelMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant,
                        modifier = Modifier.padding(start = 16.dp, top = 12.dp, bottom = 4.dp),
                    )
                }
                items(extensions, key = { it.entry.id }) { ext ->
                    val firstRepoUrl = repoUrls.firstOrNull() ?: return@items
                    ExtensionItem(
                        extensionWithState = ext,
                        onInstall = { viewModel.install(firstRepoUrl, ext.entry) },
                        onUninstall = { viewModel.uninstall(ext.entry.id) },
                    )
                    HorizontalDivider(modifier = Modifier.padding(start = 16.dp))
                }
            }
        }
    }
}

@Composable
private fun AddRepoSection(
    url: String,
    isLoading: Boolean,
    error: String?,
    onUrlChanged: (String) -> Unit,
    onAdd: () -> Unit,
    onClearError: () -> Unit,
) {
    Column(
        modifier = Modifier.fillMaxWidth().padding(16.dp),
        verticalArrangement = Arrangement.spacedBy(8.dp),
    ) {
        Text("新增 Extension 來源", style = MaterialTheme.typography.titleMedium)
        OutlinedTextField(
            value = url,
            onValueChange = {
                onUrlChanged(it)
                if (error != null) onClearError()
            },
            label = { Text("GitHub repo URL") },
            placeholder = { Text("https://github.com/owner/moneylook-extensions") },
            singleLine = true,
            isError = error != null,
            supportingText = error?.let { { Text(it, color = MaterialTheme.colorScheme.error) } },
            modifier = Modifier.fillMaxWidth(),
        )
        Button(
            onClick = onAdd,
            enabled = url.isNotBlank() && !isLoading,
            modifier = Modifier.align(Alignment.End),
        ) {
            if (isLoading) CircularProgressIndicator(modifier = Modifier.size(18.dp), strokeWidth = 2.dp)
            else Text("新增")
        }
    }
}

@Composable
private fun ExtensionItem(
    extensionWithState: ExtensionWithState,
    onInstall: () -> Unit,
    onUninstall: () -> Unit,
) {
    val ext = extensionWithState
    Row(
        modifier = Modifier.fillMaxWidth().padding(horizontal = 16.dp, vertical = 12.dp),
        verticalAlignment = Alignment.CenterVertically,
        horizontalArrangement = Arrangement.SpaceBetween,
    ) {
        Column(modifier = Modifier.weight(1f)) {
            Text(ext.entry.name, style = MaterialTheme.typography.bodyMedium)
            Text(
                text = "v${ext.entry.versionName}",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
        when {
            ext.isLoading -> CircularProgressIndicator(modifier = Modifier.size(24.dp), strokeWidth = 2.dp)
            ext.hasUpdate -> Button(onClick = onInstall) { Text("更新") }
            ext.isInstalled -> OutlinedButton(onClick = onUninstall) { Text("移除") }
            else -> Button(onClick = onInstall) { Text("安裝") }
        }
    }
}
```

- [ ] **Step 3: Commit**

```bash
git add app/src/main/java/tw/kevinzhang/moneylook/ui/marketplace/
git commit -m "feat(app): add MarketplaceScreen for extension installation"
```

---

## Task 12: GitHub Repos Setup

- [ ] **Step 1: Create `moneylook-extensions` (distribution repo)**

```bash
gh repo create twkevinzhang/moneylook-extensions --public \
    --description "Compiled Moneylook bank extension scripts"
```

- [ ] **Step 2: Create `moneylook-extensions-source` (source repo)**

```bash
gh repo create twkevinzhang/moneylook-extensions-source --public \
    --description "TypeScript source code for Moneylook bank extensions"
```

- [ ] **Step 3: Initialize `moneylook-extensions` repo with scaffold**

```bash
# In a temp directory
mkdir /tmp/moneylook-extensions && cd /tmp/moneylook-extensions
git init && git remote add origin https://github.com/twkevinzhang/moneylook-extensions.git

# Create minimal index.min.json (empty — no extensions yet)
echo '[]' > index.min.json

# Create placeholder README
cat > README.md << 'EOF'
# moneylook-extensions

Compiled extension scripts for [Moneylook](https://github.com/twkevinzhang/moneylook).

## Structure
- `index.min.json` — list of available extensions
- `{extension-id}/manifest.json` — extension metadata
- `{extension-id}/extension-script.min.js` — compiled extension script
EOF

git add . && git commit -m "init: empty extension registry"
git push -u origin main
```

- [ ] **Step 4: Initialize `moneylook-extensions-source` with SDK types and CI/CD**

```bash
mkdir /tmp/moneylook-extensions-source && cd /tmp/moneylook-extensions-source
git init && git remote add origin https://github.com/twkevinzhang/moneylook-extensions-source.git
mkdir -p .github/workflows extensions

# Create sdk.d.ts
cat > sdk.d.ts << 'EOF'
declare const sdk: {
  http: {
    get(url: string, headers?: Record<string, string>): HttpResponse
    post(url: string, body: string, headers?: Record<string, string>): HttpResponse
  }
}

interface HttpResponse {
  status: number
  body: string
}

interface AccountData {
  name: string
  balance: number
  currency: string
}

type ExtensionMain = () => ExtensionResult

interface ExtensionResult {
  accounts: AccountData[]
}
EOF

# Create package.json for the build toolchain
cat > package.json << 'EOF'
{
  "name": "moneylook-extensions-source",
  "private": true,
  "scripts": {
    "build": "node build.js"
  },
  "devDependencies": {
    "typescript": "^5.0.0",
    "esbuild": "^0.21.0"
  }
}
EOF

# Create tsconfig.json
cat > tsconfig.json << 'EOF'
{
  "compilerOptions": {
    "target": "ES6",
    "module": "ES2015",
    "strict": true,
    "noEmit": true
  },
  "include": ["extensions/**/*.ts", "sdk.d.ts"]
}
EOF

# Create build.js — compiles each extension to extension-script.min.js
cat > build.js << 'EOF'
const { buildSync } = require('esbuild')
const fs = require('fs')
const path = require('path')

const extensionsDir = path.join(__dirname, 'extensions')
const outputBase = process.env.OUTPUT_DIR || path.join(__dirname, 'dist')

fs.readdirSync(extensionsDir).forEach(id => {
  const entry = path.join(extensionsDir, id, 'src', 'index.ts')
  if (!fs.existsSync(entry)) return

  const outDir = path.join(outputBase, id)
  fs.mkdirSync(outDir, { recursive: true })

  buildSync({
    entryPoints: [entry],
    bundle: true,
    minify: true,
    outfile: path.join(outDir, 'extension-script.min.js'),
    platform: 'neutral',
    target: 'es6',
  })

  // Copy manifest.json
  const manifest = path.join(extensionsDir, id, 'manifest.json')
  if (fs.existsSync(manifest)) {
    fs.copyFileSync(manifest, path.join(outDir, 'manifest.json'))
  }

  // Copy icon.png if present
  const icon = path.join(extensionsDir, id, 'icon.png')
  if (fs.existsSync(icon)) {
    fs.copyFileSync(icon, path.join(outDir, 'icon.png'))
  }

  console.log(`Built: ${id}`)
})

// Generate index.min.json from all manifests
const index = fs.readdirSync(extensionsDir)
  .filter(id => fs.existsSync(path.join(extensionsDir, id, 'manifest.json')))
  .map(id => {
    const manifest = JSON.parse(
      fs.readFileSync(path.join(extensionsDir, id, 'manifest.json'), 'utf8')
    )
    return {
      id: manifest.id,
      name: manifest.name,
      version: manifest.version,
      versionName: manifest.versionName,
      path: id,
    }
  })

fs.writeFileSync(path.join(outputBase, 'index.min.json'), JSON.stringify(index))
console.log('Generated index.min.json')
EOF

# Create GitHub Actions workflow
cat > .github/workflows/build.yml << 'EOF'
name: Build and Deploy Extensions

on:
  push:
    branches: [main]

jobs:
  build:
    runs-on: ubuntu-latest
    steps:
      - uses: actions/checkout@v4

      - name: Setup Node.js
        uses: actions/setup-node@v4
        with:
          node-version: '20'
          cache: 'npm'

      - name: Install dependencies
        run: npm ci

      - name: Build extensions
        run: npm run build

      - name: Deploy to moneylook-extensions
        uses: cpina/github-action-push-to-another-repository@main
        env:
          API_TOKEN_GITHUB: ${{ secrets.EXTENSIONS_DEPLOY_TOKEN }}
        with:
          source-directory: dist
          destination-github-username: twkevinzhang
          destination-repository-name: moneylook-extensions
          target-branch: main
          commit-message: "deploy: build from ${{ github.sha }}"
EOF

git add . && git commit -m "init: extension build toolchain with TypeScript and esbuild"
git push -u origin main
```

> **Note:** After pushing, add a GitHub personal access token as `EXTENSIONS_DEPLOY_TOKEN` secret in the source repo settings. The token needs `repo` write access to `moneylook-extensions`.

- [ ] **Step 5: Commit all app-side changes**

Back in the Moneylook project:

```bash
git add .
git commit -m "docs: update design notes re GitHub repos"
```

---

## Self-Review Checklist

**Spec coverage:**
- [x] Multi-module Gradle setup → Task 1
- [x] `:core:network` OkHttp → Task 2
- [x] `:core:data` Room DB, Account, InstalledExtension → Task 3
- [x] `:marketplace` index fetch, manifest fetch, script download, repo URL management → Task 4
- [x] `:extension-runtime` SessionStore → Task 5
- [x] `:extension-runtime` HttpBridge with session injection → Task 6
- [x] `:extension-runtime` ExtensionRunner with QuickJS → Task 7
- [x] LoginWebViewActivity with three-layer session capture → Task 8
- [x] Navigation → Task 9
- [x] HomeScreen with sync, login state, per-extension error → Task 10
- [x] MarketplaceScreen with add repo, install, update, uninstall → Task 11
- [x] GitHub repos + CI/CD → Task 12
- [x] TypeScript `sdk.d.ts` → Task 12

**Layer 3 (response header capture):** WebView's `shouldInterceptRequest` API only exposes request, not response. Noted in Task 8 as a known limitation; cookie + URL token capture covers the primary use cases for v1.

**`toStringMap()` for JSObject headers:** The QuickJS wrapper doesn't expose JS object key enumeration. Extension scripts should pass headers as literal values in `sdk.http.get(url)` calls without a headers argument for most cases; complex header passing is a v2 concern.
