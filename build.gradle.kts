plugins {
    java
    id("org.springframework.boot") version "4.0.8"
    id("io.spring.dependency-management") version "1.1.7"
}

group = "com.carddraft"
version = "0.1.0"
description = "Drafts verifiable product cards from supplier documents"

java {
    toolchain {
        languageVersion = JavaLanguageVersion.of(21)
    }
}

repositories {
    mavenCentral()
}

val springAiBom = "org.springframework.ai:spring-ai-bom:2.0.1"

dependencies {
    implementation(platform(springAiBom))
    annotationProcessor(platform(springAiBom))

    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test") {
        exclude(group = "org.mockito")
    }
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
