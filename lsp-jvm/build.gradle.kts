plugins {
    kotlin("jvm")
    `maven-publish`
    application
}

dependencies {
    implementation(projects.lspCore)

    // LSP4J for JVM Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    implementation(kotlin("stdlib-jdk8"))
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
}

java {
    withSourcesJar()
}

application {
    mainClass.set("io.zenwave360.lsp.jvm.MainKt")
}

publishing {
    publications {
        create<MavenPublication>("mavenJvm") {
            from(components["java"])
            artifactId = "lsp-jvm"
            pom {
                name.set("ZenWave LSP JVM")
                description.set("JVM LSP4J transport for the ZenWave shared language server core")
            }
        }
    }
}
