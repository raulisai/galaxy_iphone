import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Wear OS app for Galaxy Watch7. AGP 9 built-in Kotlin: no separate kotlin-android plugin.
plugins {
    alias(libs.plugins.android.application)
    alias(libs.plugins.kotlin.compose)
}

val gitCommit: String = providers.exec {
    commandLine("git", "describe", "--always", "--dirty", "--abbrev=12", "--exclude=*")
    isIgnoreExitValue = true
}.standardOutput.asText.map { it.trim().ifEmpty { "unknown" } }.getOrElse("unknown")

android {
    namespace = "dev.wristbridge.watch"
    // AndroidX 2026.09 libraries require compiling against API 37; runtime behaviour stays at targetSdk 36.
    compileSdk = 37

    defaultConfig {
        // Own product ID; not copied from any reference repository (Doc/02 §Primera sesión).
        applicationId = "dev.wristbridge.watch"
        minSdk = 33
        targetSdk = 36
        versionCode = 4
        versionName = "0.2.0-lab"
        testInstrumentationRunner = "androidx.test.runner.AndroidJUnitRunner"
        // H01: the running build must identify its exact revision. "-dirty" marks uncommitted changes.
        buildConfigField("String", "GIT_COMMIT", "\"$gitCommit\"")
    }

    buildTypes {
        debug {
            // Laboratory APK for ADB installation; debug signing stays outside Git (Android debug keystore).
            isDebuggable = true
        }
        release {
            // Release signing is configured locally only (P8-06); never committed.
            isMinifyEnabled = false
        }
    }

    compileOptions {
        sourceCompatibility = JavaVersion.VERSION_17
        targetCompatibility = JavaVersion.VERSION_17
    }

    buildFeatures {
        compose = true
        buildConfig = true
    }

    lint {
        abortOnError = true
        warningsAsErrors = false
    }
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    implementation(project(":core:notification-domain"))

    implementation(libs.androidx.core.ktx)
    implementation(libs.androidx.activity.compose)
    implementation(libs.androidx.lifecycle.runtime.compose)
    implementation(libs.androidx.lifecycle.service)
    implementation(libs.kotlinx.coroutines.android)

    implementation(platform(libs.androidx.compose.bom))
    implementation(libs.androidx.compose.ui)
    implementation(libs.androidx.compose.ui.tooling.preview)
    implementation(libs.wear.compose.material3)
    implementation(libs.wear.compose.foundation)
    debugImplementation(libs.androidx.compose.ui.tooling)

    androidTestImplementation(libs.androidx.test.runner)
    androidTestImplementation(libs.androidx.test.ext.junit)
}
