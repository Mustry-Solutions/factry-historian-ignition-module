plugins {
    `java-library`
}

java {
    toolchain {
        languageVersion.set(org.gradle.jvm.toolchain.JavaLanguageVersion.of(17))
    }
}

dependencies {
    // add common scoped dependencies here
    compileOnly("com.inductiveautomation.ignitionsdk:ignition-common:${rootProject.extra["sdk_version"]}")
}

tasks.named<ProcessResources>("processResources") {
    val moduleVersion = rootProject.extensions
        .getByType<io.ia.sdk.gradle.modl.extension.ModuleSettings>()
        .moduleVersion
    inputs.property("moduleVersion", moduleVersion)
    filesMatching("version.properties") {
        expand("moduleVersion" to moduleVersion.get())
    }
}
