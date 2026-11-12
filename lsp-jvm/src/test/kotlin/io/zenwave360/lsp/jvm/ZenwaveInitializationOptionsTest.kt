package io.zenwave360.lsp.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNotNull
import kotlin.test.assertNull

class ZenwaveInitializationOptionsTest {

    @Test
    fun parsesZenwaveInitializationOptionsFromNestedMap() {
        val options = parseZenwaveInitializationOptions(
            mapOf(
                "zenwave" to mapOf(
                    "configUri" to "file:///workspace/.zenwave/config.yml",
                    "projectManifestUri" to "git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml",
                )
            )
        )

        assertEquals("file:///workspace/.zenwave/config.yml", options?.configUri)
        assertEquals(
            "git://github.com/acme/my-docs?ref=main#zenwave-architecture.yml",
            options?.projectManifestUri
        )
    }

    @Test
    fun parsesZenwaveInitializationOptionsFromTheJsonLsp4jReadsOffTheWire() {
        val options = parseZenwaveInitializationOptions(
            com.google.gson.JsonParser.parseString(
                """{"zenwave":{"configUri":"file:///workspace/.zenwave/config.yml","projectManifestUri":"file:///workspace/zenwave-architecture.yml"}}"""
            )
        )

        assertEquals("file:///workspace/.zenwave/config.yml", options?.configUri)
        assertEquals("file:///workspace/zenwave-architecture.yml", options?.projectManifestUri)
    }

    @Test
    fun returnsNullWhenZenwaveInitializationOptionsAreAbsent() {
        assertNull(parseZenwaveInitializationOptions(emptyMap<String, Any?>()))
    }

    @Test
    fun parsesRemoteManifestNameAndAllowedDomains() {
        val options = parseZenwaveInitializationOptions(
            com.google.gson.JsonParser.parseString(
                """{"zenwave":{"remoteManifestName":"architecture.yml","allowedDomains":["github.com","*.githubusercontent.com"]}}"""
            )
        )

        assertEquals("architecture.yml", options?.remoteManifestName)
        assertEquals(listOf("github.com", "*.githubusercontent.com"), options?.allowedDomains)
        assertNull(options?.remoteManifestNameWarning)

        val invalid = parseZenwaveInitializationOptions(
            mapOf(
                "zenwave" to mapOf(
                    "remoteManifestName" to "../secret.yml",
                    "allowedDomains" to listOf("GitHub.com"),
                )
            )
        )
        assertEquals(".zenwave-manifest.yml", invalid?.remoteManifestName)
        assertEquals(listOf("GitHub.com"), invalid?.allowedDomains)
        assertNotNull(invalid?.remoteManifestNameWarning)
    }
}
