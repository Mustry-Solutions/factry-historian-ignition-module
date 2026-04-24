import com.google.protobuf.gradle.*
import org.gradle.api.tasks.testing.logging.TestLogEvent
import org.gradle.kotlin.dsl.KotlinClosure2

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
    testImplementation("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
    testImplementation("com.inductiveautomation.historian:historian-gateway-api:1.3.3") {
        isTransitive = false
    }
    testImplementation("com.inductiveautomation.historian:historian-common:1.3.3") {
        isTransitive = false
    }
    testImplementation("com.google.protobuf:protobuf-java:$protobufVersion")
    testRuntimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

// ---------------------------------------------------------------------------
// Integration test source set
// ---------------------------------------------------------------------------
sourceSets {
    create("integrationTest") {
        java.srcDir("src/integrationTest/java")
        // Reuse main's compiled classes (including generated proto stubs) and resources
        compileClasspath += sourceSets.main.get().output + configurations["modlImplementation"]
        runtimeClasspath += sourceSets.main.get().output + configurations["modlImplementation"]
    }
}

val integrationTestImplementation by configurations.getting {
    extendsFrom(configurations["implementation"], configurations["modlImplementation"])
}
val integrationTestRuntimeOnly by configurations.getting {
    extendsFrom(configurations["runtimeOnly"])
}

dependencies {
    integrationTestImplementation("org.junit.jupiter:junit-jupiter:5.10.2")
    integrationTestImplementation("com.google.code.gson:gson:2.10.1")
    integrationTestRuntimeOnly("org.junit.platform:junit-platform-launcher")
    integrationTestRuntimeOnly("org.slf4j:slf4j-simple:2.0.12")
}

tasks.register<Test>("integrationTest") {
    group = "verification"
    description = "Run integration tests against running Ignition + Factry"
    testClassesDirs = sourceSets["integrationTest"].output.classesDirs
    classpath = sourceSets["integrationTest"].runtimeClasspath
    useJUnitPlatform()

    // Always re-run — integration tests depend on external services, not just code
    outputs.upToDateWhen { false }

    // Pass system properties from Gradle command line (-P) or env vars
    systemProperty("gateway.url", System.getenv("GATEWAY_URL") ?: "http://localhost:8089")
    systemProperty("webdev.project", System.getenv("WEBDEV_PROJECT") ?: "TestFactry")
    systemProperty("historian.name", System.getenv("HISTORIAN_NAME") ?: "Factry Historian 0.8")
    systemProperty("grpc.host", System.getenv("GRPC_HOST") ?: "localhost")
    systemProperty("grpc.port", System.getenv("GRPC_PORT") ?: "8001")
    systemProperty("collector.uuid", System.getenv("COLLECTOR_UUID") ?: "a16cac76-3272-11f1-b9ed-4a5934d93d4f")
    systemProperty("collector.token", System.getenv("COLLECTOR_TOKEN") ?: "eyJhbGciOiJIUzI1NiIsInR5cCI6IkpXVCJ9.eyJhdWQiOiJodHRwOi8vaGlzdG9yaWFuIiwiZXhwIjoyNTY0MDM2OTAzLCJncnBjLXBvcnQiOiI4MDAxIiwiaWF0IjoxNzc1NjM2OTAzLCJpc3MiOiJmYWN0cnkuaW8iLCJqdGkiOiJjb2xsZWN0b3ItYTE2Y2FjNzYtMzI3Mi0xMWYxLWI5ZWQtNGE1OTM0ZDkzZDRmIiwibmFtZSI6IkluZ2l0aW9uIiwib3JnYW5pemF0aW9uLXV1aWQiOiIxOTMwOTkyMC0zMjY5LTExZjEtYjkwYi04ZTMzMzdkZGM3MjgiLCJyZXN0LXBvcnQiOiI4MDAwIiwidXNlciI6eyJuYW1lIjoiY29sbGVjdG9yLWExNmNhYzc2LTMyNzItMTFmMS1iOWVkLTRhNTkzNGQ5M2Q0ZiIsInV1aWQiOiJhMTZjYWM3Ni0zMjcyLTExZjEtYjllZC00YTU5MzRkOTNkNGYifSwidXVpZCI6ImExNmNhYzc2LTMyNzItMTFmMS1iOWVkLTRhNTkzNGQ5M2Q0ZiJ9.8w_KLgd458TJsnhk8VkKIf1vOc8FUqOWhu5zAUdTHig")
    systemProperty("gateway.system.name", System.getenv("GATEWAY_SYSTEM_NAME") ?: "Ignition-296a8ca4b6cd")
    systemProperty("historian.name.nosf", System.getenv("HISTORIAN_NAME_NOSF") ?: "")
    systemProperty("historian.name.sf", System.getenv("HISTORIAN_NAME_SF") ?: "")

    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = true
    }
}

// Ensure integration test compilation sees main sources
tasks.named<JavaCompile>("compileIntegrationTestJava") {
    dependsOn(tasks.named("compileJava"))
}

tasks.test {
    useJUnitPlatform()
    testLogging {
        events("passed", "skipped", "failed")
        showStandardStreams = false
        afterSuite(KotlinClosure2<TestDescriptor, TestResult, Unit>({ desc, result ->
            if (desc.parent == null) {
                println("\nTest results: ${result.resultType} " +
                    "(${result.testCount} tests, ${result.successfulTestCount} passed, " +
                    "${result.failedTestCount} failed, ${result.skippedTestCount} skipped)")
            }
        }))
    }
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
