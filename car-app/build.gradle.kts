// Stage D toolchain: Gradle 8.7 + AGP 8.5.2 + Kotlin 2.0.20 + JDK 17.
// Versions are pinned here so a cold build reproduces without network drift.
plugins {
    id("com.android.application") version "8.5.2" apply false
    id("org.jetbrains.kotlin.android") version "2.0.20" apply false
    id("org.jetbrains.kotlin.plugin.compose") version "2.0.20" apply false
}
