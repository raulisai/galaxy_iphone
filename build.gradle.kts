// Windows/Kotlin root build. Apple code under apps/ios and packages/ is not part of Gradle.
plugins {
    alias(libs.plugins.android.application) apply false
    alias(libs.plugins.kotlin.jvm) apply false
    alias(libs.plugins.kotlin.compose) apply false
}
