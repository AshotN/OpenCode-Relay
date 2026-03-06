plugins {
    id("java")
    id("org.jetbrains.kotlin.jvm") version "2.1.0"
    id("org.jetbrains.intellij.platform") version "2.2.1"
}

group = "com.ashotn"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
    intellijPlatform {
        defaultRepositories()
    }
}

dependencies {
    intellijPlatform {
        intellijIdeaCommunity("2025.1")
        instrumentationTools()
        pluginVerifier()
    }
}

intellijPlatform {
    pluginConfiguration {
        id = "com.ashotn.opencode-intellij"
        name = "OpenCode"
        version = project.version.toString()

        vendor {
            name = "Ashot Nazaryan"
        }

        ideaVersion {
            sinceBuild = "251"
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
