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
    // `-PupdateFixtures` alone is an empty value; `=false` must not rewrite the golden files.
    val update = providers.gradleProperty("updateFixtures").orNull
    if (update != null && (update.isEmpty() || update.toBoolean())) {
        systemProperty("appmonitor.updateFixtures", "true")
    }
}
