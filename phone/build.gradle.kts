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
        versionCode = 1
        versionName = "0.1.0"
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    sourceSets {
        getByName("main").res.srcDir(layout.buildDirectory.dir("generated/bridge-script-res"))
    }
}

val syncBridgeScriptResource by tasks.registering(Copy::class) {
    from(rootProject.file("tools/r08-shortcut-bridge.sh"))
    into(layout.buildDirectory.dir("generated/bridge-script-res/raw"))
    rename { "r08_shortcut_bridge.sh" }
}

tasks.named("preBuild") {
    dependsOn(syncBridgeScriptResource)
}

dependencies {
    implementation(project(":bridge-protocol"))
    implementation("com.rokid.cxr:client-l:1.0.1")
    implementation("dev.mobile:dadb:1.2.10")
}
