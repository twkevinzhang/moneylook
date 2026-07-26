plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

val versionNameOverride = providers.gradleProperty("APP_VERSION_NAME").orNull
val versionCodeOverride = providers.gradleProperty("APP_VERSION_CODE").orNull?.let { value ->
    value.toIntOrNull()?.takeIf { it in 1..2_100_000_000 }
        ?: error("APP_VERSION_CODE must be an integer between 1 and 2100000000")
}
fun signingValue(environmentName: String, propertyName: String): String? =
    providers.environmentVariable(environmentName)
        .filter { it.isNotBlank() }
        .orElse(providers.gradleProperty(propertyName).filter { it.isNotBlank() })
        .orNull

val signingKeystorePath = signingValue("KEYSTORE_PATH", "MONEYLOOK_KEYSTORE_PATH")
val signingKeystoreFile = signingKeystorePath?.let(::file)?.also { keystore ->
    require(keystore.isFile) { "Configured Moneylook signing keystore does not exist" }
}
val signingStorePassword = signingValue("KEYSTORE_PASSWORD", "MONEYLOOK_KEYSTORE_PASSWORD")
val signingKeyAlias = signingValue("KEY_ALIAS", "MONEYLOOK_KEY_ALIAS")
val signingKeyPassword = signingValue("KEY_PASSWORD", "MONEYLOOK_KEY_PASSWORD")

if (signingKeystoreFile != null) {
    requireNotNull(signingStorePassword) { "Moneylook signing keystore password is not configured" }
    requireNotNull(signingKeyAlias) { "Moneylook signing key alias is not configured" }
    requireNotNull(signingKeyPassword) { "Moneylook signing key password is not configured" }
}

android {
    namespace = "tw.kevinzhang.moneylook"
    compileSdk = 36
    defaultConfig {
        applicationId = "tw.kevinzhang.moneylook"
        minSdk = 24
        targetSdk = 36
        versionCode = versionCodeOverride ?: 3
        versionName = versionNameOverride ?: "1.2.0"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }
    if (signingKeystoreFile != null) {
        signingConfigs {
            create("moneylook") {
                storeFile = signingKeystoreFile
                storePassword = signingStorePassword
                keyAlias = signingKeyAlias
                keyPassword = signingKeyPassword
            }
        }
    }
    buildTypes {
        debug {
            if (signingKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("moneylook")
            }
        }
        release {
            isMinifyEnabled = false
            proguardFiles(getDefaultProguardFile("proguard-android-optimize.txt"), "proguard-rules.pro")
            if (signingKeystoreFile != null) {
                signingConfig = signingConfigs.getByName("moneylook")
            }
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_11
        targetCompatibility = JavaVersion.VERSION_11
    }
    buildFeatures {
        compose = true
    }
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
    implementation(libs.androidx.biometric)
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
    implementation(libs.gson)
    implementation(libs.work.runtime.ktx)
    implementation(libs.hilt.work)
    ksp(libs.hilt.work.compiler)
    implementation(libs.cron.utils)

    testImplementation(libs.junit4)
    testImplementation(libs.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.room.runtime)

    androidTestImplementation(platform(libs.compose.bom))
    androidTestImplementation(libs.compose.ui.test.junit4)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.junit)

    debugImplementation(libs.compose.ui.tooling)
    debugImplementation(libs.compose.ui.test.manifest)
}
