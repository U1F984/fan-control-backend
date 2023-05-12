plugins {
    kotlin("jvm") version "1.8.21"
    kotlin("plugin.serialization") version "1.8.21"
    application
    id("com.github.johnrengelman.shadow") version "7.1.2"
}

group = "cc.tietz"
version = "1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation("io.ktor:ktor-server-core:2.3.0")
    implementation("io.ktor:ktor-server-netty:2.3.0")
    implementation("io.ktor:ktor-server-content-negotiation:2.3.0")
    implementation("io.ktor:ktor-serialization-kotlinx-json:2.3.0")

    implementation("ch.qos.logback:logback-classic:1.4.6")

    implementation("org.jooq:jooq:3.18.0")
    implementation("org.xerial:sqlite-jdbc:3.40.1.0")

    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.7.0")

    testImplementation(kotlin("test"))
}

tasks.test {
    useJUnitPlatform()
}

kotlin {
    jvmToolchain(11)
}

application {
    mainClass.set("cc.tietz.fancontrolbackend.MainKt")
}