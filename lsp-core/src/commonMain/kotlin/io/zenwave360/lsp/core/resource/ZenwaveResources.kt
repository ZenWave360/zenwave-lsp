package io.zenwave360.lsp.core.resource

import io.zenwave360.architecture.manifest.ManifestArtifact
import io.zenwave360.architecture.manifest.ManifestArtifactOwner
import io.zenwave360.architecture.manifest.ManifestDomain
import io.zenwave360.architecture.manifest.ManifestLoadOptions
import io.zenwave360.architecture.manifest.ManifestResolutionException
import io.zenwave360.architecture.manifest.ManifestResourceLoadException
import io.zenwave360.architecture.manifest.ManifestResourceLoadResult
import io.zenwave360.architecture.manifest.ManifestService
import io.zenwave360.architecture.manifest.ZenWaveManifest
import io.zenwave360.architecture.manifest.ZenWaveManifestLoader
import io.zenwave360.lsp.core.config.ResourceReferenceResolver

/** The segment that stands for "no version" in a `zenwave:` address (AR-Q3). */
const val ZENWAVE_UNVERSIONED_SEGMENT = "-"

/** The address of a remote manifest (AR-Q1): the reserved leading dot is a first segment no owner id has. */
const val ZENWAVE_REMOTE_MANIFEST_ADDRESS = "zenwave:/.zenwave-manifest.yml"

/**
 * A parsed `zenwave:/<owner.id>/<version>/<content.path>` address (AR-R1). The fields are decoded; [version] is
 * null for the `-` segment. Parsing is purely syntactic: whether the manifest declares it is [ZenwaveResources.check].
 */
data class ZenwaveAddress(val ownerId: String, val version: String?, val path: String) {
    val address: String get() = ZenwaveResources.addressOf(ownerId, version, path)
}

enum class DeclaredKind { ARTIFACT, DOCUMENT }

const val UNADDRESSABLE_PERCENT_REASON = "a literal % in a path is not supported yet"

/** Why a declared entry has no `zenwave:` address. */
enum class DeclaredFlag {
    /** The entry is an absolute URI (AR-Q4): it bypasses `contentResolution` and has no owner-relative name. */
    ABSOLUTE_URI,

    /** The entry names a folder, which is reserved for phase 2 (AR-R12). */
    FOLDER,

    /**
     * The path or the owner id cannot be written as address segments (empty, `.`, `..`, backslash, control), or the
     * path holds a literal `%`: [UNADDRESSABLE_PERCENT_REASON]. Such an entry is never served.
     */
    UNADDRESSABLE,
}

/** One entry of the manifest that has, or would have, an address. */
data class DeclaredResource(
    val owner: ManifestArtifactOwner,
    val kind: DeclaredKind,
    /** The version as AR-R1 defines it; null when none is declared (the `-` segment). */
    val version: String?,
    /** The path exactly as the manifest writes it. */
    val path: String,
    /** The artifact, for [DeclaredKind.ARTIFACT]. */
    val artifact: ManifestArtifact?,
    /** The docs key, for [DeclaredKind.DOCUMENT]. */
    val docKey: String?,
    val flag: DeclaredFlag?,
) {
    /** The `zenwave:` address, or null when [flag] is set. */
    val address: String? get() = if (flag != null) null else ZenwaveResources.addressOf(owner.id, version, path)
}

sealed interface ResourceCheck {
    /**
     * The address is served. [declared] is the manifest entry, or null when the address was allowed only as a
     * relative reference found inside content read through a declared artifact (AR-R3), which is never listed.
     */
    data class Allowed(
        val address: ZenwaveAddress,
        val owner: ManifestArtifactOwner,
        val declared: DeclaredResource?,
    ) : ResourceCheck

    /** AR-R2: refused as `resourceNotDeclared`, with no network request made. [reason] is for logs. */
    data class NotDeclared(val reason: String) : ResourceCheck
}

/**
 * The `zenwave:` address and the declared-only check as one function (AR-R1, AR-R2, AR-R3), built on the
 * library's load of [ZenWaveManifestLoader] (SR-R5 for documents and references). Nothing here touches the network
 * except [load].
 */
object ZenwaveResources {
    /** AR-R1. Each segment is percent-encoded as a URI path segment; the path keeps its own `/`. */
    fun addressOf(ownerId: String, version: String?, path: String): String =
        "zenwave:/" + listOf(ownerId, version ?: ZENWAVE_UNVERSIONED_SEGMENT).joinToString("/") { encodeSegment(it) } +
            "/" + path.split('/').joinToString("/") { encodeSegment(it) }

    fun isRemoteManifestAddress(address: String): Boolean = address == ZENWAVE_REMOTE_MANIFEST_ADDRESS

    /**
     * Parses the grammar of AR-R1, or returns null. Refused, before anything is joined to anything: another
     * scheme, an authority, a query or fragment, a first segment that starts with a dot, fewer than three
     * segments, an empty, `.` or `..` segment (also when written percent-encoded), an encoded or literal
     * separator inside a segment, a control character, and a malformed percent-encoding.
     */
    fun parse(address: String): ZenwaveAddress? {
        if (!address.startsWith("zenwave:/") || address.startsWith("zenwave://")) return null
        val body = address.substring("zenwave:/".length)
        if ('?' in body || '#' in body) return null
        val raw = body.split('/')
        if (raw.size < 3) return null
        val segments = raw.map { decodeSegment(it) ?: return null }
        if (segments.any { it.isEmpty() || it == "." || it == ".." }) return null
        if (segments[0].startsWith(".")) return null
        val version = segments[1]
        return ZenwaveAddress(
            ownerId = segments[0],
            version = if (version == ZENWAVE_UNVERSIONED_SEGMENT) null else version,
            path = segments.drop(2).joinToString("/"),
        )
    }

    /** Every entry of every owner (domains and services), in manifest order, flagged when it has no address. */
    fun declaredResources(manifest: ZenWaveManifest): List<DeclaredResource> = buildList {
        for (owner in manifest.artifactOwners) {
            val documentVersion = when (owner) {
                is ManifestService -> owner.documentVersion()
                is ManifestDomain -> owner.version?.trim()?.takeIf { it.isNotEmpty() }
            }
            val docs = when (owner) {
                is ManifestService -> owner.docs
                is ManifestDomain -> owner.docs
            }
            owner.artifacts.forEach { artifact ->
                add(entry(owner, DeclaredKind.ARTIFACT, artifact.resolvedVersion, artifact.path, artifact, null))
            }
            docs.forEach { (key, path) -> add(entry(owner, DeclaredKind.DOCUMENT, documentVersion, path, null, key)) }
        }
    }

    private fun entry(
        owner: ManifestArtifactOwner,
        kind: DeclaredKind,
        version: String?,
        path: String,
        artifact: ManifestArtifact?,
        docKey: String?,
    ): DeclaredResource {
        val flag = when {
            ResourceReferenceResolver.hasScheme(path) -> DeclaredFlag.ABSOLUTE_URI
            // AR-R12 reserves folders and globs (`adrs/`, `adrs/*.md`) for phase 2: no address yet.
            path.endsWith("/") || path.any { it in "*?[{" } -> DeclaredFlag.FOLDER
            owner.id.isEmpty() || owner.id.startsWith(".") || !isAddressable(path) -> DeclaredFlag.UNADDRESSABLE
            else -> null
        }
        return DeclaredResource(owner, kind, version, path, artifact, docKey, flag)
    }

    private fun isAddressable(path: String): Boolean =
        path.split('/').none { it.isEmpty() || it == "." || it == ".." } &&
            path.none { it == '\\' || it == '%' || it.code < 0x20 || it.code == 0x7f }

    /**
     * AR-R2. [address] is served only when its owner id, version and path are a declared entry of the manifest.
     * A refusal never makes a network request.
     */
    fun check(manifest: ZenWaveManifest, address: String): ResourceCheck {
        val parsed = parse(address) ?: return ResourceCheck.NotDeclared("not a valid zenwave: address")
        val entries = declaredFor(manifest, parsed)
        if (entries.isEmpty()) return ResourceCheck.NotDeclared("no declared owner and version match the address")
        entries.firstOrNull { it.path == parsed.path }?.let { return ResourceCheck.Allowed(parsed, it.owner, it) }
        return ResourceCheck.NotDeclared("the path is not declared and no declared content refers to it")
    }

    /**
     * AR-R3. The reference [reference] found in the content at [base] is allowed when [base] is itself allowed
     * and [resolveReference] gives its address: nothing else under the same owner and version is opened. [base] is
     * allowed when [check] allows it as a declared entry, or when it is one of [readReferences], the addresses of
     * references this function allowed earlier and the server read (a reference inside a reference). The caller
     * must only put there addresses this function returned as allowed.
     */
    fun checkReference(
        manifest: ZenWaveManifest,
        base: String,
        reference: String,
        readReferences: Collection<String> = emptyList(),
    ): ResourceCheck {
        val origin = parse(base) ?: return ResourceCheck.NotDeclared("not a valid zenwave: address")
        val baseAllowed = check(manifest, base) is ResourceCheck.Allowed ||
            (base in readReferences && declaredFor(manifest, origin).isNotEmpty())
        if (!baseAllowed) return ResourceCheck.NotDeclared("the content the reference was found in is not allowed")
        val target = resolveReference(base, reference)
            ?: return ResourceCheck.NotDeclared("the reference leaves the owner's content")
        val parsed = parse(target) ?: return ResourceCheck.NotDeclared("not a valid zenwave: address")
        val entries = declaredFor(manifest, origin)
        // A reference that lands on a declared entry is that entry, so an artifact keeps its artifact resolution.
        entries.firstOrNull { it.path == parsed.path }?.let { return ResourceCheck.Allowed(parsed, it.owner, it) }
        if ('%' in parsed.path) return ResourceCheck.NotDeclared(UNADDRESSABLE_PERCENT_REASON)
        return ResourceCheck.Allowed(parsed, entries.first().owner, null)
    }

    private fun declaredFor(manifest: ZenWaveManifest, address: ZenwaveAddress): List<DeclaredResource> =
        declaredResources(manifest).filter { it.flag == null && it.owner.id == address.ownerId && it.version == address.version }

    /**
     * AR-R3: the address a relative [reference] found in the content at [base] has, with the same owner and
     * version; or null when it has a scheme, is absolute, or once normalised leaves the owner's content. A
     * fragment is dropped. The result still needs [check].
     */
    fun resolveReference(base: String, reference: String): String? {
        val origin = parse(base) ?: return null
        val target = reference.substringBefore('#')
        if (target.isEmpty()) return origin.address
        if (ResourceReferenceResolver.hasScheme(target) || target.startsWith("/") || target.startsWith("?")) return null
        val joined = origin.path.split('/').dropLast(1).toMutableList()
        for (raw in target.split('/')) {
            val segment = decodeSegment(raw) ?: return null
            when (segment) {
                "", "." -> Unit
                ".." -> if (joined.isEmpty()) return null else joined.removeAt(joined.lastIndex)
                else -> joined.add(segment)
            }
        }
        if (joined.isEmpty()) return null
        return addressOf(origin.ownerId, origin.version, joined.joinToString("/"))
    }

    /**
     * Reads what [allowed] names through the library: a declared artifact with the artifact load, a declared
     * document or a reference with SR-R5's load of a path of the owner at the address's version. A failure is a
     * result with [ManifestResourceLoadResult.errorMessage], which lists the candidates tried, credentials removed.
     */
    suspend fun load(
        loader: ZenWaveManifestLoader,
        manifest: ZenWaveManifest,
        allowed: ResourceCheck.Allowed,
        options: ManifestLoadOptions = ManifestLoadOptions(),
    ): ManifestResourceLoadResult {
        val artifact = allowed.declared?.artifact
        if (artifact != null) return loader.loadArtifactResult(manifest, allowed.owner, artifact, options)
        val path = allowed.address.path
        return try {
            loader.loadOwnedDocument(manifest, allowed.owner, allowed.address.version, path, options)
        } catch (error: ManifestResourceLoadException) {
            ManifestResourceLoadResult(path = path, errorMessage = error.message)
        } catch (error: ManifestResolutionException) {
            ManifestResourceLoadResult(path = path, errorMessage = error.message)
        }
    }

    private const val HEX = "0123456789ABCDEF"

    /** RFC 3986 `pchar`: unreserved, sub-delims, `:` and `@` stay; everything else is UTF-8 percent-encoded. */
    private fun encodeSegment(segment: String): String = buildString {
        for (byte in segment.encodeToByteArray()) {
            val code = byte.toInt() and 0xff
            val char = code.toChar()
            if (code < 0x80 && (char in 'A'..'Z' || char in 'a'..'z' || char in '0'..'9' || char in "-._~!$&'()*+,;=:@")) {
                append(char)
            } else {
                append('%').append(HEX[code shr 4]).append(HEX[code and 0xf])
            }
        }
    }

    /** The decoded segment, or null for a bad escape, invalid UTF-8, a control character or a separator. */
    private fun decodeSegment(segment: String): String? {
        val bytes = ArrayList<Byte>(segment.length)
        val literal = StringBuilder()
        fun flush() {
            if (literal.isNotEmpty()) {
                literal.toString().encodeToByteArray().forEach { bytes.add(it) }
                literal.clear()
            }
        }
        var i = 0
        while (i < segment.length) {
            if (segment[i] == '%') {
                if (i + 2 >= segment.length) return null
                val high = segment[i + 1].digitToIntOrNull(16) ?: return null
                val low = segment[i + 2].digitToIntOrNull(16) ?: return null
                flush()
                bytes.add(((high shl 4) or low).toByte())
                i += 3
            } else {
                literal.append(segment[i])
                i += 1
            }
        }
        flush()
        val decoded = try {
            bytes.toByteArray().decodeToString(throwOnInvalidSequence = true)
        } catch (error: Exception) {
            return null
        }
        // Decoded exactly once. A `%` in the result is refused wherever a client or a reference supplies it
        // (checkReference); a declared path with one is never addressed (UNADDRESSABLE_PERCENT_REASON).
        if (decoded.any { it == '/' || it == '\\' || it.code < 0x20 || it.code == 0x7f }) return null
        return decoded
    }
}
