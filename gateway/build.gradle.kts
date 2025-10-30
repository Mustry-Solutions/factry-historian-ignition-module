plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")

    // Historian API dependencies (as per Paul Griffith's guidance)
    // These are in separate artifacts because historian is now a dedicated module
    // The SDK POMs (com.inductiveautomation.ignitionsdk) reference the real artifacts (com.inductiveautomation.historian)
    // We need to add the real artifacts directly since compileOnly doesn't pull transitive dependencies
    compileOnly("com.inductiveautomation.historian:historian-gateway:1.3.1")
    compileOnly("com.inductiveautomation.historian:historian-common:1.3.1")

    // JSON library for HTTP communication with proxy
    compileOnly("com.google.code.gson:gson:2.10.1")

    compileOnly(project(":common"))
    // add gateway scoped dependencies here
}
