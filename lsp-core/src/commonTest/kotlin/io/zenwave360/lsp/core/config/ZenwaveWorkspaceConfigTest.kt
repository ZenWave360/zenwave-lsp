package io.zenwave360.lsp.core.config

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class ZenwaveWorkspaceConfigTest {

    @Test
    fun parsesWorkspaceConfigAndResolvesManifestRelativeToConfigFile() {
        val configUri = "file:///workspace/.zenwave/config.yml"
        val configText = """
            project-manifest: my-docs/master.yml
            properties:
              localRepos: file:///workspace
              templates: classpath:/templates
        """.trimIndent()

        val config = ZenwaveWorkspaceConfig.parse(configText, configUri)

        assertEquals("my-docs/master.yml", config.projectManifest)
        assertEquals("file:///workspace/my-docs/master.yml", config.projectManifestUri)
        assertEquals("file:///workspace", config.properties["localRepos"])
        assertEquals("classpath:/templates", config.properties["templates"])
    }

    @Test
    fun keepsRemoteManifestUriAsIs() {
        val configUri = "file:///workspace/.zenwave/config.yml"
        val configText = """
            project-manifest: git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml
        """.trimIndent()

        val config = ZenwaveWorkspaceConfig.parse(configText, configUri)

        assertEquals(
            "git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml",
            config.projectManifestUri
        )
    }

    @Test
    fun strictInterpolatorReportsMissingVariables() {
        val result = StrictVariableInterpolator.interpolate(
            value = "{{localRepos}}/orders-api/{{missing}}",
            properties = mapOf("localRepos" to "file:///workspace")
        )

        assertEquals("file:///workspace/orders-api/{{missing}}", result.value)
        assertEquals(listOf("missing"), result.unresolvedVariables)
    }

    @Test
    fun appendPathJoinsSchemeAwareBases() {
        assertEquals(
            "file:///workspace/orders-api",
            ResourceReferenceResolver.appendPath("file:///workspace", "orders-api")
        )
        assertEquals(
            "classpath:/templates/asyncapi.yml",
            ResourceReferenceResolver.appendPath("classpath:/templates", "asyncapi.yml")
        )
        assertEquals(
            "zenwave://maven/io.zenwave360/sdk/2.6.0/templates/asyncapi.hbs",
            ResourceReferenceResolver.appendPath(
                "zenwave://maven/io.zenwave360/sdk/2.6.0/templates",
                "asyncapi.hbs"
            )
        )
    }

    @Test
    fun resolveReferenceUsesParentOfConfigFileForRelativeManifest() {
        val resolved = ResourceReferenceResolver.resolveReference(
            baseUri = "file:///workspace/.zenwave/config.yml",
            reference = "../my-docs/master.yml"
        )

        assertEquals("file:///workspace/my-docs/master.yml", resolved)
    }

    @Test
    fun workspaceConfigRequiresProjectManifest() {
        val failure = runCatching {
            ZenwaveWorkspaceConfig.parse("properties:\n  localRepos: file:///workspace", "file:///workspace/.zenwave/config.yml")
        }

        assertTrue(failure.isFailure)
    }
}
