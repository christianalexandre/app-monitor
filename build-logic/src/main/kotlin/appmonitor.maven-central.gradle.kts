plugins {
    `maven-publish`
    signing
}

// ADR-0215: what Maven Central requires of every publication, the SDK's and the Gradle plugin's.
val markerSuffix = "PluginMarkerMaven"

plugins.withId("java") {
    extensions.configure<JavaPluginExtension> { withSourcesJar() }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        val publication = this
        if (!name.endsWith(markerSuffix)) {
            val javadocJar =
                tasks.register<Jar>("${publication.name}JavadocJar") {
                    archiveBaseName.set("${project.name}-${publication.name}")
                    archiveClassifier.set("javadoc")
                }
            artifact(javadocJar)
        }
        pom {
            name.set(provider { publication.artifactId })
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
            val default =
                rootProject.layout.buildDirectory
                    .dir("maven-dry-run")
                    .get()
                    .asFile.path
            url = uri(providers.gradleProperty("appmonitor.mavenRepo").getOrElse(default))
        }
    }
}

// ADR-0215: the key comes from the environment only; without it nothing is signed.
val signingKey = providers.environmentVariable("APPMONITOR_SIGNING_KEY")
if (signingKey.isPresent) {
    signing {
        useInMemoryPgpKeys(signingKey.get(), providers.environmentVariable("APPMONITOR_SIGNING_PASSWORD").getOrElse(""))
        sign(publishing.publications)
    }
    tasks.withType<AbstractPublishToMaven>().configureEach { dependsOn(tasks.withType<Sign>()) }
}
