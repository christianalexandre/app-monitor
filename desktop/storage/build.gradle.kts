plugins {
    id("appmonitor.kotlin-jvm")
    id("app.cash.sqldelight")
}

dependencies {
    api(project(":protocol"))
    api(libs.kotlinx.coroutines.core)
    implementation(libs.sqldelight.sqlite.driver)

    testImplementation(kotlin("test"))
    testImplementation(libs.kotlinx.coroutines.test)
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
