plugins {
    base
}

val paperApiVersion = "1.21.8-R0.1-SNAPSHOT"
val velocityApiVersion = "3.4.0-SNAPSHOT"
val mongoDriverVersion = "5.5.0"
val clickhouseClientVersion = "0.9.8"
val sqliteJdbcVersion = "3.50.1.0"
val configurateVersion = "4.2.0"
val cloudMinecraftVersion = "2.0.0-beta.16"
val cloudCoreVersion = "2.0.0"
val junitVersion = "5.13.4"
val assertjVersion = "3.27.6"
val mockitoVersion = "5.20.0"
val testcontainersVersion = "1.21.3"
val jetbrainsAnnotationsVersion = "26.0.2"
val faweVersion = "2.15.2"
val worldeditVersion = "7.3.15"

// True when a Docker daemon is reachable. Used to warn (not fail) when the
// Testcontainers store ITs will assume-skip, so a no-Docker `check` isn't
// mistaken for full verification.
fun dockerIsAvailable(): Boolean = try {
    val process = ProcessBuilder("docker", "info")
        .redirectOutput(ProcessBuilder.Redirect.DISCARD)
        .redirectError(ProcessBuilder.Redirect.DISCARD)
        .start()
    if (process.waitFor(5, java.util.concurrent.TimeUnit.SECONDS)) {
        process.exitValue() == 0
    } else {
        process.destroyForcibly()
        false
    }
} catch (ex: Exception) {
    false
}

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    apply(plugin = "jacoco")
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        // The Testcontainers store ITs assume-skip when Docker is absent, so
        // a green `check` without Docker is NOT full verification - a silence
        // that once masked real bugs (issue #15). Make it loud: if this
        // module has *IT tests and Docker isn't reachable, warn.
        doFirst {
            val hasIntegrationTests =
                !project.fileTree("src/test/java") { include("**/*IT.java") }.files.isEmpty()
            if (hasIntegrationTests && !dockerIsAvailable()) {
                logger.warn(
                    "WARNING: Docker not reachable - the Testcontainers store ITs in " +
                        "'${project.name}' will be SKIPPED. `check` passing is NOT full " +
                        "verification; start Docker to run them."
                )
            }
        }
    }
    tasks.withType<JacocoReport>().configureEach {
        reports {
            xml.required.set(true)
            html.required.set(true)
        }
    }
    tasks.withType<JacocoCoverageVerification>().configureEach {
        violationRules {
            rule {
                element = "BUNDLE"
                limit {
                    counter = "LINE"
                    // No-Docker regression floor, sized just below the
                    // coverage `check` produces WITHOUT Docker: the store
                    // ITs (Mongo/ClickHouse via Testcontainers) assume-skip
                    // when Docker is absent, so the floor must hold for the
                    // leaner no-Docker run. A run WITH Docker covers much
                    // more (e.g. spyglass-core ~77% LINE vs the no-Docker
                    // baseline) and clears the floor with room to spare.
                    // Final v1.0.0 targets remain 0.90 api / 0.80 plugin
                    // (docs/report/gap/plan/plan.md §6.0).
                    minimum = when (project.name) {
                        // No ITs here, so the floor tracks real measured
                        // coverage (~26% LINE).
                        "spyglass-api" -> 0.24.toBigDecimal()
                        // Most of spyglass-core's coverage comes from the
                        // Docker-gated store ITs; this is the no-Docker
                        // baseline, kept conservative so a green no-Docker
                        // `check` doesn't trip on the ITs being skipped.
                        // Raise once no-Docker coverage is measured on a
                        // Docker-less runner.
                        "spyglass-core" -> 0.20.toBigDecimal()
                        // Two undo-stack ITs are Docker-gated; same
                        // no-Docker-baseline reasoning as spyglass-core.
                        "spyglass" -> 0.20.toBigDecimal()
                        // Proxy module is read-only and thin (just a
                        // command + renderer + parser); no floor until
                        // its test suite stabilises.
                        "spyglass-velocity" -> 0.00.toBigDecimal()
                        else -> 0.00.toBigDecimal()
                    }
                }
            }
        }
    }
    tasks.matching { it.name == "check" }.configureEach {
        dependsOn("jacocoTestCoverageVerification")
    }
}

tasks.register<Exec>("regression") {
    group = "verification"
    description = "Runs the Spyglass regression harness (requires ../RP_Server up)."
    dependsOn("deployToRpServer")
    workingDir = rootProject.rootDir
    commandLine = listOf("python3", "regression/run.py")
}

tasks.register("deployToRpServer") {
    group = "deployment"
    description = "Builds the lean plugin jar and copies it to ../RP_Server/plugins/Spyglass.jar."
    dependsOn(":spyglass:leanJar")
    doLast {
        val pluginProject = project(":spyglass")
        val candidate = pluginProject.layout.buildDirectory
            .file("libs/Spyglass-${pluginProject.version}.jar")
            .get().asFile
        require(candidate.exists()) { "Expected built jar at $candidate but did not find one." }
        val destination = rootProject.layout.projectDirectory
            .dir("../RP_Server/plugins").file("Spyglass.jar").asFile
        destination.parentFile.mkdirs()
        candidate.copyTo(destination, overwrite = true)
        println("Deployed ${candidate.name} -> $destination")
    }
}

extra.apply {
    set("paperApiVersion", paperApiVersion)
    set("velocityApiVersion", velocityApiVersion)
    set("mongoDriverVersion", mongoDriverVersion)
    set("clickhouseClientVersion", clickhouseClientVersion)
    set("sqliteJdbcVersion", sqliteJdbcVersion)
    set("configurateVersion", configurateVersion)
    set("cloudMinecraftVersion", cloudMinecraftVersion)
    set("cloudCoreVersion", cloudCoreVersion)
    set("junitVersion", junitVersion)
    set("assertjVersion", assertjVersion)
    set("mockitoVersion", mockitoVersion)
    set("testcontainersVersion", testcontainersVersion)
    set("jetbrainsAnnotationsVersion", jetbrainsAnnotationsVersion)
    set("faweVersion", faweVersion)
    set("worldeditVersion", worldeditVersion)
}
