plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
}
android {
    namespace = "com.adarsh.voxnoxfx"
    compileSdk = 34
    defaultConfig {
        applicationId = "com.adarsh.voxnoxfx"
        minSdk = 26
        targetSdk = 30
        versionCode = 1
        versionName = "0.1-mvp"
    }
    buildTypes { release { isMinifyEnabled = false } }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
}
