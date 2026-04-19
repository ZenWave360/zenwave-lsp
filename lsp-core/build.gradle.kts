plugins {
    kotlin("multiplatform")
    id("com.goncalossilva.resources") version "0.14.0"
}

val dslKotlinGeneratedSrc = rootProject.projectDir.resolve("../dsl-kotlin/build/generated/antlr/commonMain/kotlin")
val dslKotlinSharedSrcRoots = listOf(
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/source"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/utils"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/zdl"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/zdl/internal"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/zfl"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/zfl/internal"),
    rootProject.projectDir.resolve("../dsl-kotlin/src/commonMain/kotlin/io/zenwave360/language/zfl/semantic"),
)

kotlin {
    jvmToolchain(21)

    jvm {
//        withJava()
    }

    js(IR) {
        nodejs()
        binaries.executable()
    }

    sourceSets {
        val commonMain by getting {
            dslKotlinSharedSrcRoots.forEach(kotlin::srcDir)
            kotlin.srcDir(dslKotlinGeneratedSrc)
            dependencies {
                implementation(kotlin("stdlib-common"))

                // DSL Kotlin parsers for ZDL and ZFL languages
                implementation("io.zenwave360.dsl:dsl-kotlin:1.5.0-SNAPSHOT")
                implementation("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp:0.1.0-SNAPSHOT")
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
            }
        }

        val jsTest by getting {
            dependencies {
                implementation(kotlin("test-js"))
            }
        }
    }
}
