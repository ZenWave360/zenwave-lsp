package io.zenwave360.lsp.core.resource

import kotlin.coroutines.AbstractCoroutineContextElement
import kotlin.coroutines.CoroutineContext
import kotlinx.coroutines.currentCoroutineContext
import kotlinx.coroutines.withContext

/**
 * One HTTP request that does not follow redirects.
 *
 * The manifest loader's HTTP client lives in json-schema-ref-parser (pulled in by zenwave-architecture) and follows
 * redirects on its own, up to ten hops, without asking this server. Absolute `http(s)` reads therefore do not use
 * that client: lsp-core performs the request through this seam and applies [AbsoluteUriPolicy] to every hop.
 *
 * Node and the JVM report each redirect and [AbsoluteHttpReads] checks it before the next URL is requested.
 * A browser's `redirect: "manual"` hides the hop (`opaqueredirect`, status 0). That answer is refused with
 * [BROWSER_REDIRECT_UNCHECKED]. Nothing is retried with `redirect: "follow"`, because a URL this policy has not
 * checked must not be contacted.
 */
fun interface AbsoluteHttpFetch {
    suspend fun exchange(url: String): AbsoluteHttpExchange
}

/**
 * The status line of one request. [location] is the raw `Location` header when the status is a redirect.
 * The body of a redirect or an error is empty; the platform client discards those bytes.
 */
data class AbsoluteHttpExchange(
    val status: Int,
    val location: String?,
    val body: String,
)

/** A fetch that stopped because it took longer than [ABSOLUTE_HTTP_TIMEOUT_MS] or the body passed [ABSOLUTE_HTTP_MAX_BYTES]. */
class AbsoluteHttpTransferException(message: String) : RuntimeException(message)

/** Connect and read bound for one absolute HTTP request. */
internal const val ABSOLUTE_HTTP_TIMEOUT_MS = 30_000

/** Largest response body an absolute HTTP read will keep. */
internal const val ABSOLUTE_HTTP_MAX_BYTES = 10 * 1024 * 1024

/**
 * A browser `redirect: "manual"` answer (`opaqueredirect`, or status 0) hides every hop.
 * The fetch is refused. The caller asks for the final URL itself.
 */
internal const val BROWSER_REDIRECT_UNCHECKED =
    "the browser cannot check redirect hops; use the final URL directly (e.g. raw.githubusercontent.com)"

/** Chunk trailers, counted together. Past this the socket is closed. */
internal const val ABSOLUTE_HTTP_TRAILER_MAX_BYTES = 16 * 1024

internal expect fun absoluteHttpNow(): Long

/**
 * Total time for one absolute read, from the first hop through DNS, connect, later hops and the body.
 * A platform client that sees this stops at [deadlineEpochMs] instead of starting a fresh timeout per hop.
 */
internal class AbsoluteHttpBudget(
    val deadlineEpochMs: Long,
    val limitMs: Int,
    val clock: () -> Long,
) : AbstractCoroutineContextElement(AbsoluteHttpBudget) {
    companion object Key : CoroutineContext.Key<AbsoluteHttpBudget>

    fun remainingMs(now: Long = clock()): Long = deadlineEpochMs - now
}

internal fun absoluteHttpTimeoutMessage(timeoutMs: Int = ABSOLUTE_HTTP_TIMEOUT_MS): String =
    if (timeoutMs == ABSOLUTE_HTTP_TIMEOUT_MS) "The request timed out after 30s"
    else "The request timed out after ${timeoutMs}ms"

internal fun absoluteHttpSizeMessage(maxBytes: Int = ABSOLUTE_HTTP_MAX_BYTES): String =
    if (maxBytes == ABSOLUTE_HTTP_MAX_BYTES) "The response exceeded 10 MiB"
    else "The response exceeded $maxBytes bytes"

/** The platform `fetch` / socket client that does not follow redirects. */
expect fun platformAbsoluteHttpFetch(): AbsoluteHttpFetch

/**
 * Fetches [canonical], then at most [MAX_REDIRECTS] redirects. Each `Location` is resolved and parsed with
 * [AbsoluteUriPolicy], and the next request is made only when that hop's host is allowed and the scheme does not
 * drop from `https` to `http`. A loop, a sixth hop, or a hop the policy rejects throws [AbsoluteUriNotAllowed]
 * before that URL is requested.
 */
internal object AbsoluteHttpReads {
    const val MAX_REDIRECTS = 5

    private val REDIRECT_STATUS = setOf(301, 302, 303, 307, 308)

    suspend fun read(
        fetch: AbsoluteHttpFetch,
        allowedDomains: List<String>,
        canonical: CanonicalAbsoluteUrl,
    ): String {
        val existing = currentCoroutineContext()[AbsoluteHttpBudget]
        if (existing != null) return readWithin(fetch, allowedDomains, canonical, existing)
        val clock = ::absoluteHttpNow
        val budget = AbsoluteHttpBudget(clock() + ABSOLUTE_HTTP_TIMEOUT_MS, ABSOLUTE_HTTP_TIMEOUT_MS, clock)
        return withContext(budget) { readWithin(fetch, allowedDomains, canonical, budget) }
    }

    private suspend fun readWithin(
        fetch: AbsoluteHttpFetch,
        allowedDomains: List<String>,
        canonical: CanonicalAbsoluteUrl,
        budget: AbsoluteHttpBudget,
    ): String {
        if (!AbsoluteUriPolicy.allows(allowedDomains, canonical)) {
            throw AbsoluteUriNotAllowed(canonical.url, canonical.host)
        }
        var current = canonical
        val seen = mutableSetOf<String>()
        var followed = 0
        while (true) {
            // The budget started with the first hop and is not reset. An expired budget requests nothing more.
            if (budget.remainingMs() <= 0) {
                throw AbsoluteHttpTransferException(absoluteHttpTimeoutMessage(budget.limitMs))
            }
            if (!seen.add(current.url)) throw AbsoluteUriNotAllowed(current.url, current.host)
            val exchange = fetch.exchange(current.url)
            // A browser `redirect: "manual"` answer hides Location (opaqueredirect, status 0).
            // Retrying with redirect "follow" would contact a hop that has not been checked.
            if (exchange.status == 0) {
                throw AbsoluteUriNotAllowed(current.url, current.host, BROWSER_REDIRECT_UNCHECKED)
            }
            if (exchange.status !in REDIRECT_STATUS) {
                if (exchange.status !in 200..299) throw RuntimeException("HTTP ${exchange.status}")
                return exchange.body
            }
            followed += 1
            if (followed > MAX_REDIRECTS) throw AbsoluteUriNotAllowed(current.url, current.host)
            val location = exchange.location?.takeIf { it.isNotEmpty() }
                ?: throw AbsoluteUriNotAllowed(current.url, current.host)
            val next = AbsoluteUriPolicy.resolve(current, location)
                ?: throw AbsoluteUriNotAllowed(current.url, current.host)
            if (current.scheme == "https" && next.scheme == "http") {
                throw AbsoluteUriNotAllowed(next.url, next.host)
            }
            if (!AbsoluteUriPolicy.allows(allowedDomains, next)) {
                throw AbsoluteUriNotAllowed(next.url, next.host)
            }
            current = next
        }
    }
}
