plugins {
    id("appmonitor.maven-central")
}

// ADR-0054: coordinates an app developer types; ADR-0058: one artifact base per module.
val artifactBase = property("POM_ARTIFACT_ID").toString()
val pomDescription = property("POM_DESCRIPTION").toString()

group = "io.github.christianalexandre"
version = property("VERSION_NAME").toString()

publishing.publications.withType<MavenPublication>().configureEach {
    artifactId = artifactBase + artifactId.removePrefix(project.name)
    pom.description.set(pomDescription)
}
