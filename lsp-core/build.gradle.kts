plugins {
    kotlin("multiplatform")
    kotlin("plugin.serialization")
    id("com.vanniktech.maven.publish")
    id("org.jetbrains.kotlinx.kover")
    id("com.goncalossilva.resources") version "0.14.0"
}

// Use the DSL Kotlin library once. Embedding its sources duplicates the classes
// pulled in transitively by workspace-runtime and breaks Kotlin/JS linking.

kotlin {
    jvmToolchain(21)

    jvm {
//        withJava()
    }

    js(IR) {
        nodejs()
        // The browser target makes browser use checkable: jsBrowserTest (part of check) loads lsp-core and
        // its dependencies in headless Chromium, so a Node-only import fails the build here, not in a consumer.
        browser {
            testTask {
                // The common test suite reads fixtures from the filesystem, so it runs on Node only.
                filter.includeTestsMatching("io.zenwave360.lsp.core.LspCoreBrowserSmokeTest")
                useKarma {
                    useChromeHeadless()
                }
            }
        }
        binaries.executable()
        // ES modules, as dsl-kotlin and lsp-js use: the elkjs binding compiled from dsl-kotlin (@JsModule without
        // @JsNonModule) cannot be compiled to UMD.
        useEsModules()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))

                implementation("io.zenwave360.dsl:dsl-kotlin:1.10.0-SNAPSHOT")
                implementation("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp:1.0.0-SNAPSHOT")
                implementation("io.zenwave360.manifest:manifest-core:1.0.0-SNAPSHOT")
                implementation("io.zenwave360.manifest:workspace-runtime:1.0.0-SNAPSHOT")
                implementation("com.strumenta:antlr-kotlin-runtime:1.0.3")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-core:1.8.1")
                implementation("org.jetbrains.kotlinx:kotlinx-serialization-json:1.7.3")

                // If you already use kotlinx.serialization in core IR
                // implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
                implementation("com.goncalossilva:resources:0.14.0")
                implementation("org.jetbrains.kotlinx:kotlinx-coroutines-test:1.8.1")
            }
        }

        val jvmMain by getting {
            dependencies {
                // Same version as dsl-kotlin's jvmMain.
                implementation("org.eclipse.elk:org.eclipse.elk.alg.layered:0.10.0")
                implementation(kotlin("stdlib-jdk8"))
            }
        }

        val jvmTest by getting {
            dependencies {
                implementation(kotlin("test-junit"))
            }
        }

        val jsMain by getting {
            dependencies {
                // Same version as dsl-kotlin's jsMain. Bundlers targeting a Web Worker must keep elkjs'
                // worker script from taking over the worker's message handler (see lsp-js/npm/build.mjs).
                implementation(npm("elkjs", "0.9.3"))
                implementation(kotlin("stdlib-js"))
                // No Node API here: lsp-core's JS artifact must load in a browser and a Web Worker.
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}

publishing {
    publications.withType<MavenPublication>().configureEach {
        pom {
            name.set(
                when (artifactId) {
                    "lsp-core" -> "ZenWave LSP Core"
                    else -> "ZenWave LSP"
                }
            )
            description.set("Shared Kotlin Multiplatform language-service core for ZenWave DSLs and related specs")
        }
    }
}
