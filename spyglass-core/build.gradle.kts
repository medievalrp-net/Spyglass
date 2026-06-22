plugins {
    `java-library`
}

val paperApiVersion: String by rootProject.extra
val mongoDriverVersion: String by rootProject.extra
val clickhouseClientVersion: String by rootProject.extra
val sqliteJdbcVersion: String by rootProject.extra
val mariaDbDriverVersion: String by rootProject.extra
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
    // Records and predicates the storage layer encodes / queries against.
    api(project(":spyglass-api"))

    // BlockSnapshot.material is org.bukkit.Material — paper-api stays
    // compileOnly here so the storage module can be loaded into either a
    // Paper plugin (server provides the API) or a Velocity plugin (which
    // ships paper-api inside its shadow jar) without forcing a hard
    // runtime dependency on Bukkit.
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    // Storage drivers. Exposed as `api` so consumer modules (spyglass,
    // spyglass-velocity) can use Mongo / ClickHouse driver classes
    // directly — same contract as before the extraction.
    api("org.mongodb:mongodb-driver-sync:$mongoDriverVersion")
    api("org.mongodb:bson-record-codec:$mongoDriverVersion")
    api("com.clickhouse:clickhouse-jdbc:$clickhouseClientVersion")
    api("com.clickhouse:client-v2:$clickhouseClientVersion")
    api("com.clickhouse:clickhouse-http-client:$clickhouseClientVersion")
    api("com.clickhouse:clickhouse-data:$clickhouseClientVersion")
    runtimeOnly("org.apache.httpcomponents.client5:httpclient5:5.3.1")

    // Embedded SQLite backend (#106): a third, zero-ops record store for
    // small/medium servers. xerial sqlite-jdbc bundles the native SQLite
    // engine; exposed as `api` so the plugin + proxy can construct the
    // store directly, mirroring the Mongo / ClickHouse drivers above.
    api("org.xerial:sqlite-jdbc:$sqliteJdbcVersion")

    // MariaDB / MySQL backend (#169): the client-server SQL store for
    // operators who already run a MariaDB or MySQL server. MariaDB
    // Connector/J speaks both wire protocols, so one driver serves
    // backend = "mariadb" and "mysql". Exposed as `api` like the other
    // drivers so the plugin + proxy construct the store directly.
    api("org.mariadb.jdbc:mariadb-java-client:$mariaDbDriverVersion")

    testImplementation(project(":spyglass-api"))
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testImplementation("org.testcontainers:junit-jupiter:$testcontainersVersion")
    testImplementation("org.testcontainers:mongodb:$testcontainersVersion")
    testImplementation("org.testcontainers:clickhouse:$testcontainersVersion")
    testImplementation("org.testcontainers:mariadb:$testcontainersVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.named<Test>("test"))
}

tasks.test {
    // The `bench`, `ch-bench`, and `sqlite-bench` tags cover long-running
    // throughput benches; they live here now alongside the storage code they
    // exercise. Run via `./gradlew :spyglass-core:clickhouseBench` /
    // `:spyglass-core:sqliteBench`.
    useJUnitPlatform {
        excludeTags("bench", "ch-bench", "sqlite-bench", "mariadb-bench")
    }
    finalizedBy(tasks.named("jacocoTestReport"))
}

tasks.register<Test>("sqliteBench") {
    description = "Spyglass·SQLite disk + rollback-read benchmark (#106; no Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("sqlite-bench")
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
    maxHeapSize = "4g"
    for ((key, value) in System.getProperties()) {
        val name = key.toString()
        if (name.startsWith("SG_BENCH_")) {
            systemProperty(name, value.toString())
        }
    }
}

tasks.register<Test>("mariadbBench") {
    description = "Spyglass·MariaDB disk + rollback-read benchmark (#169; requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["test"].output.classesDirs
    classpath = sourceSets["test"].runtimeClasspath
    useJUnitPlatform {
        includeTags("mariadb-bench")
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
    maxHeapSize = "4g"
    systemProperty("org.slf4j.simpleLogger.defaultLogLevel", "warn")
    for ((key, value) in System.getProperties()) {
        val name = key.toString()
        if (name.startsWith("SG_BENCH_")) {
            systemProperty(name, value.toString())
        }
    }
}

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
