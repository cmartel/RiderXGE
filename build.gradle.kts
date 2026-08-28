import org.jetbrains.intellij.platform.gradle.IntelliJPlatformType
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
val riderBundledModules: String by project

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
        // Rider 2025.3+ exposes the project-model/solution APIs as a separate content module; on 2024.3 they are
        // part of the main jars and the module must not be requested (see riderBundledModules in gradle.properties).
        riderBundledModules.split(',').map { it.trim() }.filter { it.isNotEmpty() }.forEach { bundledModule(it) }
        pluginVerifier()
        testFramework(TestFrameworkType.Platform)
    }
    testImplementation("junit:junit:4.13.2")
}

kotlin {
    jvmToolchain(21)
    compilerOptions {
        // Stay loadable with the Kotlin stdlib bundled in the oldest supported Rider.
        apiVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
        languageVersion.set(org.jetbrains.kotlin.gradle.dsl.KotlinVersion.KOTLIN_2_1)
    }
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
            // The single binary is compiled against the oldest supported Rider (Java 21 bytecode) and verified
            // against the newer lines it must keep running on (Rider 2026.x runs on Java 25 and can load it).
            create(IntelliJPlatformType.Rider, platformVersion)
            create(IntelliJPlatformType.Rider, "2026.1.5")
            create(IntelliJPlatformType.Rider, "2026.2.1")
        }
    }
}

tasks {
    withType<JavaCompile> {
        sourceCompatibility = "21"
        targetCompatibility = "21"
    }
    test {
        useJUnit()
        testLogging { events("passed", "failed", "skipped") }
    }
}
