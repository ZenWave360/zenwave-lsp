package io.zenwave360.lsp.core.index

import io.zenwave360.lsp.core.platform.LoaderDocumentReader

/** One entry of a directory. Symbolic links are reported as neither directories nor followed. */
class DirectoryEntry(val name: String, val isDirectory: Boolean)

/** The platform's way to list a local directory; the one thing the file-set scan needs from the file system. */
fun interface WorkspaceDirectories {
    /** The entries of the directory at [path], or null when it cannot be read or this platform has no file system. */
    suspend fun entries(path: String): List<DirectoryEntry>?
}

/** The local directories of this platform: the JVM file system, Node's `fs`, nothing in a browser. */
expect fun platformWorkspaceDirectories(): WorkspaceDirectories

/** Gives the event loop (JS) or the other threads (JVM) a turn. Resumes at once when nothing is waiting. */
expect suspend fun yieldToEventLoop()

/**
 * Which local files the background index looks at (NAV-Q11): files of a supported language under a workspace folder,
 * skipping dot-directories (`.git`, `.claude`, `.zenwave`, ...) and `node_modules`, `target` and `build`.
 * The manifest is not the source of the set.
 */
object WorkspaceFileRules {
    private val skippedDirectories = setOf("node_modules", "target", "build")

    fun skipsDirectory(name: String): Boolean = name.startsWith(".") || name in skippedDirectories

    fun hasSupportedExtension(name: String, extensions: Set<String>): Boolean =
        extensions.any { name.endsWith(it, ignoreCase = true) }

    /** The path of [uri] below [folderUri] when it is a `file:` URI inside it, else null. */
    fun relativePath(folderUri: String, uri: String): String? {
        val prefix = folderUri.trimEnd('/') + "/"
        if (!uri.startsWith("file:") || !uri.startsWith(prefix) || uri.length == prefix.length) return null
        return uri.substring(prefix.length)
    }

    /** True when [uri] is a supported file under [folderUri] that no skipped directory contains. */
    fun accepts(folderUri: String, uri: String, extensions: Set<String>): Boolean {
        val relative = relativePath(folderUri, uri) ?: return false
        val segments = relative.substringBefore('#').substringBefore('?').split('/')
        return segments.dropLast(1).none(::skipsDirectory) && hasSupportedExtension(segments.last(), extensions)
    }

    /** A path segment as `encodeURIComponent` writes it, which is how VS Code writes the URIs it sends. */
    fun encodeSegment(name: String): String = buildString {
        for (byte in name.encodeToByteArray()) {
            val c = byte.toInt() and 0xFF
            if (c < 0x80 && (c.toChar().isLetterOrDigit() || c.toChar() in "-_.!~*'()")) append(c.toChar())
            else append('%').append(HEX[c shr 4]).append(HEX[c and 0xF])
        }
    }

    private const val HEX = "0123456789ABCDEF"
}

/** Lists the supported files under a workspace folder, giving way through [tick] between directories. */
internal class WorkspaceScanner(
    private val directories: WorkspaceDirectories,
    private val extensions: Set<String>,
) {
    suspend fun scan(folderUri: String, tick: suspend () -> Unit): List<String> {
        val root = LoaderDocumentReader.loaderLocation(folderUri).trimEnd('/', '\\')
        val found = mutableListOf<String>()
        val pending = ArrayDeque<Pair<String, String>>()
        pending.add(root to folderUri.trimEnd('/'))
        while (pending.isNotEmpty()) {
            val (path, uri) = pending.removeFirst()
            tick()
            val entries = directories.entries(path)?.sortedBy { it.name } ?: continue
            for (entry in entries) {
                val childPath = "$path/${entry.name}"
                val childUri = "$uri/${WorkspaceFileRules.encodeSegment(entry.name)}"
                if (entry.isDirectory) {
                    if (!WorkspaceFileRules.skipsDirectory(entry.name)) pending.add(childPath to childUri)
                } else if (WorkspaceFileRules.hasSupportedExtension(entry.name, extensions)) {
                    found += childUri
                }
            }
        }
        return found
    }
}
