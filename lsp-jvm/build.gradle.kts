plugins {
    kotlin("jvm")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover")
    application
}

dependencies {
    implementation(projects.lspCore)

    // LSP4J for JVM Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    implementation(kotlin("stdlib-jdk8"))
    // zenwave/eventFlowViews: lsp-core's layout is a suspend function.
    implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")

    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.zenwave360.lsp.jvm.MainKt")
}

mavenPublishing {
    pom {
        name.set("ZenWave LSP JVM")
        description.set("JVM LSP4J transport for the ZenWave shared language server core")
    }
}
