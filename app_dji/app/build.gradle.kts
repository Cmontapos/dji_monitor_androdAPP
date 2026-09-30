import java.util.Properties

plugins {
    id("com.android.application")
    id("org.jetbrains.kotlin.android")
    id("org.jetbrains.kotlin.plugin.compose")
}

val localConfig = Properties().apply {
    rootProject.file("local.properties").takeIf { it.exists() }?.inputStream()?.use { load(it) }
}
val djiKey = providers.environmentVariable("DJI_MSDK_API_KEY").orNull
    ?: localConfig.getProperty("dji.msdk.apiKey", "")

android {
    namespace = "com.gaslab.microgas"
    compileSdk = 35

    defaultConfig {
        applicationId = "com.gaslab.microgas"
        minSdk = 29
        targetSdk = 34
        versionCode = 5
        versionName = "0.4.1"
    }
    flavorDimensions += "source"
    productFlavors {
        create("demo") {
            dimension = "source"
            applicationIdSuffix = ".demo"
            resValue("string", "microgas_app_name", "MicroGas Simulado")
            buildConfigField("String", "DATA_ORIGIN", "\"simulado\"")
        }
        create("dji") {
            dimension = "source"
            resValue("string", "microgas_app_name", "MicroGas DJI")
            buildConfigField("String", "DATA_ORIGIN", "\"dji\"")
            manifestPlaceholders["DJI_API_KEY"] = djiKey
            ndk { abiFilters += "arm64-v8a" }
        }
    }
    packaging {
        jniLibs {
            useLegacyPackaging = true
            keepDebugSymbols += "**/*.so"
            pickFirsts += "**/libc++_shared.so"
        }
    }
    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }
    kotlinOptions { jvmTarget = "17" }
    buildFeatures { compose = true; buildConfig = true }
    buildTypes {
        getByName("release") {
            signingConfig = signingConfigs.getByName("debug")
        }
    }
}

dependencies {
    "djiImplementation"("com.dji:dji-sdk-v5-aircraft:5.18.0")
    "djiCompileOnly"("com.dji:dji-sdk-v5-aircraft-provided:5.18.0")
    implementation(platform("androidx.compose:compose-bom:2024.12.01"))
    implementation("androidx.activity:activity-compose:1.9.3")
    implementation("androidx.compose.material3:material3")
    implementation("androidx.compose.ui:ui-tooling-preview")
    implementation("androidx.lifecycle:lifecycle-viewmodel-compose:2.8.7")
    implementation("androidx.lifecycle:lifecycle-runtime-compose:2.8.7")
    debugImplementation("androidx.compose.ui:ui-tooling")
    testImplementation("junit:junit:4.13.2")
}
