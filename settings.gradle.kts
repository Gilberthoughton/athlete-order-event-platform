plugins {
    // Lets Gradle auto-provision the Java 21 toolchain if it isn't already installed,
    // so the project builds on a reviewer's machine without a manual JDK setup.
    id("org.gradle.toolchains.foojay-resolver-convention") version "0.8.0"
}

rootProject.name = "athlete-order-event-platform"

include("order-service")
include("inventory-consumer")
