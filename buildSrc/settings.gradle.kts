// The kotlin-dsl plugin above and its Kotlin libraries were looked up on the Gradle Plugin Portal only, whose copy of
// Maven Central sometimes answers 404 to GitHub's runners and fails the build in its first seconds. Maven Central
// itself is asked first.
pluginManagement {
    repositories {
        mavenCentral()
        google()
        gradlePluginPortal()
    }
}
