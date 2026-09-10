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
        versionCode = 33
        versionName = "0.33-m5"
        // 真 token 在 GitHub Actions 的 secret 里注入；本地/无 secret 时是 dev（连不上，但能编译）
        buildConfigField("String", "WS_TOKEN", "\"${System.getenv("CHEN_WS_TOKEN") ?: "dev"}\"")
        // 0.26 端口回滚原样(她拍板:少变量)。通路=梯子隧道(tun或socks10808均进v2ray核心→VPS内部访问)
        // 0910教训存档:8300/8200公网从来没在ufw放行过,通全靠隧道;直连在她的网络回程被掐,行不通
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
    // 0.16: 9999.0-empty 是"树里已有完整guava"时用的空壳，本项目没有guava，
    // 空壳把 ListenableFuture 挤出 APK → profileinstaller 启动几秒后 NoClassDefFound 闪退(0.13起)
    implementation("com.google.guava:listenablefuture:1.0")
}
