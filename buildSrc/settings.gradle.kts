// The kotlin-dsl plugin above and its Kotlin libraries were looked up on the Gradle Plugin Portal only, whose copy of
// Maven Central sometimes answers 404 to GitHub's runners and fails the build in its first seconds. Maven Central
// itself is asked first; when it too answers 404 to the runners, as it has for Kotlin 2.0.21, Gradle goes on to the
// official mirror of Maven Central (the same files, served from Google's storage), then to the Plugin Portal.
pluginManagement {
    repositories {
        mavenCentral()
        google()
        maven { url = uri("https://maven-central.storage-download.googleapis.com/maven2/") }
        gradlePluginPortal()
    }
}
