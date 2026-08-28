import org.jetbrains.intellij.platform.gradle.TestFrameworkType

plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.4.10"
    id("org.jetbrains.intellij.platform") version "2.18.1"
}

val pluginGroup: String by project
val pluginVersion: String by project
val platformVersion: String by project
val pluginSinceBuild: String by project
val pluginUntilBuild: String by project
val riderLocalPath: String by project

group = pluginGroup
version = pluginVersion

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        val local = riderLocalPath.trim()
        if (local.isNotEmpty() && file(local).isDirectory) {
            local(local)
        } else {
            rider(platformVersion)
        }
        // Rider 2026.x moved the project-model/solution APIs into a separate content module.
        bundledModule("intellij.rider.rdclient.dotnet")
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(25)
}

intellijPlatform {
    // buildSearchableOptions boots a headless IDE to index the settings page; with Rider that starts the whole
    // IDE + .NET backend and hangs. Not needed for a plugin with one settings page.
    buildSearchableOptions = false

    pluginConfiguration {
        version = pluginVersion
        ideaVersion {
            sinceBuild = pluginSinceBuild
            untilBuild = provider { pluginUntilBuild.trim().ifEmpty { null } }
        }
    }
    pluginVerification {
        ides {
            recommended()
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "25"
        targetCompatibility = "25"
    }
    test {
        useJUnit()
        testLogging { events("passed", "failed", "skipped") }
    }
}
