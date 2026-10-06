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

// --- Tinker hot-fix build glue (docs/TINKER-HOTFIX.md) ---------------------------------
// The Tinker Gradle plugin cannot run on AGP 9 (it needs applicationVariants and the removed
// Transform API), so the parts of it a patch actually depends on are done here by hand:
//
//   TINKER_ID            manifest meta-data + BuildConfig, "hermes-<versionCode>-<git sha>".
//                        Tinker refuses a patch whose base id differs from the installed one.
//   hermes.tinker.base   PATCH BUILD MODE: -Phermes.tinker.base=<archived base dir> pins the
//                        versionCode/versionName to the base's, feeds its stable-ids.txt back to
//                        aapt2 (--stable-ids) and its mapping.txt to R8 (-applymapping), so the
//                        fix is diffed against a build with the same resource ids and class names.
//
// Nothing here loads code; it only makes two builds comparable. The runtime trust checks live in
// data/hotfix and the patch itself is produced by tools/tinker from reviewed, merged code.
val tinkerBaseDir: File? = (project.findProperty("hermes.tinker.base") as String?)
    ?.takeIf { it.isNotBlank() }
    ?.let { rootProject.file(it) }
val tinkerBase: Properties? = tinkerBaseDir?.let { dir ->
    val info = File(dir, "tinker-base.properties")
    require(info.isFile) {
        "hermes.tinker.base=$dir is not an archived Tinker base (no tinker-base.properties). " +
            "Archive the release with tools/tinker/archive-base first."
    }
    Properties().apply { info.inputStream().use { load(it) } }
}
val gitShortSha: String = runCatching {
    providers.exec {
        commandLine("git", "rev-parse", "--short=12", "HEAD")
        isIgnoreExitValue = true
    }.standardOutput.asText.get().trim()
}.getOrDefault("").ifBlank { "nogit" }

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
        versionCode = (project.findProperty("hermes.versionCode") as String?)?.toInt() ?: 66
        versionName = project.findProperty("hermes.versionName") as String? ?: "0.9.6"
        // A patch build must look like its base to the manifest check in tinker-patch-lib
        // (versionCode/versionName unchanged) and to OTA version comparisons on the phone.
        // versionName is pinned on the variant output below: the archived value is the final,
        // already-suffixed manifest value (e.g. "1.1.3-debug"), and pinning it here would get the
        // build type's versionNameSuffix appended a second time.
        tinkerBase?.let { base ->
            versionCode = base.getProperty("versionCode").toInt()
        }
        // Must not look numeric: aapt would store it as an int and Tinker reads it back as text.
        val tinkerId = (project.findProperty("hermes.tinkerId") as String?)?.takeIf { it.isNotBlank() }
            ?: "hermes-$versionCode-$gitShortSha"
        manifestPlaceholders["tinkerId"] = tinkerId
        buildConfigField("String", "TINKER_ID", "\"$tinkerId\"")

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
        }
        release {
            isMinifyEnabled = true
            isShrinkResources = true
            proguardFiles(
                getDefaultProguardFile("proguard-android-optimize.txt"),
                "proguard-rules.pro"
            )
            // Patch build mode: keep the base's obfuscated names so the dex diff stays small and
            // anything that persisted a class name (WorkManager rows, serialized state) still resolves.
            // A debug (unminified) base, e.g. CI's smoke check, has no mapping; a release base must.
            tinkerBaseDir?.let { dir ->
                val mapping = File(dir, "mapping.txt")
                if (tinkerBase?.getProperty("variant") == "release") {
                    require(mapping.isFile) { "hermes.tinker.base: $mapping is missing; release bases must archive R8's mapping.txt" }
                }
                // With -dontobfuscate there are no names to keep, and -applymapping on top of it makes R8
                // write a dex whose method ids are out of order.
                val obfuscating = file("proguard-rules.pro").readLines().none { it.trim() == "-dontobfuscate" }
                if (mapping.isFile && obfuscating) {
                    val rules = project.layout.buildDirectory.file("tinker/applymapping.pro").get().asFile
                    rules.parentFile.mkdirs()
                    rules.writeText("-applymapping \"${mapping.absolutePath.replace('\\', '/')}\"\n")
                    proguardFile(rules)
                }
            }
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

                // OpenCL offload, opt-in via OPENCL_SDK. Unlike Vulkan this is
                // the backend Qualcomm targets at Adreno directly: llama.cpp
                // lists Adreno 750 (Snapdragon 8 Gen 3) as verified and supports
                // Q4_K, so the existing Q4_K_M catalogue works unchanged.
                //
                // The NDK sysroot ships neither the CL headers nor a libOpenCL.so
                // to link against, so ggml-opencl's find_package(OpenCL REQUIRED)
                // needs both pointed at explicitly. Expected layout:
                //
                //   $OPENCL_SDK/include/CL/*.h          KhronosGroup/OpenCL-Headers
                //   $OPENCL_SDK/lib/arm64-v8a/libOpenCL.so  KhronosGroup/OpenCL-ICD-Loader,
                //                                           built for arm64-v8a
                //
                // That .so is a link-time stub only. It is deliberately NOT
                // packaged into the APK: on device the loader resolves the soname
                // to the vendor's own /vendor/lib64/libOpenCL.so, which is the
                // actual Adreno driver and is exported to apps via
                // /vendor/etc/public.libraries.txt. Confirm that entry exists on
                // the target device before assuming this resolves.
                //
                // Building this in is safe on non-Adreno hardware: the backend is
                // a separate libggml-opencl.so loaded through GGML_BACKEND_DL, and
                // ggml_backend_load_best() skips a backend whose .so will not load
                // rather than failing. Devices without a driver fall back to CPU.
                // What that does NOT cover is a driver that loads and then faults
                // mid-inference, which is how the Vulkan attempt died -- so treat
                // this as untested until it has run on a real Adreno device.
                val openclSdk = System.getenv("OPENCL_SDK")?.replace('\\', '/')
                if (openclSdk != null) {
                    arguments += "-DGGML_OPENCL=ON"
                    arguments += "-DOpenCL_INCLUDE_DIR=$openclSdk/include"
                    arguments += "-DOpenCL_LIBRARY=$openclSdk/lib/arm64-v8a/libOpenCL.so"
                    // Adreno-tuned matmul kernels, embedded so no .cl files ship
                    // alongside the APK. Both are the upstream defaults; pinned
                    // here so a llama.cpp bump cannot quietly flip them.
                    arguments += "-DGGML_OPENCL_USE_ADRENO_KERNELS=ON"
                    arguments += "-DGGML_OPENCL_EMBED_KERNELS=ON"
                } else {
                    arguments += "-DGGML_OPENCL=OFF"
                }

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
        aidl = true
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

// Tinker, patch build mode only: pin resource ids to the archived base's table. The table itself is
// taken from the base APK by tools/tinker (aapt2 dump resources), not from an aapt2 --emit-ids
// side output (an undeclared task output that CI showed is not reliably written); build-patch then
// checks that no resource id of the base moved in the fix build.
androidComponents {
    onVariants { variant ->
        tinkerBase?.let { base ->
            variant.outputs.forEach { output ->
                output.versionName.set(base.getProperty("versionName"))
                output.versionCode.set(base.getProperty("versionCode").toInt())
            }
        }
        tinkerBaseDir?.let { dir ->
            val stableIds = File(dir, "stable-ids.txt")
            require(stableIds.isFile) { "hermes.tinker.base: $stableIds is missing" }
            variant.androidResources.aaptAdditionalParameters.addAll("--stable-ids", stableIds.absolutePath)
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
            // Carried over from Octo Jotter's build: its annotated constructor properties
            // rely on the pre-2.2 default annotation target.
            "-Xannotation-default-target=param-property",
        )
    }
}

dependencies {
    // SPIKE: embedded Tailscale node, built by tsnet-bridge/build.sh (gomobile).
    implementation(files("libs/tsbridge.aar"))
    implementation(project(":core:tools"))
    implementation(project(":core:llm"))
    implementation(project(":core:memory"))
    implementation(project(":core:persistence"))
    implementation(project(":core:settings"))
    implementation(project(":core:plugin"))
    implementation(project(":core:theme"))
    implementation(libs.material.color.utilities)
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
    implementation(libs.androidx.browser)

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

    // --- Tinker hot-fix runtime (patch loading; see docs/TINKER-HOTFIX.md) ---
    implementation(libs.tinker.android.lib)
    implementation(libs.tinker.android.loader)

    // --- Shizuku (privileged shell) ---
    implementation(libs.shizuku.api)
    implementation(libs.shizuku.provider)

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
