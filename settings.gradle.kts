pluginManagement {
    includeBuild("build-logic")
    repositories {
        gradlePluginPortal()
        mavenCentral()
        google()
    }
}

dependencyResolutionManagement {
    repositoriesMode = RepositoriesMode.FAIL_ON_PROJECT_REPOS
    repositories {
        mavenCentral()
        google()
    }
}

rootProject.name = "app-monitor"

include(":protocol")
include(":desktop:server")
include(":desktop:app")
include(":tools:fake-client")
include(":sdk:core")
include(":sdk:android-okhttp")
