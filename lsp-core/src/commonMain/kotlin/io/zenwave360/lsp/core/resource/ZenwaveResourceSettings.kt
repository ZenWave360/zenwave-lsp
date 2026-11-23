package io.zenwave360.lsp.core.resource

/**
 * What the client passes in `initializationOptions.zenwave` for architecture resources (AN/4.5).
 *
 * ```json
 * { "zenwave": { "remoteManifestName": ".zenwave-manifest.yml",
 *                "allowedDomains": ["github.com", "*.githubusercontent.com"] } }
 * ```
 *
 * [remoteManifestName] is a plain file name. The address of the remote manifest is `zenwave:/<name>`.
 * [allowedDomains] names the hosts an absolute `http(s)` URI may be fetched from (AR-Q4).
 * An entry matches a host exactly, ignoring case. An entry that starts with `*.` matches subdomains of
 * that host, at any depth, and not the host itself. The default list is empty, so nothing absolute is fetched.
 * The host that is checked is the host of one canonical URL, and that same string is what is fetched.
 */
data class ZenwaveResourceSettings(
    val remoteManifestName: String = DEFAULT_REMOTE_MANIFEST_NAME,
    val allowedDomains: List<String> = emptyList(),
)

/** The name the client asked for, or the default, and a warning when the request was rejected. */
data class ResolvedRemoteManifestName(val name: String, val warning: String?)

/** A remote manifest name is at most this many characters. Longer names are rejected. */
internal const val MAX_REMOTE_MANIFEST_NAME_LENGTH = 255

/**
 * A remote manifest name is a plain file name: not empty, not `.` or `..`, at most
 * [MAX_REMOTE_MANIFEST_NAME_LENGTH] characters, and containing none of `/`, `\`, `%`, `#`, `?`, `..`
 * or a control character. Anything else falls back to [DEFAULT_REMOTE_MANIFEST_NAME];
 * [ResolvedRemoteManifestName.warning] is what the server logs.
 */
fun resolveRemoteManifestName(requested: String?): ResolvedRemoteManifestName {
    if (requested == null) return ResolvedRemoteManifestName(DEFAULT_REMOTE_MANIFEST_NAME, null)
    if (isPlainRemoteManifestName(requested)) return ResolvedRemoteManifestName(requested, null)
    return ResolvedRemoteManifestName(
        DEFAULT_REMOTE_MANIFEST_NAME,
        "remoteManifestName '$requested' is not a plain file name; using '$DEFAULT_REMOTE_MANIFEST_NAME'",
    )
}

fun isPlainRemoteManifestName(name: String): Boolean =
    name.isNotBlank() &&
        name.length <= MAX_REMOTE_MANIFEST_NAME_LENGTH &&
        name != "." &&
        '/' !in name && '\\' !in name && '%' !in name && '#' !in name && '?' !in name && ".." !in name &&
        name.none { it.isISOControl() }

/**
 * One absolute `http(s)` URL after a single parse. [url] is the string that is fetched; [host] is that
 * string's host. Scheme and host are lower case, a trailing dot is removed from the host, and a default
 * port (`80` or `443`) is omitted. User info is never present.
 */
internal data class CanonicalAbsoluteUrl(
    val url: String,
    val scheme: String,
    val host: String,
    val port: Int?,
    val path: String,
    val query: String?,
    val fragment: String?,
)

/**
 * AR-Q4. Parses an absolute `http(s)` URI once. A backslash, user info (`@` in the authority), a control
 * character or whitespace is rejected, because those are read differently by the fetch implementation than
 * by a host regular expression. The host of [CanonicalAbsoluteUrl.url] is what [allows] checks, and that
 * same string is what the absolute fetch requests.
 */
internal object AbsoluteUriPolicy {
    private val HOSTNAME = Regex("^[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?(\\.[a-z0-9]([a-z0-9-]{0,61}[a-z0-9])?)*$")
    private val SCHEME = Regex("^[A-Za-z][A-Za-z0-9+.-]*:")

    /** `http://` or `https://`, ignoring case. A rejected spelling still matches, so it is not fetched elsewhere. */
    fun isAbsoluteHttp(uri: String): Boolean {
        val scheme = uri.substringBefore(':', missingDelimiterValue = "")
        if (!scheme.equals("http", ignoreCase = true) && !scheme.equals("https", ignoreCase = true)) return false
        val mark = scheme.length
        return uri.length > mark + 2 && uri[mark] == ':' && uri[mark + 1] == '/' && uri[mark + 2] == '/'
    }

    fun hostOf(uri: String): String? = parse(uri)?.host

    /** The canonical URL, so two writings of one absolute URI name the same entry. */
    fun identity(uri: String): String? = parse(uri)?.url

    fun allows(allowedDomains: List<String>, canonical: CanonicalAbsoluteUrl): Boolean =
        allowedDomains.any { matches(it, canonical.host) }

    /**
     * The canonical form of [raw], or null when it is not an `http(s)` URL this server is willing to fetch.
     * Null means nothing is requested.
     */
    fun parse(raw: String): CanonicalAbsoluteUrl? {
        if (raw.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return null
        val schemeSplit = raw.indexOf("://")
        if (schemeSplit <= 0) return null
        val scheme = raw.substring(0, schemeSplit).lowercase()
        if (scheme != "http" && scheme != "https") return null
        if (raw.substring(0, schemeSplit).any { it !in 'A'..'Z' && it !in 'a'..'z' }) return null
        val after = raw.substring(schemeSplit + 3)
        val authEnd = after.indexOfAny(charArrayOf('/', '?', '#'))
        val authority = if (authEnd < 0) after else after.substring(0, authEnd)
        val rest = if (authEnd < 0) "" else after.substring(authEnd)
        if (authority.isEmpty() || '@' in authority || '%' in authority) return null
        val (host, port) = hostAndPort(authority) ?: return null
        val (path, query, fragment) = splitRest(rest) ?: return null
        return assemble(scheme, host, port, path, query, fragment)
    }

    /** [location] resolved against [base], then parsed with [parse]. A value [parse] rejects resolves to null. */
    fun resolve(base: CanonicalAbsoluteUrl, location: String): CanonicalAbsoluteUrl? {
        if (location.isEmpty()) return null
        if (location.any { it == '\\' || it.isWhitespace() || it.isISOControl() }) return null
        if (location.startsWith("//")) return parse("${base.scheme}:$location")
        if (SCHEME.containsMatchIn(location)) return parse(location)
        val hash = location.indexOf('#')
        val beforeHash = if (hash < 0) location else location.substring(0, hash)
        val fragment = if (hash < 0) null else location.substring(hash + 1)
        val queryAt = beforeHash.indexOf('?')
        val pathRef = if (queryAt < 0) beforeHash else beforeHash.substring(0, queryAt)
        val query = if (queryAt < 0) null else beforeHash.substring(queryAt + 1)
        val path = when {
            pathRef.startsWith("/") -> removeDotSegments(pathRef)
            pathRef.isEmpty() -> base.path
            else -> removeDotSegments(mergePaths(base.path, pathRef))
        }
        val resolvedQuery = when {
            queryAt >= 0 -> query
            pathRef.isEmpty() -> base.query
            else -> null
        }
        if (path.isNotEmpty() && !path.startsWith("/")) return null
        return assemble(base.scheme, base.host, base.port, path, resolvedQuery, fragment)
    }

    private fun assemble(
        scheme: String,
        host: String,
        port: Int?,
        path: String,
        query: String?,
        fragment: String?,
    ): CanonicalAbsoluteUrl {
        val shownPort = port?.takeUnless { (scheme == "https" && it == 443) || (scheme == "http" && it == 80) }
        val url = buildString {
            append(scheme).append("://").append(host)
            if (shownPort != null) append(':').append(shownPort)
            append(path)
            if (query != null) append('?').append(query)
            if (fragment != null) append('#').append(fragment)
        }
        return CanonicalAbsoluteUrl(url, scheme, host, shownPort, path, query, fragment)
    }

    private fun hostAndPort(authority: String): Pair<String, Int?>? {
        if (authority.startsWith("[")) {
            val end = authority.indexOf(']')
            if (end < 2 || authority.indexOf(']', end + 1) >= 0) return null
            val host = authority.substring(0, end + 1).lowercase()
            val inside = host.substring(1, host.length - 1)
            if (inside.none { it in '0'..'9' || it in 'a'..'f' }) return null
            if (inside.any { it != ':' && it != '.' && it !in '0'..'9' && it !in 'a'..'f' }) return null
            val rest = authority.substring(end + 1)
            val port = when {
                rest.isEmpty() -> null
                rest.startsWith(":") -> parsePort(rest.substring(1)) ?: return null
                else -> return null
            }
            return host to port
        }
        val colon = authority.lastIndexOf(':')
        val hostRaw: String
        val port: Int?
        if (colon >= 0) {
            hostRaw = authority.substring(0, colon)
            port = parsePort(authority.substring(colon + 1)) ?: return null
        } else {
            hostRaw = authority
            port = null
        }
        val host = hostRaw.lowercase().trimEnd('.')
        if (host.isEmpty() || !HOSTNAME.matches(host)) return null
        return host to port
    }

    private fun parsePort(raw: String): Int? {
        if (raw.isEmpty() || raw.length > 5 || raw.any { it !in '0'..'9' }) return null
        val value = raw.toIntOrNull() ?: return null
        return value.takeIf { it in 1..65535 }
    }

    private fun splitRest(rest: String): Triple<String, String?, String?>? {
        val hash = rest.indexOf('#')
        val before = if (hash < 0) rest else rest.substring(0, hash)
        val fragment = if (hash < 0) null else rest.substring(hash + 1)
        val queryAt = before.indexOf('?')
        val path = if (queryAt < 0) before else before.substring(0, queryAt)
        val query = if (queryAt < 0) null else before.substring(queryAt + 1)
        if (path.isNotEmpty() && !path.startsWith("/")) return null
        return Triple(path, query, fragment)
    }

    private fun mergePaths(basePath: String, reference: String): String {
        val directory = when {
            basePath.isEmpty() -> "/"
            else -> basePath.substring(0, basePath.lastIndexOf('/') + 1).ifEmpty { "/" }
        }
        return directory + reference
    }

    /**
     * RFC 3986 §5.2.4. `.` and `..` segments are removed. An empty segment is kept, so a redirect to
     * `assets//model.zdl` stays `.../assets//model.zdl` and is not collapsed to a different path.
     */
    private fun removeDotSegments(path: String): String {
        var input = path
        val output = StringBuilder()
        while (input.isNotEmpty()) {
            when {
                input.startsWith("../") -> input = input.removePrefix("../")
                input.startsWith("./") -> input = input.removePrefix("./")
                input.startsWith("/./") -> input = "/" + input.removePrefix("/./")
                input == "/." -> input = "/"
                input.startsWith("/../") -> {
                    input = "/" + input.removePrefix("/../")
                    removeLastSegment(output)
                }
                input == "/.." -> {
                    input = "/"
                    removeLastSegment(output)
                }
                input == "." || input == ".." -> input = ""
                else -> {
                    val slash = if (input.startsWith("/")) input.indexOf('/', 1) else input.indexOf('/')
                    if (slash < 0) {
                        output.append(input)
                        input = ""
                    } else {
                        output.append(input.substring(0, slash))
                        input = input.substring(slash)
                    }
                }
            }
        }
        return output.toString()
    }

    private fun removeLastSegment(output: StringBuilder) {
        val last = output.lastIndexOf('/')
        if (last >= 0) output.setLength(last) else output.clear()
    }

    /** Exact host, or, when [entry] starts with `*.`, a subdomain of the rest. Both ignore case. */
    fun matches(entry: String, host: String): Boolean {
        val pattern = entry.trim()
        if (pattern.isEmpty()) return false
        if (pattern.startsWith("*.")) {
            val suffix = pattern.substring(2).trim()
            if (suffix.isEmpty() || '/' in suffix || '*' in suffix) return false
            val dotted = ".${suffix.lowercase()}"
            val normalized = host.lowercase()
            return normalized.length > dotted.length && normalized.endsWith(dotted)
        }
        if ('*' in pattern) return false
        return host.equals(pattern, ignoreCase = true)
    }
}

/**
 * An absolute URI this server will not fetch (AR-Q4). Thrown before the refused URL is requested.
 * [message] replaces the host text when the refusal is a browser redirect that cannot be checked hop by hop.
 */
internal class AbsoluteUriNotAllowed(
    val uri: String,
    val host: String?,
    message: String? = null,
) : RuntimeException(
    message ?: "The absolute URI is not served: host '${host ?: "(none)"}' is not an allowed domain",
)
