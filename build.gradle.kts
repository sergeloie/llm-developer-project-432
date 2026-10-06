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
    // Document parsing: PDF text per page, DOCX paragraphs, XLSX specification rows.
    implementation("org.apache.pdfbox:pdfbox:3.0.8")
    implementation("org.apache.poi:poi-ooxml:5.5.1")

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

// The metrics harness, as a start target rather than a shell script.
//
// A Gradle task rather than `bootRun` with a property, because the harness is a command someone
// runs deliberately and compares with the last run - not a mode the service happens to have. The
// report lands in build/reports/metrics/ where the next run can be diffed against it.
//
//   ./gradlew metrics            - the reference set's declared default subset
//   ./gradlew metrics -Pfull     - every document in the reference set
//
// Both need the model server and the compose stack up. Neither runs as part of `build`.
tasks.register<JavaExec>("metrics") {
    group = "verification"
    description = "Generates cards for the reference set and writes a quality report."
    classpath = sourceSets["main"].runtimeClasspath
    mainClass = "org.springframework.boot.loader.launch.JarLauncher"
    if (project.hasProperty("full")) {
        args = listOf("--spring.profiles.active=default")
    }
    // Boot's launcher wants a packaged jar; running the class directly keeps the task usable
    // without a repackage, and the application class is what the harness is reached through.
    mainClass = "com.carddraft.CardDraftingApplication"
    systemProperty("card.metrics.enabled", "true")
    if (project.hasProperty("full")) {
        systemProperty("card.metrics.full-set", "true")
    }
    // The compose stack the harness reads and writes must be up; the ports are not the defaults
    // on this machine, so they are passed through rather than assumed.
listOf("CARD_DB_URL", "CARD_DB_USER", "CARD_DB_PASSWORD",
           "CARD_LLM_BASE_URL", "CARD_EMBEDDING_BASEURL",
           "CARD_TEMPORAL_TARGET").forEach { name ->
        System.getenv(name)?.let { environment(name, it) }
    }
}
