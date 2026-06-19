plugins {
    java
    id("com.gradleup.shadow") version "8.3.6"
}

val paperApiVersion: String by rootProject.extra
val mongoDriverVersion: String by rootProject.extra
val clickhouseClientVersion: String by rootProject.extra
val configurateVersion: String by rootProject.extra
val cloudMinecraftVersion: String by rootProject.extra
val cloudCoreVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val mockitoVersion: String by rootProject.extra
val testcontainersVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra
val faweVersion: String by rootProject.extra
val worldeditVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    maven("https://maven.enginehub.org/repo/")
}

// ClickHouse 0.9.x pulls Guava 33.4.6 transitively, but Paper / WorldEdit
// pin Guava strictly to 33.3.1. Force the Paper version so the runtime
// classpath stays consistent with what the server ships; CH's Guava
// usage is small and version-tolerant.
configurations.all {
    resolutionStrategy {
        force("com.google.guava:guava:33.3.1-jre")
    }
}

dependencies {
    // spyglass-core re-exports spyglass-api, mongodb-driver-sync,
    // bson-record-codec, clickhouse-jdbc, client-v2,
    // clickhouse-http-client, and clickhouse-data via `api(...)` —
    // anything that used to import them directly from this module still
    // works without the per-dep declaration.
    implementation(project(":spyglass-core"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
    compileOnly("com.sk89q.worldedit:worldedit-core:$worldeditVersion")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:$worldeditVersion")
    // FastAsyncWorldEdit API (Maven Central). compileOnly — the server
    // provides FAWE at runtime; this just makes FaweHook / FaweBatchLogger
    // compilable. Core-only: every FAWE import is com.fastasyncworldedit.core.*.
    // Pulled non-transitively: FAWE-Core's POM otherwise drags in a gson it
    // pins (strictly 2.11.0) that collides with Paper/adventure's gson, and
    // we need none of its transitives — paper-api + worldedit already supply
    // every other type these two classes touch.
    compileOnly("com.fastasyncworldedit:FastAsyncWorldEdit-Core:$faweVersion") {
        isTransitive = false
    }
    implementation("org.spongepowered:configurate-hocon:$configurateVersion")
    implementation("org.incendo:cloud-paper:$cloudMinecraftVersion")
    implementation("org.incendo:cloud-annotations:$cloudCoreVersion")
    implementation("org.incendo:cloud-minecraft-extras:$cloudMinecraftVersion")

    testImplementation(project(":spyglass-api"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    // WorldEdit is compileOnly for the production jar (server provides
    // it). worldedit-core is on the test *compile* classpath so
    // WorldEditActorsTest can mock the WE Actor; worldedit-bukkit stays
    // runtime-only for RollbackEngineChaosTest's FAWE-availability branch
    // (exercised via mockStatic, which only needs the classes verifiable
    // at test runtime).
    testImplementation("com.sk89q.worldedit:worldedit-core:$worldeditVersion")
    testRuntimeOnly("com.sk89q.worldedit:worldedit-bukkit:$worldeditVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:mongodb:$testcontainersVersion")
    testImplementation("org.testcontainers:clickhouse:$testcontainersVersion")
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
    // The `bench` and `ch-bench` tags cover long-running throughput
    // benches (testcontainers Mongo + ClickHouse, multi-minute runs).
    // They're excluded from the default test cycle so unit+IT stays
    // fast; run via `./gradlew :spyglass:ingestBench` or
    // `./gradlew :spyglass:clickhouseBench`.
    useJUnitPlatform {
        excludeTags("bench", "ch-bench")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

// Ingest throughput benchmark: v2 AsyncRecorder + MongoRecordStore vs a
// synthetic v1-equivalent pipeline (unbounded queue + scheduled drain +
// bulkWrite with v1 doc shape) — see IngestThroughputBench.java. Gated
// on Docker. Not part of `check`; run explicitly for metrics.
tasks.register<Test>("ingestBench") {
    description = "Runs the Spyglass vs v1 ingest throughput benchmark (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("bench")
    }
    // Don't run jacoco on the bench — instrumentation perturbs timing.
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    testLogging {
        events("started", "passed", "failed", "standard_out")
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Heavy: two pipelines × three scenarios with 200k-record overload.
    // Bump test-jvm heap so the v1 unbounded deque can swell without GC
    // thrashing during the overload scenario.
    maxHeapSize = "2g"
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    // Forward SG_BENCH_* system properties from the gradle invocation
    // into the test JVM so CI can override defaults without recompiling
    // (./gradlew :spyglass:ingestBench -DSG_BENCH_SUSTAINED_SEC=60).
    for ((key, value) in System.getProperties()) {
        val name = key.toString()
        if (name.startsWith("SG_BENCH_")) {
            systemProperty(name, value.toString())
        }
    }
}
