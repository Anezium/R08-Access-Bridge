plugins {
    id("com.android.application") version "8.7.3" apply false
}

subprojects {
    tasks.withType<org.gradle.api.tasks.compile.JavaCompile>().configureEach {
        options.encoding = "UTF-8"
    }
}
