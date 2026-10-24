plugins {
    `maven-publish`
}

// ADR-0054: coordinates an app developer types; ADR-0058: dry-run repository and POM.
val artifactBase = property("POM_ARTIFACT_ID").toString()
val pomDescription = property("POM_DESCRIPTION").toString()

group = "io.github.christianalexandre"
version = property("VERSION_NAME").toString()

publishing {
    publications.withType<MavenPublication>().configureEach {
        artifactId = artifactBase + artifactId.removePrefix(project.name)
        pom {
            name.set(artifactId)
            description.set(pomDescription)
            url.set("https://github.com/christianalexandre/app-monitor")
            licenses {
                license {
                    name.set("MIT License")
                    url.set("https://opensource.org/license/mit")
                    distribution.set("repo")
                }
            }
            developers {
                developer {
                    id.set("christianalexandre")
                    name.set("Christian Alexandre")
                    url.set("https://github.com/christianalexandre")
                }
            }
            scm {
                url.set("https://github.com/christianalexandre/app-monitor")
                connection.set("scm:git:https://github.com/christianalexandre/app-monitor.git")
                developerConnection.set("scm:git:ssh://git@github.com/christianalexandre/app-monitor.git")
            }
        }
    }
    repositories {
        maven {
            name = "dryRun"
            url = uri(rootProject.layout.buildDirectory.dir("maven-dry-run"))
        }
    }
}
