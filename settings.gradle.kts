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

include(
    "lsp-core",
    "lsp-jvm",
    "lsp-js"
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
