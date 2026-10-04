import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

// Todoist token and project name come from local.properties so they stay out of the code.
val localProps = Properties().apply {
    val f = rootProject.file("local.properties")
    if (f.exists()) f.inputStream().use { load(it) }
}
fun quoted(key: String): String {
    val raw = localProps.getProperty(key) ?: ""
    return "\"" + raw.replace("\\", "\\\\").replace("\"", "\\\"") + "\""
}

android {
    namespace = "com.calendarviewbox"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.calendarviewbox"
        minSdk = 26
        targetSdk = 35
        versionCode = 1
        versionName = "0.1"
        buildConfigField("String", "TODOIST_TOKEN", quoted("todoist.token"))
        buildConfigField("String", "TODOIST_PROJECT", quoted("todoist.project"))
        buildConfigField("String", "WEATHER_PLACE", quoted("weather.place"))
    }

    // A fixed key (kept in the repo) so every build can install over the previous one
    // without uninstalling, which would wipe the app's settings. Fine for a personal app.
    signingConfigs {
        getByName("debug") {
            storeFile = file("debug.keystore")
            storePassword = "android"
            keyAlias = "androiddebugkey"
            keyPassword = "android"
        }
    }

    buildTypes {
        release {
            isMinifyEnabled = false
            signingConfig = signingConfigs.getByName("debug")
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
}

dependencies {
    val composeBom = platform("androidx.compose:compose-bom:2024.12.01")
    implementation(composeBom)
    implementation("androidx.core:core-ktx:1.15.0")
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.foundation:foundation")
    implementation("androidx.compose.material3:material3")
}
