plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

val paperApiVersion: String by rootProject.extra
val mongoDriverVersion: String by rootProject.extra
val configurateVersion: String by rootProject.extra
val cloudMinecraftVersion: String by rootProject.extra
val cloudCoreVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val mockitoVersion: String by rootProject.extra
val testcontainersVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    maven("https://maven.enginehub.org/repo/")
}

dependencies {
    implementation(project(":api"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.15")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.15")
    implementation("org.mongodb:mongodb-driver-sync:$mongoDriverVersion")
    implementation("org.mongodb:bson-record-codec:$mongoDriverVersion")
    implementation("org.spongepowered:configurate-hocon:$configurateVersion")
    implementation("org.incendo:cloud-paper:$cloudMinecraftVersion")
    implementation("org.incendo:cloud-annotations:$cloudCoreVersion")
    implementation("org.incendo:cloud-minecraft-extras:$cloudMinecraftVersion")

    testImplementation(project(":api"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:mongodb:$testcontainersVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.processResources {
    filesMatching("plugin.yml") {
        expand("version" to project.version)
    }
}

tasks.shadowJar {
    archiveBaseName.set("Spyglass")
    archiveClassifier.set("")
}

tasks.jar {
    archiveBaseName.set("Spyglass")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.named<Test>("test"))
}

tasks.test {
    finalizedBy(tasks.named("jacocoTestReport"))
}
