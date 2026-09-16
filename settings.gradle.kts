rootProject.name = "zenwave-lsp"

// Sibling checkouts are included as composite builds. The directory defaults to ../<repository>;
// override it (for example to build against a git worktree) with a Gradle property or an
// environment variable. lsp-core/build.gradle.kts reads the same dsl-kotlin setting for the
// parser sources it compiles directly.
//   -Pzenwave.local.dslKotlinDir=../dsl-kotlin-feature                         (ZENWAVE_LOCAL_DSL_KOTLIN_DIR)
//   -Pzenwave.local.jsonRefParserDir=../json-schema-ref-parser-kmp-feature   (ZENWAVE_LOCAL_JSON_REF_PARSER_DIR)
//   -Pzenwave.local.manifestDir=../zenwave-manifest-feature                  (ZENWAVE_LOCAL_MANIFEST_DIR)
// zenwave-manifest's own settings read ZENWAVE_LOCAL_DSL_KOTLIN_DIR / ZENWAVE_LOCAL_JSON_REF_PARSER_DIR
// too, so the environment variables keep an included zenwave-manifest on the same upstream checkouts.
fun localBuildDir(property: String, environmentVariable: String, default: String): File =
    file(
        providers.gradleProperty(property).orNull
            ?: providers.environmentVariable(environmentVariable).orNull
            ?: default,
    )

includeBuild(localBuildDir("zenwave.local.dslKotlinDir", "ZENWAVE_LOCAL_DSL_KOTLIN_DIR", "../dsl-kotlin")) {
    dependencySubstitution {
        substitute(module("io.zenwave360.dsl:dsl-kotlin")).using(project(":"))
    }
}

includeBuild(
    localBuildDir("zenwave.local.jsonRefParserDir", "ZENWAVE_LOCAL_JSON_REF_PARSER_DIR", "../json-schema-ref-parser-kmp"),
) {
    dependencySubstitution {
        substitute(module("io.zenwave360.jsonrefparser:json-schema-ref-parser-kmp")).using(project(":"))
    }
}

val localZenWaveManifest = localBuildDir("zenwave.local.manifestDir", "ZENWAVE_LOCAL_MANIFEST_DIR", "../zenwave-manifest")
if (localZenWaveManifest.exists()) {
    includeBuild(localZenWaveManifest) {
        dependencySubstitution {
            substitute(module("io.zenwave360.manifest:manifest-core")).using(project(":manifest-core"))
        }
    }
}

include(
    "lsp-core",
    "lsp-jvm",
    "lsp-js"
)

enableFeaturePreview("TYPESAFE_PROJECT_ACCESSORS")
