plugins {
    id("java")
    id("java-test-fixtures")
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

val liveTest by sourceSets.creating {
    java.srcDir("src/liveTest/kotlin")
    resources.srcDir("src/liveTest/resources")
    compileClasspath += sourceSets["main"].output + sourceSets["testFixtures"].output
    runtimeClasspath += output + compileClasspath
}

dependencies {
    intellijPlatform {
        intellijIdea("2024.3.7")
        pluginVerifier()
        bundledPlugin("org.jetbrains.plugins.terminal")
    }

    testImplementation(kotlin("test"))
    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":")))
    add(liveTest.implementationConfigurationName, testFixtures(project(":")))
    intellijPlatform {
        testFramework(org.jetbrains.intellij.platform.gradle.TestFrameworkType.Platform)
    }
}

configurations[liveTest.implementationConfigurationName].extendsFrom(configurations.testImplementation.get())
configurations[liveTest.runtimeOnlyConfigurationName].extendsFrom(configurations.testRuntimeOnly.get())

intellijPlatform {
    pluginConfiguration {
        id = "com.ashotn.opencode-relay"
        name = "OpenCode Relay"
        version = project.version.toString()

        vendor {
            name = "Ashot Nazaryan"
        }

        ideaVersion {
            sinceBuild = "243"
            untilBuild = provider { null }
        }
    }

    pluginVerification {
        ides {
            create(org.jetbrains.intellij.platform.gradle.IntelliJPlatformType.IntellijIdea, "2024.3.7")
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

    register<Test>("liveTest") {
        val baseTestTask = named<Test>("test").get()
        val testOutputFiles = sourceSets["test"].output.files
        description = "Runs the live OpenCode integration tests."
        group = "verification"
        testClassesDirs = liveTest.output.classesDirs
        // Reuse the IntelliJ/Gson runtime that the plugin's regular test task assembles,
        // but keep src/test outputs off the live-test runtime so live tests don't depend on unit-test code.
        classpath = files(
            liveTest.runtimeClasspath,
            baseTestTask.classpath.filter { it !in testOutputFiles },
        )
        listOf("OPENCODE_TEST_VERSIONS", "OPENCODE_TEST_AUTH_JSON").forEach { name ->
            System.getenv(name)?.let { value ->
                environment(name, value)
            }
        }
        shouldRunAfter(test)
        useJUnit()
    }

    named("compileLiveTestKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class) {
        compilerOptions.freeCompilerArgs.add(
            "-Xfriend-paths=${sourceSets["main"].output.classesDirs.asPath}",
        )
    }

    named("compileTestFixturesKotlin", org.jetbrains.kotlin.gradle.tasks.KotlinCompile::class) {
        compilerOptions.freeCompilerArgs.add(
            "-Xfriend-paths=${sourceSets["main"].output.classesDirs.asPath}",
        )
    }
}

kotlin {
    jvmToolchain(21)

    sourceSets.named("main") {
        kotlin.exclude("com/ashotn/opencode/relay/terminal/ReworkedTuiPanel.kt")
        kotlin.exclude("com/ashotn/opencode/relay/terminal/NewSessionTerminalAllowedActionsProvider.kt")
    }
}
