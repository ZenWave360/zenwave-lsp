package io.zenwave360.lsp.core.resource

import io.zenwave360.architecture.manifest.ZenWaveManifest
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.sync.Mutex
import kotlinx.coroutines.sync.withLock

/** Why a `zenwave:` resource request failed; [wireName] is the `data.kind` sent to the client. */
enum class ResourceFailureKind(val wireName: String) {
    /** AR-R2: refused before any request was made. */
    NOT_DECLARED("resourceNotDeclared"),

    /** AR-R6: allowed, but no source could produce the content. */
    UNRESOLVED("resourceUnresolved"),
}

/** One source the library tried, in order (AR-R6). [url] has its credentials removed. */
data class ResourceCandidate(val source: String, val url: String, val message: String? = null)

/**
 * A `zenwave:` request failed. Servers send it as a JSON-RPC error with code `-32803` and [ResourceJson.failureData]
 * as `data`. [message] and [candidates] hold no credential, header or query secret.
 */
class ResourceRequestException(
    val kind: ResourceFailureKind,
    message: String,
    val candidates: List<ResourceCandidate> = emptyList(),
) : RuntimeException(message)

/** Where the bytes came from: the name of the `contentResolution` source, and, for `workspace`, the file. */
data class ResourceSource(val kind: String, val fileUri: String? = null)

/** The answer of `zenwave/statResource` (AR-R4): always a file, always read-only. */
data class ResourceStat(val uri: String, val size: Int, val source: ResourceSource)

/** The answer of `zenwave/readResource` (AR-R5). */
data class ResourceContent(val uri: String, val encoding: String, val content: String)

/** The source kind of the remote manifest itself (AR-R11). */
const val MANIFEST_SOURCE_KIND = "manifest"

/**
 * `zenwave/statResource` and `zenwave/readResource` (AR-R4, AR-R5, AR-R6, AR-R10) over one open manifest.
 *
 * Every request goes through [ZenwaveResources] (declared entries, and references found in content already read), never
 * through a broad read. Content is fetched when first asked for and kept for the life of the session, keyed by address;
 * a failure is not kept. Nothing here fetches until a request arrives.
 */
class ZenwaveResourceService(
    private val manifestUri: String,
    private val manifest: ZenWaveManifest,
    private val loader: ZenWaveManifestLoader,
    /** The text the manifest was opened with; served as is, and read again only if absent (AR-R11). */
    private val manifestText: String? = null,
) {
    private class Served(val address: String, val content: String, val source: ResourceSource)

    private val mutex = Mutex()
    private val cache = linkedMapOf<String, Served>()
    private val inFlight = mutableMapOf<String, CompletableDeferred<Served>>()
    private val readReferences = mutableSetOf<String>()

    private val manifestIsRemote = manifestUri.startsWith("http://", true) || manifestUri.startsWith("https://", true)

    suspend fun stat(uri: String): ResourceStat {
        val served = serve(uri)
        return ResourceStat(served.address, served.content.encodeToByteArray().size, served.source)
    }

    suspend fun read(uri: String): ResourceContent {
        val served = serve(uri)
        // The library loads text only, so the content is always "utf8" (a binary file is not served yet).
        return ResourceContent(served.address, "utf8", served.content)
    }

    /**
     * AR-R9: the document the import [reference], written in the document at [base], names. [base] is the importing
     * document's address and the reference is resolved against it by [ZenwaveResources.checkReference] (AR-R3), so
     * the same owner, the same version and the same declared-only check as a client's `zenwave/readResource` apply,
     * and the content is fetched once, through the same cache.
     */
    suspend fun readImport(base: String, reference: String): ResourceContent {
        val origin = ZenwaveResources.parse(base) ?: throw notDeclared("not a valid zenwave: address")
        val known = mutex.withLock { readReferences.toSet() }
        val allowed = when (val check = ZenwaveResources.checkReference(manifest, origin.address, reference, known)) {
            is ResourceCheck.Allowed -> check
            is ResourceCheck.NotDeclared -> throw notDeclared(check.reason)
        }
        val served = serve(allowed.address.address, allowed)
        return ResourceContent(served.address, "utf8", served.content)
    }

    private fun keyOf(uri: String): String {
        if (ZenwaveResources.isRemoteManifestAddress(uri)) {
            if (!manifestIsRemote) throw notDeclared("the manifest is a local file, which is not served")
            return uri
        }
        return ZenwaveResources.parse(uri)?.address ?: throw notDeclared("not a valid zenwave: address")
    }

    private suspend fun serve(uri: String, preAuthorized: ResourceCheck.Allowed? = null): Served {
        val key = keyOf(uri)
        while (true) {
            val mine = CompletableDeferred<Served>()
            val other: CompletableDeferred<Served>? = mutex.withLock {
                cache[key]?.let { return it }
                inFlight[key] ?: run { inFlight[key] = mine; null }
            }
            if (other != null) {
                try {
                    return other.await()
                } catch (cancelled: CancellationException) {
                    // The request that was fetching it was cancelled; ask again.
                    continue
                }
            }
            try {
                val served = fetch(key, preAuthorized)
                mutex.withLock {
                    cache[key] = served
                    inFlight.remove(key)
                }
                mine.complete(served)
                return served
            } catch (failure: Throwable) {
                // A failure is not cached (AR-R10).
                mutex.withLock { inFlight.remove(key) }
                mine.completeExceptionally(failure)
                throw failure
            }
        }
    }

    private suspend fun fetch(key: String, preAuthorized: ResourceCheck.Allowed? = null): Served {
        if (ZenwaveResources.isRemoteManifestAddress(key)) return fetchManifest(key)
        val allowed = preAuthorized ?: authorize(key)
        val result = ZenwaveResources.load(loader, manifest, allowed)
        val content = result.content
        if (content == null) {
            val candidates = result.candidates.mapIndexed { index, candidate ->
                ResourceCandidate(
                    source = candidate.source,
                    url = redact(candidate.referenceUri()),
                    message = (
                        result.candidateErrors.getOrNull(index)
                            ?: if (index == result.candidates.lastIndex) result.finalError else null
                        )?.let(::redact),
                )
            }
            throw ResourceRequestException(
                ResourceFailureKind.UNRESOLVED,
                "Cannot read $key: " + redact(result.errorMessage ?: "no source produced the content"),
                candidates,
            )
        }
        val resource = result.resource
        val source = ResourceSource(
            kind = resource?.source ?: "unknown",
            fileUri = if (resource?.source == "workspace") fileUriOf(resource.uri) else null,
        )
        val served = Served(allowed.address.address, content, source)
        if (allowed.declared == null) mutex.withLock { readReferences += served.address }
        return served
    }

    private suspend fun fetchManifest(key: String): Served {
        val text = manifestText ?: try {
            loader.loadResourceText(manifestUri)
        } catch (error: CancellationException) {
            throw error
        } catch (error: Throwable) {
            val message = redact(error.message ?: error::class.simpleName.orEmpty())
            throw ResourceRequestException(
                ResourceFailureKind.UNRESOLVED,
                "Cannot read $key: $message",
                listOf(ResourceCandidate(MANIFEST_SOURCE_KIND, redact(manifestUri), message.ifEmpty { null })),
            )
        }
        return Served(key, text, ResourceSource(MANIFEST_SOURCE_KIND))
    }

    /** AR-R2, then AR-R3: a reference is allowed only when content already read, under the same owner and version, names it. */
    private suspend fun authorize(address: String): ResourceCheck.Allowed {
        val declared = ZenwaveResources.check(manifest, address)
        if (declared is ResourceCheck.Allowed) return declared
        val target = ZenwaveResources.parse(address) ?: throw notDeclared("not a valid zenwave: address")
        val (bases, known) = mutex.withLock { cache.values.toList() to readReferences.toSet() }
        for (base in bases) {
            val origin = ZenwaveResources.parse(base.address) ?: continue
            if (origin.ownerId != target.ownerId || origin.version != target.version) continue
            // Only references actually written in the content are tried, each resolved by the library's own function;
            // the request is allowed only when one of them resolves exactly to the requested address.
            for (reference in referencesIn(base.content)) {
                val check = ZenwaveResources.checkReference(manifest, base.address, reference, known)
                if (check is ResourceCheck.Allowed && check.address.address == target.address) return check
            }
        }
        throw notDeclared((declared as ResourceCheck.NotDeclared).reason)
    }

    private fun notDeclared(reason: String) =
        ResourceRequestException(ResourceFailureKind.NOT_DECLARED, "The address is not served: $reason")

    internal companion object {
        private val REF_VALUE = Regex("""\${'$'}ref["']?\s*:\s*(?:"([^"]*)"|'([^']*)'|([^\s,}"']+))""")
        private val LINK_TARGET = Regex("""\]\(\s*<?([^)\s>]+)""")

        /** The reference strings written in [content]: `$ref` values and Markdown link or image targets. */
        fun referencesIn(content: String): List<String> =
            (
                REF_VALUE.findAll(content).map { it.groupValues[1].ifEmpty { it.groupValues[2] }.ifEmpty { it.groupValues[3] } } +
                    LINK_TARGET.findAll(content).map { it.groupValues[1] } +
                    DocumentImports.inText(content).map { it.reference }
                ).filter { it.isNotEmpty() }.distinct().toList()

        /** The `file:` URI of a workspace candidate, whether the library wrote it as a URI or as a path. */
        fun fileUriOf(uri: String): String? = when {
            uri.startsWith("file:", ignoreCase = true) -> uri
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(uri) -> "file:///" + uri.replace('\\', '/')
            uri.startsWith("/") -> "file://$uri"
            else -> null
        }

        private val USERINFO = Regex("(://)[^/?#\\s]*@")
        private val QUERY_VALUE = Regex("([?&][^=&#\\s]+)=([^&#\\s]*)")
        private val SCHEME_CREDENTIAL = Regex("\\b(bearer|basic)\\s+[A-Za-z0-9._~+/=-]{6,}", RegexOption.IGNORE_CASE)
        private val HEADER_CREDENTIAL =
            Regex(
                "\\b(authorization|x-api-key|api-key|proxy-authorization|cookie)\\s*[:=]\\s*[^\\s,;]+(\\s+[^\\s,;]+)?",
                RegexOption.IGNORE_CASE,
            )

        /** Removes what could be a credential from a URL or an error text: user info, query values, auth headers. */
        fun redact(text: String): String = text
            .replace(USERINFO, "$1<redacted>@")
            .replace(QUERY_VALUE, "$1=<redacted>")
            .replace(HEADER_CREDENTIAL) { "${it.groupValues[1]}: <redacted>" }
            .replace(SCHEME_CREDENTIAL) { "${it.groupValues[1]} <redacted>" }
    }
}
