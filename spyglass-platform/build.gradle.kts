// The Crusalis platform integration (Crusalis phase 5 §6). Optional in every sense:
// nothing here is loaded unless `integrations.crusalis-platform.enabled` is true AND
// the CrusalisPlatformSDK plugin is on the server, so another network running Spyglass
// never touches a class in this module.
//
// It is a module rather than a package inside :spyglass so the dependency on
// platform-sdk-api cannot leak into the plugin's own compile classpath by accident —
// a compile error is a better guard than a code review.

plugins {
    java
    // The §6.1 acceptance criterion for this module is a number — 1 µs mean and 0 B/op
    // per action on the calling thread — and a budget nobody measures is a comment.
    id("me.champeau.jmh") version "0.7.2"
}

val paperApiVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val mockitoVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra

// The Crusalis SDK's compile-only API contract. Published without credentials on
// purpose (Crusalis §6.1): a fork of Spyglass must build without a Crusalis token, so
// this repository never needs one. mavenLocal() first, so the operator can work on both
// repositories at once with `./gradlew :platform-sdk-api:publishToMavenLocal`.
val crusalisPlatformApiVersion = "0.1.0-SNAPSHOT"

repositories {
    mavenLocal()
    maven("https://maven.crusalis.net") { name = "crusalis" }
}

java {
    toolchain {
        // 25, like every other module here. The Crusalis API artifact is built for 21
        // so that any consumer can read it; this module is a Spyglass module and
        // follows Spyglass.
        languageVersion = JavaLanguageVersion.of(25)
    }
    withSourcesJar()
}

dependencies {
    // compileOnly, never implementation: exactly one copy of the Crusalis API exists
    // per JVM and it belongs to the SDK plugin. Shading it here would give a server a
    // second copy, which is precisely what Crusalis §4.1 exists to prevent.
    compileOnly("net.crusalis.platform:platform-sdk-api:$crusalisPlatformApiVersion")
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    // The records this module maps. api, not core: the integration reads committed
    // records and knows nothing about storage.
    implementation(project(":spyglass-api"))

    testImplementation("net.crusalis.platform:platform-sdk-api:$crusalisPlatformApiVersion")
    testImplementation("io.papermc.paper:paper-api:$paperApiVersion")
    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testImplementation("org.mockito:mockito-core:$mockitoVersion")
    testImplementation("org.mockito:mockito-junit-jupiter:$mockitoVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JavaCompile>().configureEach {
    options.encoding = "UTF-8"
}

jmh {
    profilers.set(listOf("gc"))
    resultFormat.set("JSON")
    resultsFile.set(layout.buildDirectory.file("results/jmh/results.json"))
}

dependencies {
    // The benchmarks map real records into a slot that allocates nothing, so what the
    // GC profiler reports is this module's allocation and nobody else's.
    "jmhImplementation"("net.crusalis.platform:platform-sdk-api:$crusalisPlatformApiVersion")
    "jmhImplementation"("io.papermc.paper:paper-api:$paperApiVersion")
}
