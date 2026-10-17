pluginManagement {
    repositories {
        google()
        mavenCentral()
        gradlePluginPortal()
    }
}

dependencyResolutionManagement {
    repositoriesMode.set(RepositoriesMode.FAIL_ON_PROJECT_REPOS)
    repositories {
        google()
        mavenCentral()
    }
}

rootProject.name = "WristBridge"

include(
    ":apps:watch",
    ":core:protocol-kotlin",
    ":core:notification-domain",
    ":core:assistant-domain",
)
