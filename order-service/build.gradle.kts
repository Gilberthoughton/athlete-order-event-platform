plugins {
    // Generates Java classes from the Avro schemas under src/main/avro (validates them at build time).
    id("com.github.davidmc24.gradle.plugin.avro") version "1.9.1"
}

dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-validation")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")

    // Avro integration-event contract + Confluent Schema Registry serializer.
    implementation("org.apache.avro:avro")
    implementation("io.confluent:kafka-avro-serializer:7.6.1")
    // The Confluent schema-registry client references org.apache.commons.codec.Charsets but does
    // not pull commons-codec transitively under Boot's dependency management; add it explicitly.
    implementation("commons-codec:commons-codec")

    // Observability: Prometheus metrics + structured JSON logging.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

avro {
    // Generate java.lang.String fields (not CharSequence) for ergonomic code.
    stringType.set("String")
}

// ---------------------------------------------------------------------------
// Separate integration-test source set: real PostgreSQL + Kafka via Testcontainers.
// Unit tests (`test`) stay Docker-free and fast; `integrationTest` is opt-in and requires Docker.
// ---------------------------------------------------------------------------
sourceSets {
    create("integrationTest") {
        compileClasspath += sourceSets["main"].output + sourceSets["test"].output
        runtimeClasspath += sourceSets["main"].output + sourceSets["test"].output
    }
}

configurations["integrationTestImplementation"].extendsFrom(configurations["testImplementation"])
configurations["integrationTestRuntimeOnly"].extendsFrom(configurations["testRuntimeOnly"])

dependencies {
    "integrationTestImplementation"("org.springframework.boot:spring-boot-testcontainers")
    "integrationTestImplementation"("org.testcontainers:junit-jupiter")
    "integrationTestImplementation"("org.testcontainers:postgresql")
    "integrationTestImplementation"("org.testcontainers:kafka")
}

tasks.register<Test>("integrationTest") {
    description = "Runs integration tests against Testcontainers (requires Docker)."
    group = "verification"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()
    shouldRunAfter(tasks.named("test"))
    testLogging {
        exceptionFormat = org.gradle.api.tasks.testing.logging.TestExceptionFormat.FULL
    }
    // Modern Docker daemons require API >= 1.40; docker-java otherwise negotiates 1.32 and is
    // rejected. Pin it for the forked test JVM. Reviewers on Docker Desktop can leave the rest
    // unset; Colima/Podman users export DOCKER_HOST (forwarded below since workers don't inherit it).
    systemProperty("api.version", System.getProperty("api.version", "1.43"))
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.43")
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
    System.getenv("TESTCONTAINERS_RYUK_DISABLED")?.let { environment("TESTCONTAINERS_RYUK_DISABLED", it) }
    System.getenv("TESTCONTAINERS_HOST_OVERRIDE")?.let { environment("TESTCONTAINERS_HOST_OVERRIDE", it) }
}
