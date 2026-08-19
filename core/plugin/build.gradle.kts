plugins {
    alias(libs.plugins.android.library)
    alias(libs.plugins.kotlin.serialization)
}

android {
    namespace = "com.hermes.agent.core.plugin"
    compileSdk = 36

    defaultConfig {
        minSdk = 29
        buildConfigField("int", "VERSION_CODE", "1")
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
    api("javax.inject:javax.inject:1")
    api(libs.kotlinx.coroutines.android)
    api(libs.kotlinx.serialization.json)
    api(libs.timber)

    testImplementation(libs.junit)
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(libs.mockk)
}
