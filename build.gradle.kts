plugins {
    kotlin("multiplatform") version "2.0.21" apply false
    kotlin("jvm") version "2.0.21" apply false
    kotlin("js") version "2.0.21" apply false
}

allprojects {
    group = "io.zenwave360.language"
    version = "0.1.0-SNAPSHOT"
}

subprojects {
    repositories {
        mavenCentral()
    }
}
