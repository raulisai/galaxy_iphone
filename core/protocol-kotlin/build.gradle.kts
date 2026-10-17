import org.jetbrains.kotlin.gradle.dsl.JvmTarget

// Pure JVM: ANCS formats, GATT operation queue and contract constants. No Android imports.
plugins {
    alias(libs.plugins.kotlin.jvm)
}

java {
    sourceCompatibility = JavaVersion.VERSION_17
    targetCompatibility = JavaVersion.VERSION_17
}

kotlin {
    compilerOptions { jvmTarget = JvmTarget.JVM_17 }
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(platform(libs.junit.bom))
    testImplementation(libs.junit.jupiter)
    testRuntimeOnly(libs.junit.platform.launcher)
}

val repoRoot = rootProject.layout.projectDirectory.asFile.absolutePath

tasks.test {
    useJUnitPlatform()
    systemProperty("wristbridge.repoRoot", repoRoot)
}
