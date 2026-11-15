#!/usr/bin/env bash
# Usage: consumers_check.sh [--local] [VERSION]
# Apps outside this build install the SDK as the README says (ADR-0218): an Android app applying
# only the plugin (debug with the SDK, release with the no-ops), a KMP module on appmonitor-ktor
# with jvm and, on macOS, iosSimulatorArm64 on the oldest Kotlin that reads its klibs, and a
# SwiftPM package at the tag, debug and release. --local publishes to ~/.m2 and tags a scratch
# clone first. CONSUMER_KOTLIN picks the KMP module's Kotlin.
set -euo pipefail
cd "$(dirname "$0")/.."
root="$PWD"

local=false
[[ "${1:-}" == --local ]] && { local=true; shift; }
version="${1:-$(sed -n 's/^VERSION_NAME=//p' gradle.properties)}"
work="$root/build/consumers"
rm -rf "$work"
mkdir -p "$work"
agp="$(sed -n 's/^android-gradle-plugin = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
compile_sdk="$(sed -n 's/^android-compileSdk = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
ktor="$(sed -n 's/^ktor = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
okhttp="$(sed -n 's/^okhttp = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
# ADR-0218: iOS klibs are read by a compiler as new as the one that built them; the JVM floor is ADR-0169's.
kotlin_build="$(sed -n 's/^kotlin = "\([^"]*\)".*/\1/p' gradle/libs.versions.toml)"
kotlin_floor="${CONSUMER_KOTLIN:-${kotlin_build%.*}.0}"

say() { echo "consumers: $*"; }
fail() {
  echo "consumers: $*" >&2
  exit 1
}
gradle() { # dir, log name, tasks…
  local dir="$1" log="$work/$2.log"
  shift 2
  "$root/gradlew" -q -p "$dir" "$@" >"$log" 2>&1 || {
    tail -40 "$log" >&2
    fail "$* failed in $dir, log in $log"
  }
}

if $local; then
  say "publishing $version to ~/.m2"
  gradle "$root" publish-local publishToMavenLocal
  gradle "$root/gradle-plugin" publish-local-plugin publishToMavenLocal
  repos='mavenLocal { content { includeGroupByRegex("io\\.github\\.christianalexandre.*") } }; google(); mavenCentral()'
else
  repos='google(); mavenCentral()'
fi

# Android: only the plugin, as README's Android steps.
android_home="${ANDROID_HOME:-${ANDROID_SDK_ROOT:-}}"
[[ -n "$android_home" ]] || fail "set ANDROID_HOME for the Android consumer"
app="$work/android"
mkdir -p "$app/app/src/main/kotlin/dev/consumer"
echo "sdk.dir=$android_home" >"$app/local.properties"
echo "org.gradle.jvmargs=-Xmx2g" >"$app/gradle.properties"
cat >"$app/settings.gradle.kts" <<EOF
pluginManagement { repositories { $repos } }
dependencyResolutionManagement { repositories { $repos } }
rootProject.name = "android-consumer"
include(":app")
EOF
cat >"$app/app/build.gradle.kts" <<EOF
plugins {
    id("com.android.application") version "$agp"
    id("io.github.christianalexandre.appmonitor") version "$version"
}
android {
    namespace = "dev.consumer"
    compileSdk = $compile_sdk
    defaultConfig { minSdk = 24 }
    buildTypes { release { isMinifyEnabled = true } }
    lint { checkReleaseBuilds = false }
}
dependencies { implementation("com.squareup.okhttp3:okhttp:$okhttp") }
EOF
cat >"$app/app/src/main/AndroidManifest.xml" <<'EOF'
<manifest xmlns:android="http://schemas.android.com/apk/res/android">
    <application android:name="dev.consumer.App" />
</manifest>
EOF
cat >"$app/app/src/main/kotlin/dev/consumer/App.kt" <<'EOF'
package dev.consumer

import android.app.Application
import appmonitor.sdk.core.AppMonitor
import appmonitor.sdk.core.AppMonitorOptions
import okhttp3.OkHttpClient

class App : Application() {
    val client = OkHttpClient()

    override fun onCreate() {
        super.onCreate()
        AppMonitor.start(this, AppMonitorOptions(appName = "Consumer"))
    }
}
EOF
say "android: assembleDebug assembleRelease"
gradle "$app" android :app:assembleDebug :app:assembleRelease
for variant in debug release; do
  gradle "$app" "android-$variant-deps" :app:dependencies --configuration "${variant}RuntimeClasspath"
  # What each line resolves to: the right side of a substitution, else the coordinate itself.
  deps="$(sed 's/.* -> //' "$work/android-$variant-deps.log" |
    grep -o 'io\.github\.christianalexandre:appmonitor-[a-z-]*' | sed 's/.*://' | sort -u | tr '\n' ' ')"
  say "android $variant: $deps"
  if [[ $variant == debug ]]; then
    [[ " $deps" == *" appmonitor-okhttp "* ]] || fail "the debug build has no appmonitor-okhttp"
  elif [[ " $deps" == *" appmonitor-core "* || " $deps" == *" appmonitor-okhttp "* ]]; then
    fail "the release build resolves the real SDK"
  fi
done

# KMP: the plugin in the module that builds the HttpClient, on another engine than OkHttp and Darwin.
kmp="$work/kmp"
mkdir -p "$kmp/src/commonMain/kotlin"
echo "org.gradle.jvmargs=-Xmx2g" >"$kmp/gradle.properties"
targets="jvm()"
tasks=(compileKotlinJvm)
if [[ "$(uname -s)" == Darwin ]]; then
  targets="jvm(); iosSimulatorArm64 { binaries.framework { baseName = \"Shared\" } }"
  tasks+=(linkDebugFrameworkIosSimulatorArm64 linkReleaseFrameworkIosSimulatorArm64)
fi
cat >"$kmp/settings.gradle.kts" <<EOF
pluginManagement { repositories { $repos; gradlePluginPortal() } }
dependencyResolutionManagement { repositories { $repos } }
rootProject.name = "kmp-consumer"
EOF
cat >"$kmp/build.gradle.kts" <<EOF
plugins {
    kotlin("multiplatform") version "$kotlin_floor"
    id("io.github.christianalexandre.appmonitor") version "$version"
}
kotlin {
    $targets
    sourceSets.commonMain.dependencies { implementation("io.ktor:ktor-client-cio:$ktor") }
}
EOF
cat >"$kmp/src/commonMain/kotlin/Client.kt" <<'EOF'
import appmonitor.sdk.ktor.AppMonitorKtor
import io.ktor.client.HttpClient
import io.ktor.client.engine.cio.CIO

fun client() = HttpClient(CIO) { install(AppMonitorKtor) }
EOF
say "kmp on Kotlin $kotlin_floor: ${tasks[*]}"
gradle "$kmp" kmp "${tasks[@]}"

if [[ "$(uname -s)" != Darwin ]] || ! command -v swift >/dev/null 2>&1; then
  say "swift: SKIPPED (needs macOS with swift)"
  say "ok ($version)"
  exit 0
fi

# SwiftPM: the repository's URL at the tag, the README's Up to Next Minor rule.
url=https://github.com/christianalexandre/app-monitor.git
if $local; then
  origin="$work/local-origin/app-monitor.git" # SwiftPM names a package after its URL
  git clone -q --bare "$root" "$origin"
  git -C "$origin" tag "$version" "$(git -C "$root" rev-parse HEAD)"
  [[ -z "$(git -C "$root" status --porcelain -- Package.swift sdk/ios)" ]] ||
    say "swift: uncommitted changes in Package.swift or sdk/ios are not in the tag"
  url="file://$origin"
fi
spm="$work/swift"
mkdir -p "$spm/Sources/Consumer"
cat >"$spm/Package.swift" <<EOF
// swift-tools-version: 6.2
import PackageDescription

let package = Package(
    name: "Consumer",
    platforms: [.macOS(.v12)],
    dependencies: [.package(url: "$url", .upToNextMinor(from: "$version"))],
    targets: [.executableTarget(name: "Consumer", dependencies: [.product(name: "AppMonitor", package: "app-monitor")])]
)
EOF
cat >"$spm/Sources/Consumer/main.swift" <<'EOF'
import AppMonitor

AppMonitor.start(AppMonitorOptions(enabled: false))
AppMonitor.stop()
EOF
for config in debug release; do
  swift build --package-path "$spm" -c "$config" >"$work/swift-$config.log" 2>&1 || {
    tail -40 "$work/swift-$config.log" >&2
    fail "swift $config build failed"
  }
done
resolved="$(sed -n 's/.*"version" *: *"\([^"]*\)".*/\1/p' "$spm/Package.resolved" | head -1)"
[[ "$resolved" == "$version" ]] || fail "swift resolved $resolved, not $version"
say "swift: resolved $resolved from $url, debug and release built"
say "ok ($version)"
