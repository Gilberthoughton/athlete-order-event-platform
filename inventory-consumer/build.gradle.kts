dependencies {
    implementation("org.springframework.boot:spring-boot-starter-web")
    implementation("org.springframework.boot:spring-boot-starter-jdbc")
    implementation("org.springframework.boot:spring-boot-starter-actuator")
    implementation("org.springframework.kafka:spring-kafka")

    // Observability: Prometheus metrics + structured JSON logging.
    implementation("io.micrometer:micrometer-registry-prometheus")
    implementation("net.logstash.logback:logstash-logback-encoder:7.4")

    implementation("com.fasterxml.jackson.datatype:jackson-datatype-jsr310")

    runtimeOnly("org.postgresql:postgresql")
    implementation("org.flywaydb:flyway-core")
    runtimeOnly("org.flywaydb:flyway-database-postgresql")

    testImplementation("org.springframework.boot:spring-boot-starter-test")
}

// ---------------------------------------------------------------------------
// Separate integration-test source set: real PostgreSQL + Kafka via Testcontainers.
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
    // Forward Docker settings to the forked test JVM (see order-service for rationale).
    systemProperty("api.version", System.getProperty("api.version", "1.43"))
    environment("DOCKER_API_VERSION", System.getenv("DOCKER_API_VERSION") ?: "1.43")
    System.getenv("DOCKER_HOST")?.let { environment("DOCKER_HOST", it) }
    System.getenv("TESTCONTAINERS_RYUK_DISABLED")?.let { environment("TESTCONTAINERS_RYUK_DISABLED", it) }
    System.getenv("TESTCONTAINERS_HOST_OVERRIDE")?.let { environment("TESTCONTAINERS_HOST_OVERRIDE", it) }
}
