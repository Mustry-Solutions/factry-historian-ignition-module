import com.google.protobuf.gradle.*

plugins {
    `java-library`
    id("com.google.protobuf") version "0.9.4"
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

val grpcVersion = "1.62.2"
val protobufVersion = "3.25.3"

dependencies {
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    compileOnly("com.inductiveautomation.ignitionsdk:gateway-api:${rootProject.extra["sdk_version"]}")

    // Historian API dependencies
    // These are in separate artifacts because historian is now a dedicated module
    // The SDK POMs (com.inductiveautomation.ignitionsdk) reference the real artifacts (com.inductiveautomation.historian)
    // We need to add the real artifacts directly since compileOnly doesn't pull transitive dependencies
    compileOnly("com.inductiveautomation.historian:historian-gateway:1.3.3") {
        isTransitive = false
    }
    compileOnly("com.inductiveautomation.historian:historian-gateway-api:1.3.3") {
        isTransitive = false
    }
    compileOnly("com.inductiveautomation.historian:historian-common:1.3.3") {
        isTransitive = false
    }

    // JSON library for HTTP communication with proxy
    compileOnly("com.google.code.gson:gson:2.10.1")

    compileOnly(project(":common"))

    // gRPC and Protobuf dependencies (bundled in .modl via modlImplementation)
    modlImplementation("io.grpc:grpc-netty-shaded:$grpcVersion")
    modlImplementation("io.grpc:grpc-protobuf:$grpcVersion")
    modlImplementation("io.grpc:grpc-stub:$grpcVersion")
    modlImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
    modlImplementation("com.google.protobuf:protobuf-java-util:$protobufVersion")
    modlImplementation("javax.annotation:javax.annotation-api:1.3.2")

    // Test dependencies
    testImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    testRuntimeOnly("org.junit.platform:junit-platform-launcher")
    testImplementation("com.inductiveautomation.historian:historian-gateway-api:1.3.3") {
        isTransitive = false
    }
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

tasks.test {
    useJUnitPlatform()
}

// Ensure compileTestJava sees test sources (protobuf plugin can interfere)
tasks.named<JavaCompile>("compileTestJava") {
    dependsOn(tasks.named("compileJava"))
}

protobuf {
    protoc {
        artifact = "com.google.protobuf:protoc:$protobufVersion"
    }
    plugins {
        id("grpc") {
            artifact = "io.grpc:protoc-gen-grpc-java:$grpcVersion"
        }
    }
    generateProtoTasks {
        all().forEach { task ->
            task.plugins {
                id("grpc")
            }
        }
    }
}

sourceSets {
    main {
        proto {
            srcDir("${rootProject.projectDir}/proto")
        }
    }
}
