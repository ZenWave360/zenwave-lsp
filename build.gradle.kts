import org.jetbrains.kotlin.gradle.tasks.KotlinCompile

plugins {
    kotlin("jvm") version "1.9.0"
    id("org.jetbrains.intellij") version "1.15.0"
    id("java")
    id("application")
}

group = "io.zenwave360.zdl"
version = "0.1.0-SNAPSHOT"

repositories {
    mavenCentral()
}

dependencies {
    implementation(kotlin("stdlib"))
    
    // LSP4J - Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j.jsonrpc:0.21.1")
    
    // ANTLR4 for parsing
    implementation("org.antlr:antlr4-runtime:4.13.1")
    
    // Logging
    implementation("org.slf4j:slf4j-api:2.0.9")
    implementation("ch.qos.logback:logback-classic:1.4.11")
    
    // ZDL Parser from Maven Central
    implementation("io.github.zenwave360.zenwave-sdk:zdl-jvm:1.2.3")
    
    // Testing
    testImplementation("org.junit.jupiter:junit-jupiter-api:5.10.0")
    testRuntimeOnly("org.junit.jupiter:junit-jupiter-engine:5.10.0")
}

application {
    mainClass.set("io.zenwave360.zdl.lsp.ZdlLanguageServerLauncherKt")
}

tasks.withType<KotlinCompile> {
    kotlinOptions.jvmTarget = "17"
}

tasks.withType<Test> {
    useJUnitPlatform()
}

// IntelliJ plugin configuration
intellij {
    version.set("2023.2")
    type.set("IC") // Community Edition
    plugins.set(listOf("com.intellij.java"))
}
