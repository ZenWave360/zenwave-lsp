plugins {
    kotlin("multiplatform")
}

kotlin {
    jvmToolchain(17)

    jvm {
        withJava()
    }

    js(IR) {
        nodejs()
        binaries.executable()

        // Generate ES modules instead of CommonJS
        useEsModules()
    }

    sourceSets {
        val commonMain by getting {
            dependencies {
                implementation(kotlin("stdlib-common"))

                // DSL Kotlin parsers for ZDL and ZFL languages
                // Parser implementation will be provided externally
                // implementation("io.zenwave360.sdk:dsl-kotlin")

                // If you already use kotlinx.serialization in core IR
                // implementation("org.jetbrains.kotlinx:kotlinx-serialization-core:1.7.3")
            }
        }

        val commonTest by getting {
            dependencies {
                implementation(kotlin("test-common"))
                implementation(kotlin("test-annotations-common"))
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

