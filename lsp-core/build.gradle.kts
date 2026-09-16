plugins {
    kotlin("multiplatform")
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
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/formatter/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/source"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/utils"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zdl/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/formatter"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/internal"),
    dslKotlinDir.resolve("src/commonMain/kotlin/io/zenwave360/language/zfl/semantic"),
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
