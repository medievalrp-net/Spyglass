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

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
}

repositories {
    maven("https://maven.enginehub.org/repo/")
}

val faweJar = rootProject.rootDir.resolve("../RP_Server/plugins/FastAsyncWorldEdit.jar")

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
    implementation(project(":spyglass-api"))

    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")
    compileOnly("com.sk89q.worldedit:worldedit-core:7.3.15")
    compileOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.15")
    if (faweJar.exists()) {
        compileOnly(files(faweJar))
    }
    implementation("org.mongodb:mongodb-driver-sync:$mongoDriverVersion")
    implementation("org.mongodb:bson-record-codec:$mongoDriverVersion")
    // ClickHouse Java client v2 — RowBinary streaming inserts via
    // `client-v2`, JDBC façade for the read path. v0.9.x routes JDBC
    // onto client-v2 internally so both share the same HTTP transport
    // and connection pool.
    implementation("com.clickhouse:clickhouse-jdbc:$clickhouseClientVersion")
    implementation("com.clickhouse:client-v2:$clickhouseClientVersion")
    implementation("com.clickhouse:clickhouse-http-client:$clickhouseClientVersion")
    implementation("com.clickhouse:clickhouse-data:$clickhouseClientVersion")
    runtimeOnly("org.apache.httpcomponents.client5:httpclient5:5.3.1")
    implementation("org.spongepowered:configurate-hocon:$configurateVersion")
    implementation("org.incendo:cloud-paper:$cloudMinecraftVersion")
    implementation("org.incendo:cloud-annotations:$cloudCoreVersion")
    implementation("org.incendo:cloud-minecraft-extras:$cloudMinecraftVersion")

    testImplementation(project(":spyglass-api"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    // WorldEdit is compileOnly for the production jar (server provides
    // it), but RollbackEngineChaosTest exercises the FAWE-availability
    // branch via mockStatic, which requires the WE classes to be
    // verifiable at test runtime. Pull the same coordinates onto the
    // test runtime classpath only.
    testRuntimeOnly("com.sk89q.worldedit:worldedit-core:7.3.15")
    testRuntimeOnly("com.sk89q.worldedit:worldedit-bukkit:7.3.15")
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

// Benchmark of the same RecordStore contract against MongoDB and
// ClickHouse on identical record streams. Tag = "ch-bench"; not part
// of the default test cycle. Requires Docker.
tasks.register<Test>("clickhouseBench") {
    description = "Runs the ClickHouse vs Mongo benchmark for Spyglass (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("ch-bench")
    }
    extensions.configure<JacocoTaskExtension> {
        isEnabled = false
    }
    testLogging {
        events("started", "passed", "failed", "standard_out")
        showStandardStreams = true
        showExceptions = true
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    maxHeapSize = "2g"
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    for ((key, value) in System.getProperties()) {
        val name = key.toString()
        if (name.startsWith("SG_BENCH_")) {
            systemProperty(name, value.toString())
        }
    }
}

// Ingest throughput benchmark: v2 AsyncRecorder + MongoRecordStore vs a
// synthetic v1-equivalent pipeline (unbounded queue + scheduled drain +
// bulkWrite with v1 doc shape) — see IngestThroughputBench.java. Gated
// on Docker. Not part of `check`; run explicitly for metrics.
tasks.register<Test>("ingestBench") {
    description = "Runs the v1-vs-v2 ingest throughput benchmark (requires Docker)."
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
