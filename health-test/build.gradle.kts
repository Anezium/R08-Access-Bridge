plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.r08healthtest"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.r08healthtest"
        minSdk = 28
        targetSdk = 31
        versionCode = 1
        versionName = "0.1.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    testOptions {
        unitTests.isIncludeAndroidResources = true
    }

    lint {
        abortOnError = true
        checkReleaseBuilds = false
        // This debug harness deliberately targets Android 12 (API 31) per its test contract.
        disable += "ExpiredTargetSdkVersion"
    }
}

dependencies {
    implementation(project(":ring-health"))

    testImplementation("junit:junit:4.13.2")
    testImplementation("org.robolectric:robolectric:4.13")
}
