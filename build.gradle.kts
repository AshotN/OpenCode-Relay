plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.10.4"
}

group = "com.ashotn"
version = providers.gradleProperty("pluginVersion").get()

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdea("2025.3.3")
        pluginVerifier()
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.ashotn.opencode-relay"
        name = "OpenCode Relay"
        version = project.version.toString()

        vendor {
            name = "Ashot Nazaryan"
        }

        ideaVersion {
            sinceBuild = "253"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2025.3.3")
        }
    }

    signing {
        // Configure signing for production releases
    }

    publishing {
        // Configure publishing to JetBrains Marketplace
    }
}

val sandboxProject = layout.buildDirectory.dir("sandbox-project").get().asFile

tasks {
    runIde {
        // Open a blank project automatically, bypassing the welcome screen
        doFirst { sandboxProject.mkdirs() }
        args(sandboxProject.absolutePath)
    }
}

kotlin {
    jvmToolchain(21)
}
