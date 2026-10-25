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

// The app's own AGP is the one at run time; the functional tests put this one on the plugin's classpath.
val agp = configurations.create("agp")

dependencies {
    compileOnly(libs.android.gradle.api)
    agp(libs.android.gradle.plugin)
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
