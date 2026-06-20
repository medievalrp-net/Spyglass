plugins {
    java
    id("com.gradleup.shadow") version "9.0.0-rc2"
}

val paperApiVersion: String by rootProject.extra
val velocityApiVersion: String by rootProject.extra
val configurateVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    maven("https://repo.papermc.io/repository/maven-public/")
}

// Same Guava pin as the spyglass module — ClickHouse 0.9.x pulls
// 33.4.6 transitively, but Velocity 3.4 ships 33.3.1. Keep the runtime
// classpath consistent with what the proxy ships.
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.3.1-jre")
    }
}

dependencies {
    // Velocity provides its API at runtime. The annotation processor
    // emits the velocity-plugin.json descriptor from the @Plugin
    // annotation on the main class.
    compileOnly("com.velocitypowered:velocity-api:$velocityApiVersion")
    annotationProcessor("com.velocitypowered:velocity-api:$velocityApiVersion")

    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    // Storage layer + records, identical to what Paper writes. The
    // codec reads stay in lock-step with Paper writes because both
    // pull the exact same artifact.
    implementation(project(":spyglass-core"))

    // BlockSnapshot.material is org.bukkit.Material; the JVM resolves
    // that class lazily, but if any record query path touches a heavy
    // field (originalBlock / newBlock) Material has to be on the
    // classloader. Shading paper-api into the proxy jar costs ~6 MB
    // compressed and removes a CNFE class of bug. Velocity has no
    // Bukkit classloader of its own, so the shaded copy is the only
    // place these classes exist on the proxy.
    implementation("io.papermc.paper:paper-api:$paperApiVersion")

    // HOCON config — same tooling Paper uses, for consistency.
    implementation("org.spongepowered:configurate-hocon:$configurateVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    archiveBaseName.set("Spyglass-Velocity")
    archiveClassifier.set("")
    // Velocity 3.4 bundles Guava 27.1 (2019) on a parent classloader
    // and the ClickHouse client v2 needs ImmutableMap.Builder.buildKeepingLast()
    // (Guava 31+). Relocating Guava into a private package isolates
    // the plugin's bundled 33.3.1 from Velocity's ancient one. Same
    // story for failureaccess, the small Guava companion artifact.
    //
    // The shadow 8.3.6 ASM remapper chokes on Type-constant references
    // when relocating arbitrary packages, so we keep the rule narrow
    // to com.google.* — none of our own bytecode references those, so
    // the remapper never has to walk records / enums of ours.
    relocate("com.google.common", "net.medievalrp.spyglass.proxy.shaded.guava")
    relocate("com.google.thirdparty", "net.medievalrp.spyglass.proxy.shaded.guava.thirdparty")
}

tasks.jar {
    archiveBaseName.set("Spyglass-Velocity")
    // Classified so the plain module jar never shares a filename with the
    // shipped shadowJar (Spyglass-Velocity-<version>.jar). Mirrors the spyglass
    // module's `thin` jar. Not a published artifact.
    archiveClassifier.set("thin")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
