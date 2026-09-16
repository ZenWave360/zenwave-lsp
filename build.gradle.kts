plugins {
    kotlin("multiplatform") version "2.3.0" apply false
    kotlin("jvm") version "2.3.0" apply false
    kotlin("js") version "2.3.0" apply false
}

allprojects {
    group = "io.zenwave360.language"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
        mavenLocal()
    }
}

// Karma launches Chrome through CHROME_BIN. When it is not set, fall back to a locally
// installed Chromium-based browser (Chrome, Chromium or Microsoft Edge).
subprojects {
    tasks.withType<org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest>()
        .matching { it.name == "jsBrowserTest" }
        .configureEach {
            if (System.getenv("CHROME_BIN").isNullOrBlank()) {
                val candidates = listOf(
                    "C:/Program Files/Google/Chrome/Application/chrome.exe",
                    "C:/Program Files (x86)/Google/Chrome/Application/chrome.exe",
                    "C:/Program Files (x86)/Microsoft/Edge/Application/msedge.exe",
                    "C:/Program Files/Microsoft/Edge/Application/msedge.exe",
                    "/Applications/Google Chrome.app/Contents/MacOS/Google Chrome",
                    "/Applications/Microsoft Edge.app/Contents/MacOS/Microsoft Edge",
                    "/usr/bin/google-chrome",
                    "/usr/bin/chromium",
                    "/usr/bin/chromium-browser",
                    "/usr/bin/microsoft-edge",
                )
                candidates.firstOrNull { file(it).exists() }?.let { environment("CHROME_BIN", it) }
            }
        }
}
