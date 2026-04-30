plugins {
    java
    application
    id("com.gradleup.shadow") version "9.0.0-rc2"
}

val paperApiVersion: String by rootProject.extra
val clickhouseClientVersion: String by rootProject.extra
val picocliVersion: String by rootProject.extra
val sqliteJdbcVersion: String by rootProject.extra
val mysqlConnectorVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

application {
    mainClass.set("net.medievalrp.spyglass.importer.Main")
}

// Same Guava pin as the other modules — ClickHouse 0.9.x pulls 33.4.6
// transitively, but we keep the runtime classpath consistent with the
// version Paper / Velocity ship.
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.3.1-jre")
    }
}

dependencies {
    // Storage layer + records. The importer writes EventRecord
    // instances through the same ClickHouseRecordStore the live plugin
    // uses, so we can't drift schema across import vs ingest paths.
    implementation(project(":spyglass-core"))

    // BlockSnapshot.material is org.bukkit.Material. The CLI runs
    // outside any server, so paper-api has to be on the runtime
    // classpath. Shaded into the fat jar like spyglass-velocity does.
    implementation("io.papermc.paper:paper-api:$paperApiVersion")

    // CLI parsing. Picocli is small, dependency-free, and emits good
    // help text from annotations alone.
    implementation("info.picocli:picocli:$picocliVersion")
    annotationProcessor("info.picocli:picocli-codegen:$picocliVersion")

    // CoreProtect ships SQLite by default; MySQL is the production
    // option for shared / multi-server installs. Both drivers are tiny
    // and the user picks one at runtime via the --source URL scheme.
    implementation("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")
    implementation("com.mysql:mysql-connector-j:$mysqlConnectorVersion")

    // ClickHouse client v2 logs through SLF4J; without a binding the
    // first log call prints a "No SLF4J providers were found" warning
    // to stderr. Ship slf4j-nop so we silently no-op those.
    runtimeOnly("org.slf4j:slf4j-nop:2.0.13")

    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.shadowJar {
    archiveBaseName.set("spyglass-importer")
    archiveClassifier.set("")
    // Same Guava relocation rationale as spyglass-velocity: ClickHouse
    // and Paper disagree on Guava version, and shadow's ASM remapper
    // is fragile on broader rules. Keep it com.google.* only.
    relocate("com.google.common", "net.medievalrp.spyglass.importer.shaded.guava")
    relocate("com.google.thirdparty", "net.medievalrp.spyglass.importer.shaded.guava.thirdparty")
}

tasks.jar {
    archiveBaseName.set("spyglass-importer")
    // Give the thin jar a classifier so it doesn't write the same
    // spyglass-importer-<ver>.jar path as the shaded shadowJar
    // (archiveClassifier = ""). Without this the two outputs collide and
    // Gradle 9's strict task-graph validation flags the dist / start-script
    // tasks as consuming an undeclared output. Mirrors spyglass-velocity.
    archiveClassifier.set("thin")
}

tasks.build {
    dependsOn(tasks.shadowJar)
}

tasks.test {
    useJUnitPlatform()
}
