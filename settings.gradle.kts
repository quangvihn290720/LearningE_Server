pluginManagement {
    repositories {
        gradlePluginPortal()
        mavenCentral()
    }
}

plugins {
    // Lets Gradle download a JDK 21 toolchain when none is installed.
    id("org.gradle.toolchains.foojay-resolver-convention") version "1.0.0"
}

dependencyResolutionManagement {
    repositories { mavenCentral() }
}

rootProject.name = "LearningE_Server"

include(
    "proto",
    "common",
    "edge",
    "auth",
    "sync",
    "content",
    "gamification",
    "insight",
    "all-in-one",
)
