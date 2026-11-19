// Terminal front-end of the Llamatik agent ("Claude Code for Llamatik"). Runs the same AgentLoop as
// the IDE plugin on a local model or a Llamatik server. No com.intellij.
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

    // JmDNS (server discovery) logs through SLF4J; silence the "no provider" notice.
    runtimeOnly("com.google.code.gson:gson:2.11.0")
    runtimeOnly("org.slf4j:slf4j-nop:2.0.16")

    testImplementation("junit:junit:4.13.2")
    testImplementation(testFixtures(project(":agent-core")))
    testImplementation(testFixtures(project(":backends")))
    testImplementation("com.google.code.gson:gson:2.11.0")
}

application {
    mainClass.set("com.llamatik.cli.MainKt")
    applicationName = "llamatik"
}

tasks.test {
    // The native library is only needed for --model runs; tests never load a model.
    jvmArgs("--enable-native-access=ALL-UNNAMED")
}
