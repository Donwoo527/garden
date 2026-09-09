plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}

android {
    namespace = "me.chen.laidian"
    compileSdk = 34

    defaultConfig {
        applicationId = "me.chen.laidian"
        minSdk = 26
        targetSdk = 34
        versionCode = 14
        versionName = "0.14-m5"
        // 真 token 在 GitHub Actions 的 secret 里注入；本地/无 secret 时是 dev（连不上，但能编译）
        buildConfigField("String", "WS_TOKEN", "\"${System.getenv("CHEN_WS_TOKEN") ?: "dev"}\"")
        buildConfigField("String", "SERVER_HOST", "\"45.76.170.242\"")
        buildConfigField("int", "SERVER_PORT", "8200")
        buildConfigField("int", "CHAT_PORT", "8300")
    }

    buildFeatures {
        buildConfig = true
        compose = true
    }
    composeOptions { kotlinCompilerExtensionVersion = "1.5.14" }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}

dependencies {
    implementation("androidx.core:core-ktx:1.13.1")
    implementation("androidx.appcompat:appcompat:1.7.0")
    implementation("androidx.lifecycle:lifecycle-livedata-ktx:2.8.4")
    implementation("com.google.android.material:material:1.12.0")
    implementation("com.squareup.okhttp3:okhttp:4.12.0")

    // Compose（M3 起页面用 Compose 写，动效好做）
    implementation(platform("androidx.compose:compose-bom:2024.06.00"))
    implementation("androidx.compose.ui:ui")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.runtime:runtime-livedata")
    implementation("androidx.activity:activity-compose:1.9.1")
    implementation("io.coil-kt:coil-compose:2.6.0")

    // 终端：Termux 的终端模拟器（纯 Java 的 VT 解析 + 屏幕缓冲），渲染我们自己用 Canvas 画
    implementation("com.termux.termux-app:terminal-emulator:0.118.0")
    implementation("com.google.guava:listenablefuture:9999.0-empty-to-avoid-conflict-with-guava")
}
