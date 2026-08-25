plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

android { namespace = "com.jonis.thirty"; compileSdk = 35
    defaultConfig { applicationId = "com.jonis.thirty"; minSdk = 24; targetSdk = 35; versionCode = 25; versionName = "3.4" }
    // Fixed debug keystore committed to the repo so EVERY build (local + CI) signs with
    // the same key. Without this, CI generates a random debug key per run, so updates
    // fail with "app not installed" (signature mismatch). It's only a debug key.
    signingConfigs {
        getByName("debug") {
            storeFile = file("jonis-debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }
    buildTypes { getByName("debug") { signingConfig = signingConfigs.getByName("debug") } }
    compileOptions { sourceCompatibility = JavaVersion.VERSION_17; targetCompatibility = JavaVersion.VERSION_17 }
}

kotlin { jvmToolchain(17) }

dependencies {
    implementation(platform("androidx.compose:compose-bom:2025.02.00"))
    implementation("androidx.activity:activity-compose:1.10.1")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.material:material-icons-extended")
    debugImplementation("androidx.compose.ui:ui-tooling")
}
