package io.zenwave360.lsp.core.architecture

import io.zenwave360.architecture.graph.ArchitectureGraphBuilder
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.architecture.workspace.WorkspaceRuntime
import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue

/** AR-R10 and AR-Q4: opening the workspace reads the manifest and what it composes, and nothing else. */
class OpeningLoaderTest {
    private val manifestUri = "https://allowed.example/manifest.yml"
    private val manifestText = """
        domains:
          sales:
            services:
              orders:
                artifacts:
                  - { type: openapi, path: "https://not-allowed.example/private.yml", version: 1.0.0 }
                  - { type: asyncapi, path: "asyncapi.yml", version: 1.0.0 }
    """.trimIndent()

    private class Recording(private val documents: Map<String, String>) : DocumentLoader {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("not found: $uri")
        }
    }

    private fun runtime(manifest: String, io: DocumentLoader): WorkspaceRuntime {
        fun loader(follow: Boolean) =
            ZenWaveManifestLoader(listOf(OpeningDocumentGuard(manifest, listOf(io), follow)), null)
        return WorkspaceRuntime(loader(true), ArchitectureGraphBuilder(loader(false)))
    }

    @Test
    fun openingReadsTheManifestAndNoOtherRemoteAddress() = runTest {
        val io = Recording(mapOf(manifestUri to manifestText))

        runtime(manifestUri, io).open(manifestUri)

        assertEquals(listOf(manifestUri), io.loaded)
    }

    @Test
    fun aRemoteManifestStillComposesWithARefOnItsOwnOrigin() = runTest {
        val composed = "domains:\n  sales:\n    \$ref: https://allowed.example/sales.yml\n"
        val io = Recording(
            mapOf(
                manifestUri to composed,
                "https://allowed.example/sales.yml" to "services:\n  orders:\n    artifacts:\n      - { type: openapi, path: \"https://not-allowed.example/p.yml\", version: 1.0.0 }\n",
            ),
        )

        runtime(manifestUri, io).open(manifestUri)

        assertEquals(listOf(manifestUri, "https://allowed.example/sales.yml"), io.loaded)
    }

    @Test
    fun aRefToAnotherOriginIsNotFollowed() = runTest {
        val io = Recording(mapOf(manifestUri to "domains:\n  sales:\n    \$ref: https://evil.example/sales.yml\n"))
        runCatching { runtime(manifestUri, io).open(manifestUri) }
        assertEquals(listOf(manifestUri), io.loaded)
    }

    private val localManifest = "file:///work/architecture/zenwave-architecture.yml"

    private fun guard(manifest: String, follow: Boolean = false) = OpeningDocumentGuard(manifest, emptyList(), follow)

    @Test
    fun aLocalManifestReadsLocalFilesButNeverUncHostsOrTricks() {
        val guard = guard(localManifest)
        assertTrue(guard.isReadable(localManifest))
        assertTrue(guard.isReadable("file:///work/architecture/orders/api.yml"))
        // sibling checkouts of the user's own disk feed the graph
        assertTrue(guard.isReadable("file:///work/orders-service/openapi.yml"))
        assertTrue(guard.isReadable("file:///C:/checkouts/orders/openapi.yml"))
        assertTrue(guard.isReadable("C:/checkouts/orders/openapi.yml"))
        listOf(
            "file:////host/share/api.yml",
            "file://host/share/api.yml",
            "file:///work/architecture/../other/api.yml",
            "file:///work/architecture/%2e%2e/other/api.yml",
            "file:///work/architecture/..%2Fother/api.yml",
            "file:///work/architecture/a\\..\\..\\x.yml",
            "file:///work/architecture/api.yml?x",
            "//host/share/api.yml",
            "\\\\host\\share\\api.yml",
            "orders/api.yml",
            "https://not-allowed.example/private.yml",
            "git://github.com/acme/x",
        ).forEach { assertFalse(guard.isReadable(it), it) }
    }

    @Test
    fun aWindowsManifestReadsSiblingDrivePathsButNotUnc() {
        val guard = guard("file:///C:/work/architecture/zenwave-architecture.yml")
        assertTrue(guard.isReadable("file:///c:/work/orders-service/api.yml"))
        assertTrue(guard.isReadable("file:///D:/work/api.yml"))
        assertFalse(guard.isReadable("\\\\host\\share\\api.yml"))
        assertFalse(guard.isReadable("file:////host/share/api.yml"))
    }

    @Test
    fun aRemoteManifestReadsNoLocalFileAndNoOtherOrigin() {
        val guard = guard(manifestUri, follow = true)
        assertTrue(guard.isReadable("https://allowed.example/sales.yml"))
        listOf(
            "file:///work/architecture/api.yml",
            "https://allowed.example.evil.example/x.yml",
            "https://allowed.example@evil.example/x.yml",
            "http://allowed.example/x.yml",
            "https://allowed.example:8443/x.yml",
            "C:/private/api.yml",
        ).forEach { assertFalse(guard.isReadable(it), it) }
        assertFalse(guard(manifestUri, follow = false).isReadable("https://allowed.example/sales.yml"))
    }

    @Test
    fun originsAreComparedByNormalisedSchemeHostAndEffectivePort() {
        val guard = guard(manifestUri, follow = true)
        listOf(
            "https://allowed.example:443/sales.yml",
            "HTTPS://Allowed.Example/sales.yml",
            "https://allowed.example:0443/sales.yml",
        ).forEach { assertTrue(guard.isReadable(it), it) }
        listOf(
            "https://allowed.example:444/sales.yml",
            "http://allowed.example:80/sales.yml",
            "https://allowed.example:/x@evil.example",
            "https://allowed.example.evil.example:443/x.yml",
        ).forEach { assertFalse(guard.isReadable(it), it) }
        val port = guard("http://allowed.example:80/m.yml", follow = true)
        assertTrue(port.isReadable("http://allowed.example/sales.yml"))
        assertFalse(port.isReadable("https://allowed.example/sales.yml"))
    }

    @Test
    fun refusedAddressesNeverReachTheDelegate() = runTest {
        val io = Recording(emptyMap())
        val guard = OpeningDocumentGuard(localManifest, listOf(io))
        assertFailsWith<IllegalStateException> { guard.load("file:////host/share/api.yml") }
        assertFailsWith<IllegalStateException> { guard.load("https://not-allowed.example/private.yml") }
        assertTrue(io.loaded.isEmpty())
    }
}
