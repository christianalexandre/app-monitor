import org.gradle.api.tasks.testing.logging.TestExceptionFormat
import java.time.Duration

plugins {
    id("appmonitor.kotlin-jvm")
    id("app.cash.sqldelight")
}

dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver)
    implementation(libs.sqldelight.coroutines.extensions)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
    testImplementation(project(":tools:fake-client"))
}

// ADR-0085: the committed `.db` schemas are what migrations are verified against.
sqldelight {
    databases {
        create("AppMonitorDatabase") {
            packageName = "appmonitor.desktop.storage.db"
            dialect(libs.sqldelight.sqlite.dialect)
            schemaOutputDirectory = file("src/main/sqldelight/databases")
            verifyMigrations = true
        }
    }
}

// DEBT-0091: a hung test fails with its stack, well inside CI's 30 minutes.
tasks.test {
    testLogging.exceptionFormat = TestExceptionFormat.FULL
    systemProperty("junit.jupiter.execution.timeout.default", "2m")
    timeout.set(Duration.ofMinutes(10))
}
