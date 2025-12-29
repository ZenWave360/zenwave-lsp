plugins {
    kotlin("js")
}

kotlin {
    js(IR) {
        nodejs()
        binaries.executable()

        // Generate ES modules instead of CommonJS
        useEsModules()

        // Generate npm package
        compilations["main"].packageJson {
            customField("name", "@zenwave360/lsp-js")
            customField("version", project.version.toString())
            customField("description", "ZenWave Domain Model Language Server")
            customField("keywords", listOf("zdl", "domain-driven-design", "event-storming"))
            customField("homepage", "https://github.com/ZenWave360/zenwave-lsp")
            customField("repository", mapOf(
                "type" to "git",
                "url" to "https://github.com/ZenWave360/zenwave-lsp"
            ))
            customField("license", "MIT")
            customField("main", "lsp-js.js")
            customField("types", "lsp-js.d.ts")
        }
    }
}

dependencies {
    implementation(projects.lspCore)

    implementation(kotlin("stdlib-js"))

    // VSCode Language Server Protocol for Node.js
    implementation(npm("vscode-languageserver", "8.1.0"))

    testImplementation(kotlin("test-js"))
}

