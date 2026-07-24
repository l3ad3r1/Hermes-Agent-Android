/*
 * Hermes Agent — Root build file.
 * Plugin versions are centralized in gradle/libs.versions.toml.
 */

plugins {
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin must NOT be applied.
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.compose) apply false
    alias(libs.plugins.kotlin.serialization) apply false
    alias(libs.plugins.ksp) apply false
    alias(libs.plugins.hilt) apply false
}
