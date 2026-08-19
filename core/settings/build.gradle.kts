plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hermes.agent.core.settings"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        buildConfigField("String", "CLOUD_API_KEY", "\"\"")
        buildConfigField("String", "CLOUD_BASE_URL", "\"https://api.together.xyz/v1\"")
        buildConfigField("String", "CLOUD_MODEL", "\"meta-llama/Llama-3.3-70B-Instruct-Turbo\"")
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        buildConfig = true
    }
}

dependencies {
    api(project(":core:util"))
    api(project(":core:domain"))
    implementation(libs.hilt.android)
    api(libs.androidx.datastore.preferences)
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.timber)
    api("javax.inject:javax.inject:1")

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
