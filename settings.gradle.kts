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

rootProject.name = "ffl-yahoo-android"

include(":core")
// `-PcoreOnly` builds the pure-JVM engine without an Android SDK installed.
if (!providers.gradleProperty("coreOnly").isPresent) {
    include(":app")
}
