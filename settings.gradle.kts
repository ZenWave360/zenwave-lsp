rootProject.name = "zenwave-lsp"

includeBuild("../dsl-kotlin") {
    dependencySubstitution {
        substitute(module("io.zenwave360.dsl:dsl-kotlin")).using(project(":"))
    }
}

includeBuild("../json-schema-ref-parser-kmp") {
    dependencySubstitution {
        substitute(module("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp")).using(project(":"))
    }
}

val localZenWaveManifest = file("../zenwave-manifest")
if (localZenWaveManifest.exists()) {
    includeBuild(localZenWaveManifest) {
        dependencySubstitution {
            substitute(module("io.zenwave360.manifest:manifest-core")).using(project(":manifest-core"))
            substitute(module("io.zenwave360.manifest:manifest-graph")).using(project(":manifest-graph"))
            substitute(module("io.zenwave360.manifest:workspace-runtime")).using(project(":workspace-runtime"))
        }
    }
}

include(
    "lsp-core",
    "lsp-jvm",
    "lsp-js"
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
