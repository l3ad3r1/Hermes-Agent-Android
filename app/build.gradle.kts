/*
 * Hermes Agent — App module build file.
 *
 * Phase 1 (Foundation) of the technical plan:
 *   - Jetpack Compose UI shell
 *   - Hilt DI
 *   - Room (conversation + memory persistence)
 *   - LLM provider interface + on-device mock + cloud stub (OpenAI-compatible)
 *   - Security scaffolding (Android Keystore, Samsung Knox hooks)
 *
 * The on-device LLM provider returns canned responses because the MLC-LLM /
 * llama.cpp native runtime and Snapdragon NPU bindings cannot be built in
 * this environment. The cloud provider is wired but the API key is empty
 * by default — see BUILD.md for configuration.
 */

import java.util.Properties

plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.android)
    alias(libs.plugins.kotlin.compose)
    alias(libs.plugins.kotlin.serialization)
    alias(libs.plugins.ksp)
    alias(libs.plugins.hilt)
}

// Read optional local secrets from hermes.local.properties (gitignored).
val localProps = Properties().apply {
    val f = rootProject.file("hermes.local.properties")
    if (f.exists()) load(f.inputStream())
}

android {
    namespace = "com.hermes.agent"
    compileSdk = 34

    defaultConfig {
        applicationId = "com.hermes.agent"
        minSdk = 29          // Android 10 — covers ~95% of active devices
        targetSdk = 34       // Android 14 — matches the plan's target
        versionCode = 14
        versionName = "0.4.9"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        vectorDrawables { useSupportLibrary = true }

        // Surface Gradle properties into BuildConfig so runtime code can read them.
        buildConfigField("String", "CLOUD_BASE_URL", "\"${project.findProperty("hermes.cloudBaseUrl") ?: "https://api.openai.com/v1"}\"")
        buildConfigField("String", "CLOUD_MODEL", "\"${project.findProperty("hermes.cloudModel") ?: "gpt-4o-mini"}\"")
        // API key is read from local properties only — never committed.
        buildConfigField("String", "CLOUD_API_KEY", "\"${localProps.getProperty("hermes.cloudApiKey") ?: ""}\"")
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Phase 4: release signing config. Reads from hermes.local.properties:
            //   hermes.signing.storeFile=/path/to/hermes-release.jks
            //   hermes.signing.storePassword=...
            //   hermes.signing.keyAlias=hermes-release
            //   hermes.signing.keyPassword=...
            // If absent, the release APK is built unsigned (for CI testing).
            val storeFile = localProps.getProperty("hermes.signing.storeFile")
            val storePass = localProps.getProperty("hermes.signing.storePassword")
            val keyAlias = localProps.getProperty("hermes.signing.keyAlias")
            val keyPass = localProps.getProperty("hermes.signing.keyPassword")
            if (!storeFile.isNullOrBlank()) {
                signingConfig = signingConfigs.create("release") {
                    this.storeFile = file(storeFile)
                    this.storePassword = storePass
                    this.keyAlias = keyAlias
                    this.keyPassword = keyPass
                }
            }
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions {
        jvmTarget = "17"
        freeCompilerArgs += listOf(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
    buildFeatures {
        compose = true
        buildConfig = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

dependencies {
    // --- AndroidX core ---
    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.lifecycle.runtime.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.ktx)
    implementation(libs.androidx.lifecycle.viewmodel.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.navigation.compose)
    implementation(libs.androidx.datastore.preferences)
    implementation(libs.androidx.work.runtime.ktx)

    // --- Compose (BOM-managed) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    // --- Termux terminal engine (in-app shell for the Terminal tab) ---
    // github.com/termux/termux-app, consumed as prebuilt AARs via JitPack.
    // terminal-view pulls terminal-emulator (with its native pty) transitively.
    implementation("com.github.termux.termux-app:terminal-view:v0.118.3")
    implementation("com.github.termux.termux-app:terminal-emulator:v0.118.3")

    // --- Hilt ---
    implementation(libs.hilt.android)
    ksp(libs.hilt.compiler)
    implementation(libs.androidx.hilt.navigation.compose)
    implementation(libs.androidx.hilt.work)
    ksp(libs.androidx.hilt.compiler)

    // --- Room ---
    implementation(libs.androidx.room.runtime)
    implementation(libs.androidx.room.ktx)
    ksp(libs.androidx.room.compiler)

    // --- Networking ---
    implementation(libs.retrofit)
    implementation(libs.retrofit.kotlinx.serialization)
    implementation(libs.okhttp)
    implementation(libs.okhttp.logging)

    // --- Serialization ---
    implementation(libs.kotlinx.serialization.json)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)

    // --- Logging ---
    implementation(libs.timber)

    // --- Unit tests ---
    testImplementation(libs.junit)
    testImplementation(libs.mockk)
    testImplementation(libs.turbine)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.robolectric)
    testImplementation(libs.androidx.test.core)
    testImplementation(libs.androidx.room.testing)
    testImplementation(libs.androidx.arch.core.testing)

    // --- Instrumented tests ---
    androidTestImplementation(platform(libs.androidx.compose.bom))
    androidTestImplementation(libs.androidx.junit)
    androidTestImplementation(libs.androidx.compose.ui.test.junit4)
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
