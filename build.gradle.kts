import org.gradle.api.tasks.testing.logging.TestExceptionFormat

plugins {
    base
    alias(libs.plugins.spring.boot) apply false
    alias(libs.plugins.protobuf) apply false
    alias(libs.plugins.spotless)
    alias(libs.plugins.owasp.dependencycheck)
}

allprojects {
    group = "com.learninge"
    version = providers.gradleProperty("releaseVersion").getOrElse("0.1.0")
}

subprojects {
    apply(plugin = "java-library")
    apply(plugin = "jacoco")

    extensions.configure<JavaPluginExtension> {
        toolchain { languageVersion = JavaLanguageVersion.of(21) }
    }

    extensions.configure<JacocoPluginExtension> {
        toolVersion = rootProject.libs.versions.jacoco.get()
    }

    dependencies {
        val bom = platform(rootProject.libs.spring.boot.bom)
        add("api", bom)
        add("compileOnly", bom)
        add("testImplementation", bom)
        add("testRuntimeOnly", rootProject.libs.junit.platform.launcher)
    }

    tasks.withType<JavaCompile>().configureEach {
        options.encoding = "UTF-8"
        options.release = 21
        options.compilerArgs.add("-parameters")
        if (project.name != "proto") {
            options.compilerArgs.addAll(listOf("-Xlint:all,-processing,-serial,-classfile", "-Werror"))
        }
    }

    tasks.withType<Test>().configureEach {
        useJUnitPlatform()
        testLogging {
            events("failed", "skipped")
            exceptionFormat = TestExceptionFormat.FULL
        }
    }

    // Reproducible jars: same sources, same bytes.
    tasks.withType<AbstractArchiveTask>().configureEach {
        isPreserveFileTimestamps = false
        isReproducibleFileOrder = true
    }

    tasks.named<JacocoReport>("jacocoTestReport") {
        dependsOn(tasks.named("test"))
        reports { xml.required = true }
    }
}

repositories { mavenCentral() }

spotless {
    java {
        target("*/src/**/*.java")
        googleJavaFormat(libs.versions.googleJavaFormat.get())
    }
}

dependencyCheck {
    failBuildOnCVSS = 7.0f
    failOnError = true
    suppressionFile = "config/dependency-check-suppression.xml"
    nvd { apiKey = System.getenv("NVD_API_KEY") ?: "" }
}
