package io.zenwave360.lsp.core.resource

import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.jsonrefparser.io.DocumentLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

/** spec:flows/architecture-resources#ar-q1 and #ar-q4; backlog AN/4.5. No network: an in-memory loader. */
class ZenwaveResourceSettingsTest {
    private class Recording(private val documents: MutableMap<String, String> = mutableMapOf()) : DocumentLoader, AbsoluteHttpFetch {
        val loaded = mutableListOf<String>()
        override fun canLoad(uri: String) = true
        override suspend fun load(uri: String): String {
            loaded += uri
            return documents[uri] ?: error("HTTP 404 for $uri")
        }

        override suspend fun exchange(url: String): AbsoluteHttpExchange {
            loaded += url
            val body = documents[url]
            return if (body != null) AbsoluteHttpExchange(200, null, body) else AbsoluteHttpExchange(404, null, "")
        }

        fun serve(uri: String, text: String) {
            documents[uri] = text
        }
    }

    private val remoteManifestUri = "https://git.example.org/arcadia/raw/.zenwave-manifest.yml"
    private val raw = "https://raw.githubusercontent.com/acme/README.md"
    private val githubApi = "https://github.com/acme/api.yml"
    private val githubPage = "https://github.com/acme/ROOT.md"
    private val apiHost = "https://api.github.com/repos/acme/x.yml"
    private val nested = "https://a.b.github.com/acme/n.yml"
    private val evil = "https://github.com.evil.net/acme/e.yml"
    private val notGithub = "https://notgithub.com/acme/z.yml"
    private val cased = "https://GitHub.com/acme/CASE.md"

    private val manifestText = """
        config:
          contentResolution: [git]
          sources:
            git:
              provider: generic
              server: https://git.example.org
              contentUrlExpression: "${'$'}{server}/${'$'}{owner.id}/raw/${'$'}{content.path}"
        domains:
          sales:
            services:
              orders:
                id: sales.orders
                docs:
                  readme: "$raw"
                  page: "$githubPage"
                  api: "$apiHost"
                  nested: "$nested"
                  evil: "$evil"
                  other: "$notGithub"
                  cased: "$cased"
                artifacts:
                  - { type: openapi, path: "$githubApi", version: 1.0.0 }
    """.trimIndent()

    private suspend fun service(
        io: Recording,
        domains: List<String> = emptyList(),
        name: String = DEFAULT_REMOTE_MANIFEST_NAME,
        text: String = manifestText,
        http: AbsoluteHttpFetch = io,
    ): ZenwaveResourceService {
        val loader = ZenWaveManifestLoader(listOf(io), null)
        return ZenwaveResourceService(
            remoteManifestUri,
            loader.parse(remoteManifestUri, text),
            loader,
            settings = ZenwaveResourceSettings(remoteManifestName = name, allowedDomains = domains),
            absoluteHttp = http,
        )
    }

    /** One absolute document per name. Values are single-quoted so a backslash stays a backslash. */
    private fun manifestDeclaring(vararg docs: Pair<String, String>): String {
        val body = docs.joinToString("\n") { (key, uri) -> "          $key: '$uri'" }
        return """
            |config:
            |  contentResolution: [git]
            |  sources:
            |    git:
            |      provider: generic
            |      server: https://git.example.org
            |      contentUrlExpression: "${'$'}{server}/${'$'}{owner.id}/raw/${'$'}{content.path}"
            |domains:
            |  sales:
            |    services:
            |      orders:
            |        id: sales.orders
            |        docs:
            |$body
        """.trimMargin()
    }

    /** Redirect script. A URL with no scripted answer is recorded and answered 200, so a forbidden hop shows up in [calls]. */
    private class Scripted(private val hops: Map<String, AbsoluteHttpExchange>) : AbsoluteHttpFetch {
        val calls = mutableListOf<String>()
        override suspend fun exchange(url: String): AbsoluteHttpExchange {
            calls += url
            if (calls.size > 8) error("too many fetches: $calls")
            return hops[url] ?: AbsoluteHttpExchange(200, null, "UNEXPECTED")
        }
    }

    private suspend fun failure(block: suspend () -> Unit): ResourceRequestException =
        assertFailsWith<ResourceRequestException> { block() }

    @Test
    fun theRemoteManifestNameDefaultsAndACustomNameChangesTheAddress() = runTest {
        val absent = resolveRemoteManifestName(null)
        assertEquals(DEFAULT_REMOTE_MANIFEST_NAME, absent.name)
        assertNull(absent.warning)
        assertEquals(ZENWAVE_REMOTE_MANIFEST_ADDRESS, ZenwaveResources.remoteManifestAddress())
        assertEquals(".custom.yml", resolveRemoteManifestName(".custom.yml").name)
        assertNull(resolveRemoteManifestName(".custom.yml").warning)

        val io = Recording().apply { serve(remoteManifestUri, manifestText) }
        val named = service(io, name = "architecture.yml")
        assertEquals(manifestText, named.read("zenwave:/architecture.yml").content)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { named.read(ZENWAVE_REMOTE_MANIFEST_ADDRESS) }.kind)
        assertEquals(listOf(remoteManifestUri), io.loaded)
    }

    @Test
    fun anInvalidRemoteManifestNameFallsBackToTheDefaultAndWarns() = runTest {
        val rejected = listOf(
            "", " ", "a/b.yml", "a\\b.yml", "..", "../secret.yml", "foo/../bar.yml", "a%2e.yml", "x..y.yml",
            ".", "a#fragment", "a?query", "a\nb.yml", "a\rb.yml", "a\tb.yml", "a\u0000b.yml", "a\u007Fb.yml",
            "a".repeat(MAX_REMOTE_MANIFEST_NAME_LENGTH + 1),
        )
        rejected.forEach { rawName ->
            val resolved = resolveRemoteManifestName(rawName)
            assertEquals(DEFAULT_REMOTE_MANIFEST_NAME, resolved.name, rawName)
            val warning = assertNotNull(resolved.warning, rawName)
            assertTrue(rawName in warning && DEFAULT_REMOTE_MANIFEST_NAME in warning, warning)
        }

        val io = Recording().apply { serve(remoteManifestUri, manifestText) }
        val service = service(io, name = "../secret.yml")
        assertEquals(manifestText, service.read(ZENWAVE_REMOTE_MANIFEST_ADDRESS).content)
        val fetches = io.loaded.size
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { service.read("zenwave:/../secret.yml") }.kind)
        assertEquals(fetches, io.loaded.size)
        assertEquals("a".repeat(MAX_REMOTE_MANIFEST_NAME_LENGTH), resolveRemoteManifestName("a".repeat(MAX_REMOTE_MANIFEST_NAME_LENGTH)).name)
        assertNull(resolveRemoteManifestName("a".repeat(MAX_REMOTE_MANIFEST_NAME_LENGTH)).warning)
    }

    @Test
    fun anAllowedHostIsFetchedAndAnyOtherHostIsRefusedWithoutAFetch() = runTest {
        val io = Recording().apply {
            serve(raw, "readme")
            serve(githubApi, "openapi: 3")
            serve(apiHost, "not this one")
        }
        val closed = service(io)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { closed.stat(raw) }.kind)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { closed.read(githubApi) }.kind)
        assertTrue("raw.githubusercontent.com" in failure { closed.read(raw) }.message.orEmpty())
        assertEquals(emptyList(), io.loaded)

        val allowed = service(io, domains = listOf("github.com", "raw.githubusercontent.com"))
        val stat = allowed.stat(githubApi)
        assertEquals("openapi: 3".length, stat.size)
        assertEquals("https", stat.source.kind)
        assertEquals("readme", allowed.read(raw).content)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { allowed.read(apiHost) }.kind)
        assertEquals(ResourceFailureKind.NOT_DECLARED, failure { allowed.read("https://github.com/acme/not-declared.yml") }.kind)
        assertEquals(listOf(githubApi, raw), io.loaded)
    }

    @Test
    fun aWildcardAllowsSubdomainsAndNotTheHostItself() = runTest {
        val io = Recording().apply {
            serve(apiHost, "api")
            serve(nested, "nested")
            serve(githubPage, "apex")
            serve(evil, "evil")
            serve(raw, "raw")
            serve(notGithub, "near")
        }
        val service = service(io, domains = listOf("*.github.com"))

        assertEquals("api", service.read(apiHost).content)
        assertEquals("nested", service.read(nested).content)
        listOf(githubPage, evil, raw, notGithub).forEach { uri ->
            assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { service.stat(uri) }.kind, uri)
        }
        assertEquals(listOf(apiHost, nested), io.loaded)
    }

    @Test
    fun anAllowedDomainMatchesTheHostIgnoringCase() = runTest {
        val canonical = "https://github.com/acme/CASE.md"
        val io = Recording().apply { serve(canonical, "case") }
        val service = service(io, domains = listOf("GitHub.COM"))

        assertEquals("case", service.read("https://github.com/acme/CASE.md").content)
        assertEquals("case", service.read("https://GITHUB.com/acme/CASE.md").content)
        assertEquals(listOf(canonical), io.loaded)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { service.read(apiHost) }.kind)
        assertEquals(listOf(canonical), io.loaded)

        val wildcard = service(Recording().apply { serve(apiHost, "api") }, domains = listOf("*.GITHUB.com"))
        assertEquals("api", wildcard.read("https://API.github.com/repos/acme/x.yml").content)
    }

    @Test
    fun aBackslashUserInfoOrControlInAnAbsoluteUriIsRefusedWithoutAFetch() = runTest {
        val slash = "https://evil.example\\@allowed.com/x"
        val userinfo = "https://allowed.com@evil.com/x"
        val spaced = "https://allowed.com/x y"
        val pathAt = "https://allowed.com/a@b"
        assertNull(AbsoluteUriPolicy.parse(slash))
        assertNull(AbsoluteUriPolicy.parse(userinfo))
        assertNull(AbsoluteUriPolicy.parse(spaced))
        assertNull(AbsoluteUriPolicy.parse("https://allowed.com/x\u0001"))
        assertNull(AbsoluteUriPolicy.parse("https://allowed.com/x\n"))
        assertNull(AbsoluteUriPolicy.parse("https://allowed.com%2e/x"))
        assertNull(AbsoluteUriPolicy.parse("https://a%40allowed.com/x"))
        assertEquals(pathAt, AbsoluteUriPolicy.parse(pathAt)?.url)
        assertEquals("allowed.com", AbsoluteUriPolicy.hostOf(pathAt))

        val io = Recording().apply {
            serve("https://evil.example/@allowed.com/x", "bypassed")
            serve("https://evil.com/x", "bypassed")
            serve("https://allowed.com/x", "bypassed")
            serve(pathAt, "path")
        }
        val service = service(
            io,
            domains = listOf("allowed.com"),
            text = manifestDeclaring("slash" to slash, "userinfo" to userinfo, "spaced" to spaced, "pathAt" to pathAt),
        )
        listOf(slash, userinfo, spaced).forEach { uri ->
            assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { service.stat(uri) }.kind, uri)
            assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { service.read(uri) }.kind, uri)
            assertFalse(uri in (failure { service.read(uri) }.message ?: ""), uri)
        }
        assertEquals("path", service.read(pathAt).content)
        assertEquals(listOf(pathAt), io.loaded)
    }

    @Test
    fun theFetchedStringIsTheCanonicalUrlWhoseHostWasChecked() = runTest {
        val dotted = "https://allowed.com./x"
        val upper = "https://ALLOWED.com/Case.md"
        val highPort = "https://allowed.com:8443/y"
        val defaultPort = "https://allowed.com:443/z"
        val suffix = "https://allowed.com.evil.net./x"
        val other = "https://evil.com./x"
        val io = Recording().apply {
            serve("https://allowed.com/x", "dot")
            serve("https://allowed.com/Case.md", "upper")
            serve("https://allowed.com:8443/y", "port")
            serve("https://allowed.com/z", "def")
        }
        val service = service(
            io,
            domains = listOf("Allowed.COM"),
            text = manifestDeclaring(
                "dotted" to dotted,
                "upper" to upper,
                "highPort" to highPort,
                "defaultPort" to defaultPort,
                "suffix" to suffix,
                "other" to other,
            ),
        )

        assertEquals("https://allowed.com/x", AbsoluteUriPolicy.identity(dotted))
        assertEquals("https://allowed.com/Case.md", AbsoluteUriPolicy.identity(upper))
        assertEquals("https://allowed.com:8443/y", AbsoluteUriPolicy.identity(highPort))
        assertEquals("https://allowed.com/z", AbsoluteUriPolicy.identity(defaultPort))
        assertEquals("allowed.com.evil.net", AbsoluteUriPolicy.hostOf(suffix))
        assertEquals("evil.com", AbsoluteUriPolicy.hostOf(other))

        assertEquals("dot", service.read(dotted).content)
        assertEquals("upper", service.read(upper).content)
        assertEquals("port", service.read(highPort).content)
        assertEquals("def", service.read(defaultPort).content)
        val suffixFailure = failure { service.stat(suffix) }
        val otherFailure = failure { service.read(other) }
        assertEquals(ResourceFailureKind.NOT_ALLOWED, suffixFailure.kind)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, otherFailure.kind)
        assertTrue("allowed.com.evil.net" in suffixFailure.message.orEmpty())
        assertTrue("evil.com" in otherFailure.message.orEmpty())
        assertEquals(
            listOf(
                "https://allowed.com/x",
                "https://allowed.com/Case.md",
                "https://allowed.com:8443/y",
                "https://allowed.com/z",
            ),
            io.loaded,
        )
    }

    @Test
    fun eachRedirectHopIsCheckedBeforeItIsFetched() = runTest {
        val start = "https://allowed.com/a"
        val next = "https://allowed.com/b"
        val blocked = "https://evil.example/b"
        val downgraded = "http://allowed.com/b"

        suspend fun exchange(
            hops: Map<String, AbsoluteHttpExchange>,
            domains: List<String> = listOf("allowed.com"),
        ): Pair<Scripted, ResourceRequestException?> {
            val http = Scripted(hops)
            val served = service(Recording(), domains = domains, text = manifestDeclaring("doc" to start), http = http)
            val error = runCatching { served.read(start) }.exceptionOrNull() as? ResourceRequestException
            return http to error
        }

        val allowed = exchange(
            mapOf(
                start to AbsoluteHttpExchange(302, next, ""),
                next to AbsoluteHttpExchange(200, null, "landed"),
            ),
        )
        assertNull(allowed.second)
        assertEquals(listOf(start, next), allowed.first.calls)
        val landed = service(
            Recording(),
            domains = listOf("allowed.com"),
            text = manifestDeclaring("doc" to start),
            http = Scripted(
                mapOf(
                    start to AbsoluteHttpExchange(302, next, ""),
                    next to AbsoluteHttpExchange(200, null, "landed"),
                ),
            ),
        )
        assertEquals("landed", landed.read(start).content)

        val relative = Scripted(
            mapOf(
                start to AbsoluteHttpExchange(302, "/b", ""),
                next to AbsoluteHttpExchange(200, null, "relative"),
            ),
        )
        val relativeService = service(Recording(), domains = listOf("allowed.com"), text = manifestDeclaring("doc" to start), http = relative)
        assertEquals("relative", relativeService.read(start).content)
        assertEquals(listOf(start, next), relative.calls)

        val refused = exchange(mapOf(start to AbsoluteHttpExchange(302, blocked, "")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, refused.second?.kind)
        assertEquals(listOf(start), refused.first.calls)

        val downgrade = exchange(mapOf(start to AbsoluteHttpExchange(301, downgraded, "")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, downgrade.second?.kind)
        assertEquals(listOf(start), downgrade.first.calls)

        val protocolRelative = exchange(mapOf(start to AbsoluteHttpExchange(302, "//evil.example/stolen", "")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, protocolRelative.second?.kind)
        assertEquals(listOf(start), protocolRelative.first.calls)

        val userinfoHop = exchange(mapOf(start to AbsoluteHttpExchange(302, "https://user:pw@evil.example/b", "")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, userinfoHop.second?.kind)
        assertEquals(listOf(start), userinfoHop.first.calls)

        val slashHop = exchange(mapOf(start to AbsoluteHttpExchange(302, "https://evil.example\\@allowed.com/b", "")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, slashHop.second?.kind)
        assertEquals(listOf(start), slashHop.first.calls)

        val upgraded = Scripted(
            mapOf(
                "http://allowed.com/a" to AbsoluteHttpExchange(302, start, ""),
                start to AbsoluteHttpExchange(200, null, "up"),
            ),
        )
        val httpStart = "http://allowed.com/a"
        val upgradeService = service(Recording(), domains = listOf("allowed.com"), text = manifestDeclaring("doc" to httpStart), http = upgraded)
        assertEquals("up", upgradeService.read(httpStart).content)
        assertEquals(listOf(httpStart, start), upgraded.calls)

        val loopA = "https://allowed.com/loop-a"
        val loopB = "https://allowed.com/loop-b"
        val loop = Scripted(
            mapOf(
                loopA to AbsoluteHttpExchange(302, loopB, ""),
                loopB to AbsoluteHttpExchange(302, loopA, ""),
            ),
        )
        val loopService = service(Recording(), domains = listOf("allowed.com"), text = manifestDeclaring("doc" to loopA), http = loop)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { loopService.read(loopA) }.kind)
        assertEquals(listOf(loopA, loopB), loop.calls)

        val chain = (0..6).map { "https://allowed.com/h$it" }
        val over = Scripted(
            chain.dropLast(1).mapIndexed { index, url -> url to AbsoluteHttpExchange(302, chain[index + 1], "") }.toMap(),
        )
        val overService = service(Recording(), domains = listOf("allowed.com"), text = manifestDeclaring("doc" to chain[0]), http = over)
        assertEquals(ResourceFailureKind.NOT_ALLOWED, failure { overService.read(chain[0]) }.kind)
        assertEquals(chain.dropLast(1), over.calls)

        val five = (0..5).map { "https://allowed.com/ok$it" }
        val exact = Scripted(
            five.dropLast(1).mapIndexed { index, url -> url to AbsoluteHttpExchange(307, five[index + 1], "") }.toMap() +
                (five.last() to AbsoluteHttpExchange(200, null, "done")),
        )
        val exactService = service(Recording(), domains = listOf("allowed.com"), text = manifestDeclaring("doc" to five[0]), http = exact)
        assertEquals("done", exactService.read(five[0]).content)
        assertEquals(five, exact.calls)

        val missing = exchange(mapOf(start to AbsoluteHttpExchange(404, null, "")))
        assertEquals(ResourceFailureKind.UNRESOLVED, missing.second?.kind)
        assertEquals(listOf(start), missing.first.calls)

        val opaque = exchange(mapOf(start to AbsoluteHttpExchange(0, "https://evil.example/hidden", "STOLEN")))
        assertEquals(ResourceFailureKind.NOT_ALLOWED, opaque.second?.kind)
        assertEquals(BROWSER_REDIRECT_UNCHECKED, opaque.second?.message)
        assertFalse("STOLEN" in (opaque.second?.message ?: ""))
        assertEquals(listOf(start), opaque.first.calls)
    }

    @Test
    fun aFailedAbsoluteReadRedactsUserInfoAndTheQueryInStatAndRead() = runTest {
        val query = "https://allowed.com/missing.md?token=QUERYSECRET"
        val io = Recording()
        val service = service(io, domains = listOf("allowed.com"), text = manifestDeclaring("missing" to query))
        listOf(failure { service.stat(query) }, failure { service.read(query) }).forEach { error ->
            assertEquals(ResourceFailureKind.UNRESOLVED, error.kind)
            val wire = buildString {
                append(error.message)
                error.candidates.forEach { append(it.url).append(it.message) }
                append(ResourceJson.failureData(error))
            }
            assertFalse("QUERYSECRET" in wire, wire)
            assertTrue("<redacted>" in wire, wire)
        }
        assertEquals(listOf(query, query), io.loaded)

        val leaked = "https://alice:s3cret@allowed.com/missing.md?token=QUERYSECRET"
        val closed = Recording()
        val refused = service(closed, domains = listOf("allowed.com"), text = manifestDeclaring("leaked" to leaked))
        listOf(failure { refused.stat(leaked) }, failure { refused.read(leaked) }).forEach { error ->
            assertEquals(ResourceFailureKind.NOT_ALLOWED, error.kind)
            val wire = (error.message ?: "") + ResourceJson.failureData(error)
            assertFalse("s3cret" in wire, wire)
            assertFalse("QUERYSECRET" in wire, wire)
        }
        assertEquals(emptyList(), closed.loaded)
    }

    @Test
    fun aRelativeRedirectKeepsEmptyPathSegments() = runTest {
        val start = "https://allowed.com/dir/start.zdl"
        val target = "https://allowed.com/dir/assets//model.zdl"
        val base = checkNotNull(AbsoluteUriPolicy.parse(start))
        assertEquals(target, AbsoluteUriPolicy.resolve(base, "assets//model.zdl")?.url)
        assertEquals("/dir/assets/x//z", AbsoluteUriPolicy.resolve(base, "assets/./x//y/../z")?.path)
        assertEquals("/dir/assets/model.zdl", AbsoluteUriPolicy.resolve(base, "./assets/model.zdl")?.path)
        assertEquals("/assets/model.zdl", AbsoluteUriPolicy.resolve(base, "../assets/model.zdl")?.path)
        assertEquals("/g", AbsoluteUriPolicy.resolve(base, "/./g")?.path)
        assertEquals("/dir/h", AbsoluteUriPolicy.resolve(base, "g/../h")?.path)
        assertEquals("/assets//model.zdl", AbsoluteUriPolicy.resolve(base, "/assets//model.zdl")?.path)
        assertEquals("/a//b", AbsoluteUriPolicy.resolve(base, "/a//./b")?.path)
        val withQuery = AbsoluteUriPolicy.resolve(base, "assets//model.zdl?x=1")
        assertEquals("$target?x=1", withQuery?.url)
        assertEquals("x=1", withQuery?.query)

        val http = Scripted(
            mapOf(
                start to AbsoluteHttpExchange(302, "assets//model.zdl", ""),
                target to AbsoluteHttpExchange(200, null, "kept"),
            ),
        )
        val served = service(
            Recording(),
            domains = listOf("allowed.com"),
            text = manifestDeclaring("doc" to start),
            http = http,
        )
        assertEquals("kept", served.read(start).content)
        assertEquals(listOf(start, target), http.calls)
    }

    @Test
    fun aDeadlineCoversEveryRedirectHopAndThenRequestsNothing() = runTest {
        val start = "https://allowed.com/a"
        val next = "https://allowed.com/b"
        val now = longArrayOf(0L)
        val calls = mutableListOf<String>()
        val http = object : AbsoluteHttpFetch {
            override suspend fun exchange(url: String): AbsoluteHttpExchange {
                calls += url
                now[0] = 30_000
                return AbsoluteHttpExchange(302, next, "STOLEN")
            }
        }
        val budget = AbsoluteHttpBudget(deadlineEpochMs = 30_000, limitMs = 30_000, clock = { now[0] })
        val error = assertFailsWith<AbsoluteHttpTransferException> {
            withContext(budget) {
                AbsoluteHttpReads.read(http, listOf("allowed.com"), checkNotNull(AbsoluteUriPolicy.parse(start)))
            }
        }
        assertTrue("timed out" in (error.message ?: "") && "30s" in (error.message ?: ""), error.message)
        assertFalse("STOLEN" in (error.message ?: ""))
        assertEquals(listOf(start), calls)

        val blocked = mutableListOf<String>()
        val closed = object : AbsoluteHttpFetch {
            override suspend fun exchange(url: String): AbsoluteHttpExchange {
                blocked += url
                return AbsoluteHttpExchange(200, null, "STOLEN")
            }
        }
        val expired = AbsoluteHttpBudget(deadlineEpochMs = 0, limitMs = 30_000, clock = { 0L })
        val early = assertFailsWith<AbsoluteHttpTransferException> {
            withContext(expired) {
                AbsoluteHttpReads.read(closed, listOf("allowed.com"), checkNotNull(AbsoluteUriPolicy.parse(start)))
            }
        }
        assertTrue("timed out" in (early.message ?: ""), early.message)
        assertEquals(emptyList(), blocked)
    }

    @Test
    fun aMissingStreamIsUnresolved() = runTest {
        val url = "https://allowed.com/model.zdl"
        val http = object : AbsoluteHttpFetch {
            override suspend fun exchange(url: String): AbsoluteHttpExchange {
                throw RuntimeException("streaming body not available")
            }
        }
        val served = service(
            Recording(),
            domains = listOf("allowed.com"),
            text = manifestDeclaring("doc" to url),
            http = http,
        )
        val failure = failure { served.read(url) }
        assertEquals(ResourceFailureKind.UNRESOLVED, failure.kind)
        assertTrue("streaming body not available" in (failure.message ?: ""), failure.message)
    }

    @Test
    fun aTimedOutOrOversizedAbsoluteReadIsUnresolvedAndRedacted() = runTest {
        assertEquals(30_000, ABSOLUTE_HTTP_TIMEOUT_MS)
        assertEquals(10 * 1024 * 1024, ABSOLUTE_HTTP_MAX_BYTES)
        assertEquals("The request timed out after 30s", absoluteHttpTimeoutMessage())
        assertEquals("The response exceeded 10 MiB", absoluteHttpSizeMessage())

        val query = "https://allowed.com/model.zdl?token=QUERYSECRET"
        suspend fun check(error: AbsoluteHttpTransferException, expected: String) {
            val http = object : AbsoluteHttpFetch {
                val calls = mutableListOf<String>()
                override suspend fun exchange(url: String): AbsoluteHttpExchange {
                    calls += url
                    throw error
                }
            }
            val served = service(
                Recording(),
                domains = listOf("allowed.com"),
                text = manifestDeclaring("doc" to query),
                http = http,
            )
            val failure = failure { served.read(query) }
            assertEquals(ResourceFailureKind.UNRESOLVED, failure.kind)
            val wire = buildString {
                append(failure.message)
                failure.candidates.forEach { append(it.url).append(it.message) }
                append(ResourceJson.failureData(failure))
            }
            assertTrue(expected in wire, wire)
            assertFalse("QUERYSECRET" in wire, wire)
            assertTrue("<redacted>" in wire, wire)
            assertEquals(listOf(query), http.calls)
        }
        check(AbsoluteHttpTransferException(absoluteHttpTimeoutMessage()), "The request timed out after 30s")
        check(AbsoluteHttpTransferException(absoluteHttpSizeMessage()), "The response exceeded 10 MiB")
    }

    @Test
    fun cancellingAnAbsoluteReadIsNotReportedAsUnresolved() = runTest {
        val query = "https://allowed.com/model.zdl?token=QUERYSECRET"
        val http = object : AbsoluteHttpFetch {
            override suspend fun exchange(url: String): AbsoluteHttpExchange {
                throw CancellationException("stopped")
            }
        }
        val served = service(
            Recording(),
            domains = listOf("allowed.com"),
            text = manifestDeclaring("doc" to query),
            http = http,
        )
        assertFailsWith<CancellationException> { served.read(query) }
    }
}
