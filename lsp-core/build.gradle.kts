plugins {
    kotlin("multiplatform")
    // dsl-kotlin's EventFlow view models, compiled here from source, are @Serializable.
    kotlin("plugin.serialization")
    `maven-publish`
    id("com.goncalossilva.resources") version "0.14.0"
}

// dsl-kotlin parser sources are compiled directly from the sibling checkout. Its directory defaults to
// ../dsl-kotlin and follows the same override as settings.gradle.kts
// (-Pzenwave.local.dslKotlinDir or ZENWAVE_LOCAL_DSL_KOTLIN_DIR, relative to the root project).
val dslKotlinDir = rootProject.projectDir.resolve(
    providers.gradleProperty("zenwave.local.dslKotlinDir").orNull
        ?: providers.environmentVariable("ZENWAVE_LOCAL_DSL_KOTLIN_DIR").orNull
        ?: "../dsl-kotlin"
)
val dslKotlinGeneratedSrc = dslKotlinDir.resolve("build/generated/antlr/commonMain/kotlin")
val dslKotlinSharedSrcRoots = listOf(
    // EventFlow view models and their generators (zenwave/eventFlowViews, zenwave/preview).
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/eventflow"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/formatter/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/source"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/utils"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl"),
    // GenerateMermaidFromZdl (zenwave/preview for ZDL) and its class diagram transformer.
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/application"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/view"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/semantic"),
)
// The platform actuals of the EventFlow layout engine: ELK on the JVM, elkjs on JavaScript.
val dslKotlinJvmSrcRoots = listOf(
    dslKotlinDir.resolve("src/jvmMain/kotlin/io/zenwave360/language/eventflow"),
)
val dslKotlinJsSrcRoots = listOf(
    dslKotlinDir.resolve("src/jsMain/kotlin/io/zenwave360/language/eventflow"),
)

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
            dslKotlinSharedSrcRoots.forEach(kotlin::srcDir)
            kotlin.srcDir(dslKotlinGeneratedSrc)
            dependencies {
                implementation(kotlin("stdlib-common"))

                // DSL Kotlin parser sources are compiled directly via srcDir above.
                implementation("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp:0.1.0-SNAPSHOT")
                implementation("io.zenwave360.manifest:manifest-core:0.1.0-SNAPSHOT")
                implementation("io.zenwave360.manifest:workspace-runtime:0.1.0-SNAPSHOT")
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
            dslKotlinJvmSrcRoots.forEach(kotlin::srcDir)
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
            dslKotlinJsSrcRoots.forEach(kotlin::srcDir)
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
