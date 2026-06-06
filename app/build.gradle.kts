plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.r08accessbridge"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.anezium.r08accessbridge"
        minSdk = 28
        targetSdk = 34
        versionCode = 12
        versionName = "1.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}
