plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.r08accessbridge"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.r08accessbridge"
        minSdk = 28
        targetSdk = 34
        versionCode = 14
        versionName = "1.3.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
}

dependencies {
    implementation(project(":bridge-protocol"))
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260212.103714-88")
}
