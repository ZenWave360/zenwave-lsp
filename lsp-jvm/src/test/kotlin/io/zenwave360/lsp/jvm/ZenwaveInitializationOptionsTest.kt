package io.zenwave360.lsp.jvm

import kotlin.test.Test
import kotlin.test.assertEquals
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
    fun returnsNullWhenZenwaveInitializationOptionsAreAbsent() {
        assertNull(parseZenwaveInitializationOptions(emptyMap<String, Any?>()))
    }
}
