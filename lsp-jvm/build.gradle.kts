plugins {
    kotlin("jvm")
    application
}

dependencies {
    implementation(projects.lspCore)

    // LSP4J for JVM Language Server Protocol implementation
    implementation("org.eclipse.lsp4j:org.eclipse.lsp4j:0.21.1")

    implementation(kotlin("stdlib-jdk8"))

    testImplementation(kotlin("test-junit"))
}

kotlin {
    jvmToolchain(21)
}

application {
    mainClass.set("io.zenwave360.lsp.jvm.MainKt")
}
