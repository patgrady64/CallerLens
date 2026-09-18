buildscript {
    dependencies {
        // AGP 9 uses built-in Kotlin. Pin a current Kotlin toolchain so the
        // Compose compiler plugin and Kotlin compiler stay on the same version.
        classpath("org.jetbrains.kotlin:kotlin-gradle-plugin:2.4.10")
    }
}

plugins {
    id("com.android.application") version "9.3.0" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.4.10" apply false
}
