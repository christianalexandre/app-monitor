import org.jetbrains.kotlin.gradle.dsl.JvmTarget
import org.jetbrains.kotlin.gradle.dsl.abi.ExperimentalAbiValidation

plugins {
    `java-gradle-plugin`
    id("appmonitor.kotlin-jvm")
}

// AGP 9 runs on JDK 17 and later.
kotlin {
    explicitApi()
    compilerOptions {
        jvmTarget = JvmTarget.JVM_17
    }

    @OptIn(ExperimentalAbiValidation::class)
    abiValidation {}
}

java {
    targetCompatibility = JavaVersion.VERSION_17
}

gradlePlugin {
    plugins {
        create("appMonitor") {
            id = "io.github.christianalexandre.appmonitor"
            implementationClass = "appmonitor.gradle.AppMonitorPlugin"
        }
    }
}

// ADR-0157: the plugin adds the SDK of its own release, the root's VERSION_NAME.
val sdkVersion =
    providers.fileContents(layout.projectDirectory.file("../gradle.properties")).asText.map { text ->
        text
            .lineSequence()
            .first { it.startsWith("VERSION_NAME=") }
            .substringAfter("=")
            .trim()
    }
val generateSdkVersion by tasks.registering {
    val output = layout.buildDirectory.dir("generated/sdkVersion")
    inputs.property("version", sdkVersion)
    outputs.dir(output)
    doLast {
        val file = output.get().file("appmonitor/gradle/SdkVersion.kt").asFile
        file.parentFile.mkdirs()
        file.writeText("package appmonitor.gradle\n\ninternal const val SDK_VERSION: String = \"${sdkVersion.get()}\"\n")
    }
}
kotlin.sourceSets.main { kotlin.srcDir(generateSdkVersion) }

// The app's own AGP and KGP are the ones at run time; the functional tests put these on the plugin's classpath.
val agp = configurations.create("agp")

dependencies {
    compileOnly(libs.android.gradle.api)
    compileOnly(libs.kotlin.gradle.plugin)
    agp(libs.android.gradle.plugin)
    agp(libs.kotlin.gradle.plugin)
    testImplementation(kotlin("test"))
    testImplementation(libs.android.gradle.api)
    testImplementation(libs.okhttp)
}

testing {
    suites {
        register<JvmTestSuite>("functionalTest") {
            useKotlinTest(embeddedKotlinVersion)
            dependencies {
                implementation(gradleTestKit())
            }
            targets.configureEach {
                testTask.configure {
                    val sdkDir = providers.environmentVariable("ANDROID_HOME").orElse(providers.environmentVariable("ANDROID_SDK_ROOT"))
                    systemProperty("appmonitor.androidSdk", sdkDir.getOrElse(""))
                    systemProperty("appmonitor.okhttp", libs.versions.okhttp.get())
                    systemProperty("appmonitor.sdkVersion", sdkVersion.get())
                    systemProperty(
                        "appmonitor.compileSdk",
                        libs.versions.android.compileSdk
                            .get(),
                    )
                }
            }
        }
    }
}

tasks.named<PluginUnderTestMetadata>("pluginUnderTestMetadata") {
    pluginClasspath.from(agp)
}

gradlePlugin {
    testSourceSets(sourceSets["functionalTest"])
}
