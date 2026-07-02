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
        versionCode = 26
        versionName = "1.4.8"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").res.srcDir(layout.buildDirectory.dir("generated/watchdog-script-res"))
    }

    packaging {
        resources {
            excludes += "META-INF/versions/**"
        }
    }
}

val syncWatchdogScriptResource by tasks.registering(Copy::class) {
    from(rootProject.file("tools/r08-a11y-watchdog.sh")) {
        rename { "r08_a11y_watchdog.sh" }
    }
    into(layout.buildDirectory.dir("generated/watchdog-script-res/raw"))
}

tasks.named("preBuild") {
    dependsOn(syncWatchdogScriptResource)
}

dependencies {
    implementation(project(":bridge-protocol"))
    implementation("com.rokid.cxr:cxr-service-bridge:1.0-20260522.063600-105")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
