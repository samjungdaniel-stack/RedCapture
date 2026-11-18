plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.samjung.redcapture"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.samjung.redcapture"
        minSdk = 30          // 화면 캡처 API(takeScreenshot)가 Android 11부터 지원
        targetSdk = 34
        versionCode = 1
        versionName = "1.0"
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
