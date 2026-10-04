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
    implementation("org.springframework.boot:spring-boot-starter-json")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.ai:spring-ai-starter-model-openai")

    implementation("org.springframework.boot:spring-boot-starter-flyway")
    implementation("org.flywaydb:flyway-database-postgresql")
    // The SDK directly rather than the Spring Boot starter. That starter discovers workers via
    // annotations this SDK version does not have - there is no @WorkflowImpl or @ActivityImpl -
    // so its auto-discovery has nothing to bind to, and it drags in a Spring Boot 2.7 BOM.
    // Registering the worker and the client explicitly is fewer moving parts and is verifiable.
    implementation("io.temporal:temporal-sdk:1.40.0")
    testImplementation("io.temporal:temporal-testing:1.40.0")
    testImplementation("org.awaitility:awaitility:4.3.0")
    runtimeOnly("org.postgresql:postgresql")

    compileOnly("org.springframework.boot:spring-boot-configuration-processor")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
    testImplementation(platform("org.testcontainers:testcontainers-bom:2.0.5"))
    testImplementation("org.springframework.boot:spring-boot-testcontainers")
    testImplementation("org.springframework.boot:spring-boot-resttestclient")
    testImplementation("org.testcontainers:testcontainers-junit-jupiter")
    testImplementation("org.testcontainers:testcontainers-postgresql")
}

tasks.withType<Test> {
    useJUnitPlatform {
        // The live-model suite is the one thing that proves a real provider's response reaches
        // the parser. It needs a running model server and costs seconds per call, so it is opt-in:
        //   ./gradlew test          - everything except it
        //   ./gradlew test -PliveModel   - include it
        if (!project.hasProperty("liveModel")) {
            excludeTags("live-model")
        }
    }
    testLogging {
        events("passed", "skipped", "failed")
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
}
