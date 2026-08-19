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
    // AGP 9 provides built-in Kotlin support; the kotlin-android plugin must NOT be applied.
    alias(libs.plugins.android.application)
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

ksp {
    // Room writes its expected schema here so migrations can be verified
    // mechanically instead of by eye, and MigrationTestHelper can replay them.
    arg("room.schemaLocation", "$projectDir/schemas")
}

android {
    namespace = "com.hermes.agent"
    compileSdk = 36

    defaultConfig {
        applicationId = "com.hermes.agent"
        minSdk = 29          // Android 10 — covers ~95% of active devices
        targetSdk = 36       // Android 16
        // Single source of truth in gradle.properties.
        versionCode = (project.findProperty("hermes.versionCode") as String?)?.toInt() ?: 60
        versionName = project.findProperty("hermes.versionName") as String? ?: "0.9.0"

        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // MigrationTestHelper loads the exported schemas from assets.
        sourceSets["androidTest"].assets.srcDir("$projectDir/schemas")
        vectorDrawables { useSupportLibrary = true }

        // Surface Gradle properties into BuildConfig so runtime code can read them.
        buildConfigField("String", "CLOUD_BASE_URL", "\"${project.findProperty("hermes.cloudBaseUrl") ?: "https://api.openai.com/v1"}\"")
        buildConfigField("String", "CLOUD_MODEL", "\"${project.findProperty("hermes.cloudModel") ?: "gpt-4o-mini"}\"")
        // API key is read from local properties only — never committed.
        buildConfigField("String", "CLOUD_API_KEY", "\"${localProps.getProperty("hermes.cloudApiKey") ?: ""}\"")

        // The in-app OTA update channel: an "owner/repo" that publishes signed
        // Hermes APKs as GitHub releases. Blank disables the updater entirely
        // (UI hidden, background check cancelled).
        val updateRepo = (project.findProperty("hermes.updateRepo") as String?).orEmpty().trim()
        buildConfigField("String", "UPDATE_REPO", "\"$updateRepo\"")
        buildConfigField("boolean", "OTA_ENABLED", updateRepo.isNotBlank().toString())
    }

    buildTypes {
        debug {
            isMinifyEnabled = false
            isDebuggable = true
            applicationIdSuffix = ".debug"
            versionNameSuffix = "-debug"
            // res/xml cannot see manifest placeholders, so the launcher
            // shortcuts get the variant's real application id this way.
            resValue("string", "shortcut_target_package", "com.hermes.agent.debug")
        }
        release {
            resValue("string", "shortcut_target_package", "com.hermes.agent")
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

    // llama.cpp is built from source (app/src/main/cpp). Only arm64-v8a is
    // bundled — the target hardware is 64-bit ARM and every other ABI is dead
    // weight in an APK that already carries the native inference runtime.
    defaultConfig {
        ndk {
            abiFilters.add("arm64-v8a")
        }
        externalNativeBuild {
            cmake {
                arguments += "-DCMAKE_BUILD_TYPE=Release"
                arguments += "-DBUILD_SHARED_LIBS=ON"
                arguments += "-DLLAMA_BUILD_APP=OFF"
                arguments += "-DLLAMA_BUILD_COMMON=ON"
                arguments += "-DLLAMA_OPENSSL=OFF"
                arguments += "-DGGML_NATIVE=OFF"
                arguments += "-DGGML_BACKEND_DL=ON"
                arguments += "-DGGML_CPU_ALL_VARIANTS=ON"
                arguments += "-DGGML_LLAMAFILE=OFF"

                // The NDK sysroot has vulkan.h but NOT the C++ vulkan.hpp that
                // ggml-vulkan includes; both glslc and the Vulkan-Hpp headers
                // come from the host Vulkan SDK. Overriding Vulkan_INCLUDE_DIR
                // repoints the Vulkan::Vulkan imported target's headers at the
                // SDK while libvulkan.so still resolves from the NDK sysroot.
                val vulkanSdk = System.getenv("VULKAN_SDK")?.replace('\\', '/')
                if (vulkanSdk != null) {
                    arguments += "-DVulkan_GLSLC_EXECUTABLE=$vulkanSdk/bin/glslc"
                    arguments += "-DVulkan_INCLUDE_DIR=$vulkanSdk/include"
                }

                // Vulkan offload is off: it triggered DeviceLostError on Adreno.
                arguments(
                    "-DGGML_OPENMP=OFF",
                    "-DGGML_VULKAN=OFF"
                )

                val isWindows = System.getProperty("os.name").lowercase().contains("windows")
                if (!isWindows) {
                    arguments += "-DHOST_C_COMPILER=/usr/bin/gcc"
                    arguments += "-DHOST_CXX_COMPILER=/usr/bin/g++"
                }
            }
        }
    }
    externalNativeBuild {
        cmake {
            path("src/main/cpp/CMakeLists.txt")
            version = "3.22.1"
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    buildFeatures {
        compose = true
        buildConfig = true
        // shortcuts.xml resolves the variant's application id through a
        // generated string resource.
        resValues = true
    }
    packaging {
        resources {
            excludes += "/META-INF/{AL2.0,LGPL2.1}"
            excludes += "/META-INF/DEPENDENCIES"
            excludes += "/META-INF/LICENSE*"
        }
        jniLibs {
            // llama.cpp is built with GGML_BACKEND_DL=ON, so it dlopen()s its
            // backend .so files at runtime. Legacy packaging extracts them to
            // the filesystem, which is what makes that dlopen resolve.
            useLegacyPackaging = true
        }
    }
    testOptions {
        unitTests {
            isIncludeAndroidResources = true
            isReturnDefaultValues = true
        }
    }
}

// AGP 9 built-in Kotlin: replaces the old android { kotlinOptions { ... } } block.
// jvmTarget is omitted on purpose — it defaults to android.compileOptions.targetCompatibility (17).
kotlin {
    compilerOptions {
        freeCompilerArgs.addAll(
            "-Xjvm-default=all",
            "-opt-in=kotlin.RequiresOptIn",
        )
    }
}

dependencies {
    implementation(project(":core:plugin"))
    implementation(project(":core:theme"))
    implementation(project(":core:domain"))
    implementation(project(":core:util"))
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
    implementation(libs.androidx.biometric)

    // --- Compose (BOM-managed) ---
    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.graphics)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.androidx.compose.material3)
    implementation(libs.androidx.compose.material.icons.extended)
    implementation(libs.androidx.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

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
    implementation(libs.nanohttpd)
    implementation(libs.jsch)

    // --- Serialization ---
    implementation(libs.kotlinx.serialization.json)

    // --- Coroutines ---
    implementation(libs.kotlinx.coroutines.android)

    // --- Logging ---
    implementation(libs.timber)

    // --- ONNX Runtime (on-device embeddings) ---
    implementation(libs.onnxruntime.android)

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
    androidTestImplementation(libs.androidx.test.core)
    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation("androidx.room:room-testing:2.7.0")
    androidTestImplementation("androidx.test.uiautomator:uiautomator:2.3.0")
    debugImplementation(libs.androidx.compose.ui.test.manifest)
}
