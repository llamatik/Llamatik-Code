// Llamatik as an Agent Client Protocol (ACP) agent: any ACP editor (Zed, JetBrains, Neovim, ...) can
// launch it over stdio and use the same AgentLoop as the IDE plugin and the CLI. No com.intellij.
plugins {
    id("org.jetbrains.kotlin.jvm")
    application
}

group = "com.llamatik.agent"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    implementation(project(":agent-core"))
    implementation(project(":backends"))
    // Pre-1.0 and released often: pinned, and only this module depends on it.
    implementation("com.agentclientprotocol:acp:0.34.0")
    runtimeOnly("com.google.code.gson:gson:2.11.0")
    // The SDK logs through kotlin-logging/SLF4J and ships no backend. slf4j-simple writes to stderr, which
    // matters: stdout carries the protocol.
    runtimeOnly("org.slf4j:slf4j-simple:2.0.16")

    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-core")))
    testImplementation(testFixtures(project(":backends")))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

application {
    mainClass.set("com.llamatik.acp.MainKt")
    applicationName = "llamatik-acp"
}

tasks.test {
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
