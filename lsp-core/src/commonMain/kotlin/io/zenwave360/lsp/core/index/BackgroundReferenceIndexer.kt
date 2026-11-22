package io.zenwave360.lsp.core.index

import io.zenwave360.lsp.core.platform.PlatformReadWriteLock
import kotlin.time.TimeSource
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put

/**
 * How the background index shares the thread (NAV-Q11). [clock] reads milliseconds; the work gives way through
 * [yielder] once a slice has taken [sliceMillis] or more, so a request waits for one file at most.
 */
class BackgroundIndexSettings(
    val sliceMillis: Double = 4.0,
    /** Files indexed by the first pass; the rest are indexed when opened or referenced, and the index is partial. */
    val maxFiles: Int = 500,
    /** A longer file is not indexed. */
    val maxFileChars: Int = 1_000_000,
    val clock: () -> Double = TimeSource.Monotonic.markNow().let { start -> { start.elapsedNow().inWholeNanoseconds / 1_000_000.0 } },
    val yielder: suspend () -> Unit = ::yieldToEventLoop,
)

enum class IndexState { IDLE, INDEXING, READY }

/**
 * What `zenwave/indexStatus` reports: [files] closed or open files whose references are in the index, [candidates]
 * the files the scan found, and [partial] when some candidates are not indexed yet (beyond the cap of the first pass).
 */
data class IndexStatus(val state: IndexState, val files: Int, val candidates: Int, val partial: Boolean)

/** What the indexer asks of the language server. */
internal interface ClosedFileIndexing {
    fun isOpen(uri: String): Boolean

    /** The text of [uri], or null when it cannot be read. */
    suspend fun read(uri: String): String?

    /** Replaces the references [uri] contributes with those of [text]. Null when no module handles it; else the target documents. */
    fun indexClosed(uri: String, text: String): List<String>?

    fun forget(uri: String)
}

/**
 * NAV-R20. Keeps the references of the local workspace files in the index while they are closed: a first pass over
 * the files of the workspace folders, then each file again when it changes on disk or an editor closes it.
 *
 * Work runs in [scope] in slices of [BackgroundIndexSettings.sliceMillis], so a single thread (JS) answers requests in
 * between. An open document is never read from disk: its snapshot is the authority and it indexes itself.
 */
internal class BackgroundReferenceIndexer(
    private val scope: CoroutineScope,
    private val directories: WorkspaceDirectories,
    private val extensions: Set<String>,
    private val host: ClosedFileIndexing,
    private val settings: BackgroundIndexSettings,
) {
    private val lock = PlatformReadWriteLock()
    private var folders = emptyList<String>()
    private val queue = linkedSetOf<String>()
    private val pending = linkedSetOf<String>()
    private val indexed = mutableSetOf<String>()

    /** Files deleted on disk; a read that finished after the deletion must not bring their references back. */
    private val deleted = mutableSetOf<String>()
    private var candidates = 0
    private var started = false
    private var scanning = false
    private var working = false

    fun start(workspaceFolders: List<String>) {
        val roots = workspaceFolders.filter { it.startsWith("file:") }.distinct()
        val first = lock.write {
            if (started) return@write false
            started = true
            scanning = true
            folders = roots
            true
        }
        if (!first) return
        scope.launch {
            val budget = Slice()
            try {
                val scanner = WorkspaceScanner(directories, extensions)
                val found = roots.flatMap { scanner.scan(it) { budget.tick() } }.distinct()
                // Models first: a ZDL or ZFL says more about who uses what than a spec, and the cap cuts the tail.
                val ordered = found.sortedBy { if (isModel(it)) 0 else 1 }
                lock.write {
                    candidates += ordered.size
                    for (uri in ordered) {
                        if (host.isOpen(uri) || uri in indexed) continue
                        if (indexed.size + queue.size < settings.maxFiles) queue += uri else pending += uri
                    }
                }
            } finally {
                lock.write { scanning = false }
                launchWorker()
            }
        }
    }

    /** An editor opened [uri]: its snapshot indexes it from now on. */
    fun opened(uri: String) {
        lock.write {
            queue.remove(uri)
            deleted.remove(uri)
            if (pending.remove(uri) || uri.startsWith("file:")) indexed += uri
        }
    }

    /** An editor closed [uri]: its references stay, and the file on disk is read again for what the editor discarded. */
    fun closed(uri: String) {
        lock.write {
            deleted.remove(uri)
            queue += uri
        }
        launchWorker()
    }

    /** [uri] was created or changed on disk. Files outside the workspace folders or of no supported kind are ignored. */
    fun changedOnDisk(uri: String) {
        val accepted = lock.write {
            if (folders.none { WorkspaceFileRules.accepts(it, uri, extensions) }) return@write false
            deleted.remove(uri)
            if (uri !in indexed && indexed.size + queue.size >= settings.maxFiles) {
                if (pending.add(uri)) candidates += 1
            } else {
                queue += uri
            }
            true
        }
        if (accepted) launchWorker()
    }

    fun deletedOnDisk(uri: String) {
        lock.write {
            queue.remove(uri)
            if (pending.remove(uri)) candidates -= 1
            indexed.remove(uri)
            deleted += uri
            host.forget(uri)
        }
    }

    fun status(): IndexStatus = lock.read {
        val state = when {
            !started -> IndexState.IDLE
            scanning || working || queue.isNotEmpty() -> IndexState.INDEXING
            else -> IndexState.READY
        }
        IndexStatus(state, indexed.size, candidates, pending.isNotEmpty())
    }

    private fun isModel(uri: String) = listOf(".zdl", ".zfl", ".zw").any { uri.endsWith(it, ignoreCase = true) }

    private fun launchWorker() {
        val launch = lock.write {
            if (working || queue.isEmpty()) {
                false
            } else {
                working = true
                true
            }
        }
        if (launch) scope.launch { drain() }
    }

    private suspend fun drain() {
        val budget = Slice()
        while (true) {
            val uri = lock.write {
                val next = queue.firstOrNull()
                if (next == null) working = false else queue.remove(next)
                next
            } ?: return
            try {
                index(uri)
            } catch (e: CancellationException) {
                lock.write { working = false }
                throw e
            } catch (e: Throwable) {
                // One file that cannot be indexed must not stop the others; a JS error is no Exception.
                if (e is Error && e !is NotImplementedError) {
                    lock.write { working = false }
                    throw e
                }
            }
            budget.tick()
        }
    }

    private suspend fun index(uri: String) {
        if (host.isOpen(uri)) return
        val text = host.read(uri)?.takeIf { it.length <= settings.maxFileChars } ?: return
        // Under the lock, so a deletion reported meanwhile is not undone by this read.
        lock.write {
            if (uri in deleted) return@write
            val targets = host.indexClosed(uri, text)
            if (targets == null) {
                indexed.remove(uri)
            } else {
                indexed += uri
                // A file a closed one points at is wanted by someone: index it now instead of waiting to be opened.
                for (target in targets) if (pending.remove(target)) queue += target
            }
        }
    }

    private inner class Slice {
        private var start = settings.clock()

        suspend fun tick() {
            if (settings.clock() - start >= settings.sliceMillis) {
                settings.yielder()
                start = settings.clock()
            }
        }
    }
}

/** The JSON both servers send for `zenwave/indexStatus`: `{state, files, candidates, partial}`. */
object IndexStatusJson {
    fun status(status: IndexStatus): String =
        buildJsonObject {
            put("state", status.state.name.lowercase())
            put("files", status.files)
            put("candidates", status.candidates)
            put("partial", status.partial)
        }.toString()
}
