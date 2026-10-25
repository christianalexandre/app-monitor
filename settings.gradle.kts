pluginManagement {
    includeBuild("build-logic")
    includeBuild("gradle-plugin")
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
include(":desktop:storage")
include(":desktop:mcp")
include(":desktop:proxy")
include(":desktop:designsystem")
include(":desktop:domain")
include(":desktop:data")
include(":desktop:feature:traffic")
include(":desktop:app")
include(":tools:fake-client")
include(":tools:e2e-target")
include(":sdk:core")
include(":sdk:android-okhttp")
include(":sdk:ktor")
include(":sdk:core-no-op")
include(":sdk:okhttp-no-op")
include(":sdk:ktor-no-op")
include(":sdk:no-op")
include(":samples:android")
include(":samples:kmp:shared")
include(":samples:kmp:androidApp")
