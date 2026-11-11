plugins {
    `kotlin-dsl`
    // kotlin("jvm") version "2.1.0"
}

gradlePlugin {
    plugins {
        register("telegramBuildPlugin") {
            id = "org.telegram.build-plugin"
            implementationClass = "org.telegram.plugin.TelegramBuildPlugin"
        }
        register("telegramBuildAppPlugin") {
            id = "org.telegram.build-app-plugin"
            implementationClass = "org.telegram.plugin.TelegramBuildAppPlugin"
        }
        register("testGenerator") {
            id = "test-generator"
            implementationClass = "com.example.TestGeneratorPlugin"
        }
    }
}

repositories {
    google()
    mavenCentral()
    gradlePluginPortal()
}
/*
val checkEmojiKeyboard by tasks.registering(GenerateSchemeTask::class) {

}
*/
tasks.withType<org.jetbrains.kotlin.gradle.tasks.KotlinCompile>().configureEach {
    compilerOptions {
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_1_9)
    }
    incremental = false
}

dependencies {
    implementation(gradleApi())
    implementation("com.android.tools.build:gradle:8.13.2")

    implementation("com.squareup.moshi:moshi:1.15.0")
    implementation("com.squareup.moshi:moshi-kotlin:1.15.0")
    implementation("com.github.javaparser:javaparser-core:3.25.4")
    implementation("com.squareup:kotlinpoet:1.15.0")
    implementation("com.google.code.gson:gson:2.11.0")

    // OsmAnd's engine (phase 0 of the Maps tab) uses Kotlin Multiplatform. Its plugins must load next to
    // the Android plugin above, or Gradle sees Kotlin's classes twice and refuses the build.
    if (file("../osmand/android/OsmAnd").exists()) {
        implementation("org.jetbrains.kotlin:kotlin-gradle-plugin:2.1.20")
        implementation("org.jetbrains.kotlin:compose-compiler-gradle-plugin:2.1.20")
        implementation("org.jetbrains.kotlin:kotlin-serialization:2.1.20")
        implementation("com.squareup.sqldelight:gradle-plugin:1.5.5")
        implementation("de.undercouch:gradle-download-task:5.7.0")
    }
}