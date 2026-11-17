// Headless inference backends and the Llamatik server client. No com.intellij: shared by the IDE
// plugin, the CLI and the ACP agent.
plugins {
    id("org.jetbrains.kotlin.jvm")
    `java-library`
    `java-test-fixtures`
}

group = "com.llamatik.agent"

kotlin {
    jvmToolchain(21)
}

repositories {
    mavenCentral()
}

dependencies {
    api(project(":agent-core"))
    // Native llama.cpp bridge (local inference).
    api("com.llamatik:core:2.4.0")
    // The Llamatik server client (resumable streaming, pairing, status). mDNS discovery is not used here.
    api("com.llamatik:network:2.4.0") { exclude(group = "org.jmdns") }
    // The HTTP stack is chosen explicitly (JDK engine) instead of by service discovery: inside the IDE the
    // platform ships its own ktor engines.
    implementation("io.ktor:ktor-client-core:3.6.1")
    implementation("io.ktor:ktor-client-java:3.6.1")
    implementation("io.ktor:ktor-client-content-negotiation:3.6.1")
    implementation("io.ktor:ktor-serialization-kotlinx-json:3.6.1")
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.11.0")
    implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.11.0")

    testFixturesApi(project(":agent-core"))
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}

tasks.test {
    // Real-model tests load the native library.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
