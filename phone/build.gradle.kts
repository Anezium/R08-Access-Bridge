plugins {
    id("com.android.application")
}

android {
    namespace = "com.anezium.r08companion"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.anezium.r08companion"
        minSdk = 31
        targetSdk = 34
        versionCode = 9
        versionName = "0.2.7"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").res.srcDir(layout.buildDirectory.dir("generated/bridge-script-res"))
    }

    packaging {
        resources {
            excludes += "META-INF/versions/**"
        }
    }
}

val syncBridgeScriptResource by tasks.registering(Copy::class) {
    from(rootProject.file("tools/r08-shortcut-bridge.sh")) {
        rename { "r08_shortcut_bridge.sh" }
    }
    from(rootProject.file("tools/r08-a11y-watchdog.sh")) {
        rename { "r08_a11y_watchdog.sh" }
    }
    into(layout.buildDirectory.dir("generated/bridge-script-res/raw"))
}

tasks.named("preBuild") {
    dependsOn(syncBridgeScriptResource)
}

dependencies {
    implementation(project(":bridge-protocol"))
    implementation("com.rokid.cxr:client-l:1.0.3")
    implementation("dev.mobile:dadb:1.2.10")
    implementation("com.flyfishxu:kadb:2.1.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.10.2")
}
