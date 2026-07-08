plugins {
    `java-library`
    `maven-publish`
    signing
    // Marks this module as a Central Portal publishable unit. nmcp 1.x has no
    // per-module publish task; the root aggregation plugin owns the upload task
    // (publishAggregationToCentralPortal). Version comes from the root
    // aggregation plugin's classpath, so it is omitted here. See the root
    // build.gradle.kts.
    id("com.gradleup.nmcp")
}

val paperApiVersion: String by rootProject.extra
val mongoDriverVersion: String by rootProject.extra
val junitVersion: String by rootProject.extra
val assertjVersion: String by rootProject.extra
val jetbrainsAnnotationsVersion: String by rootProject.extra

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
    withSourcesJar()
    withJavadocJar()
}

dependencies {
    compileOnly("io.papermc.paper:paper-api:$paperApiVersion")
    compileOnly("org.mongodb:bson:$mongoDriverVersion")
    compileOnly("org.mongodb:bson-record-codec:$mongoDriverVersion")
    compileOnly("org.jetbrains:annotations:$jetbrainsAnnotationsVersion")

    testImplementation("org.junit.jupiter:junit-jupiter:$junitVersion")
    testImplementation("org.assertj:assertj-core:$assertjVersion")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
}

tasks.withType<JacocoReport>().configureEach {
    dependsOn(tasks.named<Test>("test"))
}

tasks.test {
    finalizedBy(tasks.named("jacocoTestReport"))
}

publishing {
    publications {
        create<MavenPublication>("api") {
            from(components["java"])
            groupId = "net.medievalrp"
            artifactId = "spyglass-api"
            pom {
                name.set("Spyglass API")
                description.set("Public record, query, and rollback types for the Spyglass forensics plugin.")
                url.set("https://github.com/medievalrp-net/Spyglass")
                licenses {
                    license {
                        name.set("Apache License 2.0")
                        url.set("https://www.apache.org/licenses/LICENSE-2.0.txt")
                    }
                }
                developers {
                    developer {
                        id.set("medievalrp")
                        name.set("MedievalRP")
                        url.set("https://medievalrp.net")
                    }
                }
                scm {
                    connection.set("scm:git:https://github.com/medievalrp-net/Spyglass.git")
                    developerConnection.set("scm:git:ssh://git@github.com/medievalrp-net/Spyglass.git")
                    url.set("https://github.com/medievalrp-net/Spyglass")
                }
            }
        }
    }
    repositories {
        maven {
            name = "local"
            url = uri(rootProject.layout.buildDirectory.dir("repo"))
        }
    }
}

signing {
    // Central requires every artifact be GPG-signed. CI supplies an
    // ASCII-armored key and its passphrase via env; sign only when they are
    // present so a local `build` / `publishToMavenLocal` still works keyless.
    val signingKey = providers.environmentVariable("SIGNING_KEY").orNull
    val signingPassword = providers.environmentVariable("SIGNING_PASSWORD").orNull
    isRequired = signingKey != null
    if (signingKey != null) {
        useInMemoryPgpKeys(signingKey, signingPassword)
        sign(publishing.publications["api"])
    }
}
