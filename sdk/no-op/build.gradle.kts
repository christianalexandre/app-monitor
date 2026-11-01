plugins {
    id("appmonitor.kotlin-jvm")
}

dependencies {
    testImplementation(kotlin("test"))
    testImplementation(project(":protocol"))
    testImplementation(project(":sdk:core-no-op"))
    testImplementation(libs.asm)
    testImplementation(libs.kotlin.metadata.jvm)
}

// ADR-0072: the API test compares each no-op module's reference dumps with its real module's.
val sdkDir = rootDir.resolve("sdk")

// ADR-0169: and each module's JVM classes, for the constructors the dumps can miss; one jar per module.
val sdkJars =
    listOf("core", "core-no-op", "android-okhttp", "okhttp-no-op", "ktor", "ktor-no-op").associateWith { module ->
        configurations.create("sdkJar-$module") {
            isCanBeConsumed = false
            isTransitive = false
            attributes.addAllLater(configurations.testRuntimeClasspath.get().attributes)
            dependencies.add(project.dependencies.project(":sdk:$module"))
        }
    }

tasks.withType<Test>().configureEach {
    inputs.files(fileTree(sdkDir) { include("*/api/**/*.api") }).withPropertyName("apiDumps")
    systemProperty("appmonitor.sdkDir", sdkDir.absolutePath)
    val jars: Map<String, FileCollection> = sdkJars
    jars.forEach { (module, jar) -> inputs.files(jar).withPropertyName("sdkJar-$module") }
    jvmArgumentProviders += CommandLineArgumentProvider { jars.map { (module, jar) -> "-Dappmonitor.jar.$module=${jar.singleFile}" } }
}
