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
