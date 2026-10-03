plugins {
	kotlin("jvm") version "2.2.21"
	kotlin("plugin.spring") version "2.2.21"
	id("org.springframework.boot") version "3.5.6"
	id("io.spring.dependency-management") version "1.1.7"
	kotlin("plugin.jpa") version "2.2.21"
}

group = "dev.alllexey"
version = "1.7.0"
description = "Backend for ITMO.Widgets app"

// 1.21.4 keeps the Boot 3.x test API and supports Docker Engine 29.
extra["testcontainers.version"] = "1.21.4"

java {
	toolchain {
		languageVersion = JavaLanguageVersion.of(21)
	}
}

repositories {
	mavenCentral()
}

dependencies {
	implementation("dev.alllexey:my-itmo-api:1.8.2")

	implementation("com.auth0:java-jwt:4.5.0")
	implementation("com.auth0:jwks-rsa:0.23.0")

	implementation("com.google.firebase:firebase-admin:9.7.0")

	implementation("org.springframework.boot:spring-boot-starter-data-jpa")
	implementation("org.springframework.boot:spring-boot-starter-web")
	implementation("org.springframework.boot:spring-boot-starter-security")
	implementation("org.springframework.retry:spring-retry")
	implementation("org.springframework.boot:spring-boot-starter-aop")
	implementation("com.fasterxml.jackson.module:jackson-module-kotlin")
	implementation("org.jetbrains.kotlin:kotlin-reflect")
	implementation("org.flywaydb:flyway-core")
	implementation("org.jsoup:jsoup:1.21.1")
	runtimeOnly("org.flywaydb:flyway-database-postgresql")
	runtimeOnly("org.postgresql:postgresql")
	testImplementation("org.springframework.boot:spring-boot-starter-test")
	testImplementation("org.springframework.security:spring-security-test")
	testImplementation("org.jetbrains.kotlin:kotlin-test-junit5")
	testRuntimeOnly("org.junit.platform:junit-platform-launcher")
	testImplementation("org.testcontainers:postgresql")
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

tasks.withType<Test> {
	useJUnitPlatform()
}

tasks.test {
	// `-Pcontract.record=true` rewrites src/test/resources/contract (see its README); off by default.
	providers.gradleProperty("contract.record").orNull?.let { systemProperty("contract.record", it) }
}

// Released Core decodes the golden fixtures as installed apps do. Each suite sees one Core release
// and JUnit, no project classes: the releases share packages, so they cannot share a classpath.
// Both compile the harness in src/compatCore170Test/harness; compatCore120Test goes when
// app.minimum reaches 2.2.
val compatCores = mapOf("compatCore120Test" to "1.2.0", "compatCore170Test" to "1.7.0")
val contractFixtures = layout.projectDirectory.dir("src/test/resources/contract")

testing {
	suites {
		compatCores.forEach { (suiteName, release) ->
			register<JvmTestSuite>(suiteName) {
				useJUnitJupiter()
				dependencies {
					implementation("dev.alllexey:itmo-widgets-core:$release")
				}
				targets.all {
					testTask.configure {
						// Read relative to the project directory; an input, so a changed fixture always reruns the suite.
						inputs.dir(contractFixtures)
							.withPropertyName("contractFixtures")
							.withPathSensitivity(PathSensitivity.RELATIVE)
					}
				}
			}
		}

		// The package rules of docs/architecture.md, read from src/main/kotlin with Konsist. A suite of its own
		// keeps Konsist's Kotlin compiler off the classpath and heap of the Spring tests.
		register<JvmTestSuite>("architectureTest") {
			useJUnitJupiter()
			dependencies {
				implementation("com.lemonappdev:konsist:0.17.3")
			}
			targets.all {
				testTask.configure {
					// It reads sources, not classes: a changed source file always reruns it.
					inputs.dir(layout.projectDirectory.dir("src/main/kotlin"))
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
