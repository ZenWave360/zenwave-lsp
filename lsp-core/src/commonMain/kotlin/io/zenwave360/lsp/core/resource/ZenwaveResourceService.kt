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

    private fun keyOf(uri: String): String {
        if (ZenwaveResources.isRemoteManifestAddress(uri)) {
            if (!manifestIsRemote) throw notDeclared("the manifest is a local file, which is not served")
            return uri
        }
        return ZenwaveResources.parse(uri)?.address ?: throw notDeclared("not a valid zenwave: address")
    }

    private suspend fun serve(uri: String): Served {
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
                val served = fetch(key)
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

    private suspend fun fetch(key: String): Served {
        if (ZenwaveResources.isRemoteManifestAddress(key)) return fetchManifest(key)
        val allowed = authorize(key)
        val result = ZenwaveResources.load(loader, manifest, allowed)
        val content = result.content
        if (content == null) {
            val candidates = result.candidates.mapIndexed { index, candidate ->
                ResourceCandidate(
                    source = candidate.source,
                    url = redact(candidate.referenceUri()),
                    message = if (index == result.candidates.lastIndex) result.finalError?.let(::redact) else null,
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
        val text = try {
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
            val relative = relativePath(origin.path, target.path) ?: continue
            if (!mentions(base.content, relative)) continue
            val check = ZenwaveResources.checkReference(manifest, base.address, relative, known)
            if (check is ResourceCheck.Allowed) return check
        }
        throw notDeclared((declared as ResourceCheck.NotDeclared).reason)
    }

    private fun notDeclared(reason: String) =
        ResourceRequestException(ResourceFailureKind.NOT_DECLARED, "The address is not served: $reason")

    internal companion object {
        /** The path of [target] as a reference written in the file at [base]; null when it cannot be written. */
        fun relativePath(base: String, target: String): String? {
            val from = base.split('/').dropLast(1)
            val to = target.split('/')
            var common = 0
            while (common < from.size && common < to.size - 1 && from[common] == to[common]) common++
            val up = from.size - common
            val relative = List(up) { ".." } + to.drop(common)
            return relative.joinToString("/").takeIf { it.isNotEmpty() }
        }

        /** True when [content] holds [reference] as a whole token, as a `$ref` value or a link target is written. */
        fun mentions(content: String, reference: String): Boolean {
            var from = 0
            while (true) {
                val at = content.indexOf(reference, from)
                if (at < 0) return false
                val before = if (at == 0) ' ' else content[at - 1]
                val after = content.getOrElse(at + reference.length) { ' ' }
                val beforeOk = !(before.isLetterOrDigit() || before in "_-.%") &&
                    (before != '/' || (at >= 2 && content.startsWith("./", at - 2)))
                val afterOk = !(after.isLetterOrDigit() || after in "_-.%/")
                if (beforeOk && afterOk) return true
                from = at + 1
            }
        }

        /** The `file:` URI of a workspace candidate, whether the library wrote it as a URI or as a path. */
        fun fileUriOf(uri: String): String? = when {
            uri.startsWith("file:", ignoreCase = true) -> uri
            Regex("^[A-Za-z]:[/\\\\]").containsMatchIn(uri) -> "file:///" + uri.replace('\\', '/')
            uri.startsWith("/") -> "file://$uri"
            else -> null
        }

        private val USERINFO = Regex("(://)[^/@\\s]+@")
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
