// Headless agent library. Must stay free of com.intellij (and of the plugin's licensing, settings,
// analytics and persistence code): the CLI, the ACP adapter and the benchmarks depend on it too.
plugins {
    id("org.jetbrains.kotlin.jvm")
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
    testImplementation("junit:junit:4.13.2")
}
