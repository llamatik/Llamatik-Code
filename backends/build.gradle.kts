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
    api("com.llamatik:core:2.3.0")
    // The IDE platform ships Gson, so it must not be bundled into the plugin: compile-only here, and
    // added explicitly by the command-line applications.
    compileOnly("com.google.code.gson:gson:2.11.0")

    testFixturesApi(project(":agent-core"))
    testFixturesCompileOnly("com.google.code.gson:gson:2.11.0")
    testImplementation("junit:junit:4.13.2")
    testImplementation("com.google.code.gson:gson:2.11.0")
}
