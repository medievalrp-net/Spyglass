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

allprojects {
    group = providers.gradleProperty("group").get()
    version = providers.gradleProperty("version").get()

    repositories {
        mavenCentral()
        maven("https://repo.papermc.io/repository/maven-public/")
    }
}

subprojects {
    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
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
}
