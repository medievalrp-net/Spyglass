plugins {
    base
}

val paperApiVersion = "1.21.8-R0.1-SNAPSHOT"
val mongoDriverVersion = "5.5.0"
val configurateVersion = "4.2.0"
val cloudMinecraftVersion = "2.0.0-beta.15"
val cloudCoreVersion = "2.0.0"
val junitVersion = "5.13.4"
val assertjVersion = "3.27.6"
val mockitoVersion = "5.20.0"
val testcontainersVersion = "1.21.3"
val jetbrainsAnnotationsVersion = "26.0.2"

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
                    // Current coverage floor. Ramp step 1/4 — raised
                    // from 0.05 to 0.10 after Phase 2 landed (added
                    // ResultRendererTest + touched enough tested code
                    // that both modules clear 0.10). Targets per the
                    // v1.0.0 plan are 0.90 api / 0.80 plugin; see
                    // docs/report/gap/plan/plan.md §6.0 for the ramp.
                    minimum = when (project.name) {
                        "api" -> 0.10.toBigDecimal()
                        "plugin" -> 0.10.toBigDecimal()
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
    description = "Shadow-builds the plugin jar and copies it to ../RP_Server/plugins/Spyglass.jar."
    dependsOn(":plugin:shadowJar")
    doLast {
        val pluginProject = project(":plugin")
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
    set("mongoDriverVersion", mongoDriverVersion)
    set("configurateVersion", configurateVersion)
    set("cloudMinecraftVersion", cloudMinecraftVersion)
    set("cloudCoreVersion", cloudCoreVersion)
    set("junitVersion", junitVersion)
    set("assertjVersion", assertjVersion)
    set("mockitoVersion", mockitoVersion)
    set("testcontainersVersion", testcontainersVersion)
    set("jetbrainsAnnotationsVersion", jetbrainsAnnotationsVersion)
}
