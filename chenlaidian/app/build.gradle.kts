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
        versionCode = 2
        versionName = "0.2-m2"
        // 真 token 在 GitHub Actions 的 secret 里注入；本地/无 secret 时是 dev（连不上，但能编译）
        buildConfigField("String", "WS_TOKEN", "\"${System.getenv("CHEN_WS_TOKEN") ?: "dev"}\"")
        buildConfigField("String", "SERVER_HOST", "\"45.76.170.242\"")
        buildConfigField("int", "SERVER_PORT", "8200")
    }

    buildFeatures { buildConfig = true }

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
}
