plugins {
    id("appmonitor.kmp-library")
    id("com.squareup.wire")
}

wire {
    sourcePath {
        srcDir("proto")
    }
    kotlin {}
}

kotlin {
    android {
        namespace = "appmonitor.protocol"
    }
    sourceSets {
        commonTest.dependencies {
            implementation(kotlin("test"))
        }
    }
}

tasks.named<Test>("jvmTest") {
    val fixtures = layout.projectDirectory.dir("fixtures")
    inputs.dir(fixtures).withPropertyName("fixtures").withPathSensitivity(PathSensitivity.RELATIVE)
    systemProperty("appmonitor.fixtures", fixtures.asFile.absolutePath)
    providers.gradleProperty("updateFixtures").orNull?.let { systemProperty("appmonitor.updateFixtures", "true") }
}
