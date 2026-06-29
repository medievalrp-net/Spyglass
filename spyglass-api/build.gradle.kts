plugins {
    `java-library`
    `maven-publish`
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