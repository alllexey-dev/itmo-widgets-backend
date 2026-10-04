plugins {
    alias(libs.plugins.kotlin.jvm)
    alias(libs.plugins.kotlin.spring)
    alias(libs.plugins.spring.boot)
    alias(libs.plugins.spring.dependency.management)
    alias(libs.plugins.kotlin.jpa)
    alias(libs.plugins.ktlint)
}

group = "dev.alllexey"
version = "1.7.0"
description = "Backend for ITMO.Widgets app"

// Overrides the version in Spring Boot's BOM; the reason is next to it in the catalog.
extra["testcontainers.version"] = libs.versions.testcontainers.get()

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(libs.my.itmo.api)

    implementation(libs.java.jwt)
    implementation(libs.jwks.rsa)

    implementation(libs.firebase.admin)

    implementation(libs.spring.boot.starter.data.jpa)
    implementation(libs.spring.boot.starter.web)
    implementation(libs.spring.boot.starter.security)
    implementation(libs.spring.retry)
    implementation(libs.spring.boot.starter.aop)
    implementation(libs.jackson.module.kotlin)
    implementation(libs.kotlin.reflect)
    implementation(libs.flyway.core)
    implementation(libs.jsoup)
    runtimeOnly(libs.flyway.database.postgresql)
    runtimeOnly(libs.postgresql)
    testImplementation(libs.spring.boot.starter.test)
    testImplementation(libs.spring.security.test)
    testImplementation(libs.kotlin.test.junit5)
    testRuntimeOnly(libs.junit.platform.launcher)
    testImplementation(libs.testcontainers.postgresql)
    // Generates docs/openapi.json in OpenApiSnapshotTest; never on the runtime classpath.
    testImplementation(libs.springdoc.openapi.starter.webmvc.api)
}

kotlin {
    compilerOptions {
        freeCompilerArgs.addAll("-Xjsr305=strict")
    }
}

allOpen {
    annotation("jakarta.persistence.Entity")
    annotation("jakarta.persistence.MappedSuperclass")
    annotation("jakarta.persistence.Embeddable")
}

ktlint {
    version = libs.versions.ktlint.cli
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// The build cache (gradle.properties) reuses a test result while the task inputs are unchanged.
// Tests that read files by path relative to the project directory declare those files here, so a
// changed fixture or runbook always reruns them.
val contractFixtures = layout.projectDirectory.dir("src/test/resources/contract")

fun Test.readsContractFixtures() {
    inputs
        .dir(contractFixtures)
        .withPropertyName("contractFixtures")
        .withPathSensitivity(PathSensitivity.RELATIVE)
}

tasks.test {
    readsContractFixtures()
    // AccountDeletionRunbookTest runs the runbook against PostgreSQL.
    inputs
        .file(layout.projectDirectory.file("docs/ops/account-deletion.sql"))
        .withPropertyName("accountDeletionRunbook")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // OpenApiSnapshotTest compares the generated spec with it, so a hand edit or a stale copy fails the next run.
    inputs
        .file(layout.projectDirectory.file("docs/openapi.json"))
        .withPropertyName("openApiSnapshot")
        .withPathSensitivity(PathSensitivity.RELATIVE)
    // `-Pcontract.record=true` rewrites src/test/resources/contract (see its README); off by default.
    providers.gradleProperty("contract.record").orNull?.let { systemProperty("contract.record", it) }
    // `-Popenapi.record=true` rewrites docs/openapi.json (`scripts/verify.sh openapi`); off by default.
    providers.gradleProperty("openapi.record").orNull?.let { systemProperty("openapi.record", it) }
}

// Released Core decodes the golden fixtures as installed apps do. Each suite sees one Core release
// and JUnit, no project classes: the releases share packages, so they cannot share a classpath.
// Both compile the harness in src/compatCore170Test/harness; compatCore120Test goes when
// app.minimum reaches 2.2.
val compatCores =
    mapOf(
        "compatCore120Test" to libs.itmo.widgets.core.v120,
        "compatCore170Test" to libs.itmo.widgets.core.v170,
    )

testing {
    suites {
        compatCores.forEach { (suiteName, releasedCore) ->
            register<JvmTestSuite>(suiteName) {
                useJUnitJupiter()
                dependencies {
                    implementation(releasedCore)
                }
                targets.all {
                    testTask.configure { readsContractFixtures() }
                }
            }
        }

        // The package rules of docs/architecture.md, read from src/main/kotlin with Konsist. A suite of its own
        // keeps Konsist's Kotlin compiler off the classpath and heap of the Spring tests.
        register<JvmTestSuite>("architectureTest") {
            useJUnitJupiter()
            dependencies {
                implementation(libs.konsist)
            }
            targets.all {
                testTask.configure {
                    // It reads sources, not classes: a changed source file always reruns it.
                    inputs
                        .dir(layout.projectDirectory.dir("src/main/kotlin"))
                        .withPropertyName("mainSources")
                        .withPathSensitivity(PathSensitivity.RELATIVE)
                }
            }
        }
    }
}

compatCores.keys.forEach { suiteName ->
    kotlin.sourceSets.named(suiteName) { kotlin.srcDir("src/compatCore170Test/harness") }
}

tasks.named("check") {
    dependsOn(compatCores.keys.map { testing.suites.named(it) })
    dependsOn(testing.suites.named("architectureTest"))
}
