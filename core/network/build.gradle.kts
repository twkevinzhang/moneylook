plugins {
    alias(libs.plugins.android.library)
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
    buildFeatures { buildConfig = true }
}

dependencies {
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    api(libs.bundles.okhttp)
    api(libs.gson)
    implementation(libs.coroutines.android)
    testImplementation(libs.junit4)
    testImplementation(libs.robolectric)
    testImplementation(libs.okhttp.mockwebserver)
}
