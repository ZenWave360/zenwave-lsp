package io.zenwave360.lsp.core.index

import io.zenwave360.lsp.core.platform.FileUris
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
 * What `zenwave/indexStatus` reports.
 *
 * [files] are the files whose references are in the index, [candidates] the files the scan and the file watcher found.
 * [skipped] counts candidates left out because they are too long, cannot be read or could not be processed.
 * [partial] is true while candidates beyond the cap of the first pass wait to be opened or referenced, and when a
 * candidate could not be read or processed; a file left out only for its size does not make the index partial, and
 * neither does a file no language module handles (a `package.json`).
 */
data class IndexStatus(val state: IndexState, val files: Int, val candidates: Int, val partial: Boolean, val skipped: Int = 0)

/** The result of reading a closed file, ready to be applied to the index in one short step. */
internal class PreparedClosedIndex(
    /** The documents the references point at. */
    val targets: List<String>,
    /** Puts the references into the index; false when an editor opened the file meanwhile and nothing was applied. */
    val apply: () -> Boolean,
)

/** The outcome of reading a closed file. */
internal sealed class ClosedRead {
    class Text(val text: String) : ClosedRead()

    /** The file is not there: never saved, or deleted. Not a problem of the index. */
    object Missing : ClosedRead()

    /** The file is there and could not be read (permission, I/O). */
    object Failed : ClosedRead()
}

/**
 * The files seen on disk, behind a lock of its own: the server asks whether a file is known while it holds the
 * server's lock, and the indexer takes the server's lock while it holds the indexer's, so asking must not need the latter.
 */
private class KnownFiles {
    private val lock = PlatformReadWriteLock()
    private val keys = mutableSetOf<String>()

    fun add(key: String) { lock.write { keys += key } }

    fun remove(key: String) { lock.write { keys -= key } }

    operator fun contains(key: String): Boolean = lock.read { key in keys }

    val size: Int get() = lock.read { keys.size }
}

/** What the indexer asks of the language server. */
internal interface ClosedFileIndexing {
    fun isOpen(uri: String): Boolean

    /** The text of [uri], or why there is none. */
    suspend fun read(uri: String): ClosedRead

    /**
     * Parses [text] and works out the references of [uri] without touching the index or taking the server's lock.
     * Null when no module handles it; throws when it cannot be processed.
     */
    fun prepare(uri: String, text: String): PreparedClosedIndex?

    fun forget(uri: String)

    /** The first pass over the workspace found every file it will find: what [BackgroundReferenceIndexer.knows] says is now complete. */
    fun scanned() {}

    /** A file that was known is not any more: it was read again and is gone. */
    fun gone(uri: String) {}
}

/**
 * NAV-R20. Keeps the references of the local workspace files in the index while they are closed: a first pass over
 * the files of the workspace folders, then each file again when it changes on disk or an editor closes it.
 *
 * Work runs in [scope] in slices of [BackgroundIndexSettings.sliceMillis], so a single thread (JS) answers requests in
 * between. An open document is never read from disk: its snapshot is the authority and it indexes itself.
 * Files are told apart by [FileUris.key], so `file:///C:/x` and `file:///c%3A/x` are one file.
 */
internal class BackgroundReferenceIndexer(
    private val scope: CoroutineScope,
    private val directories: WorkspaceDirectories,
    private val extensions: Set<String>,
    private val host: ClosedFileIndexing,
    private val settings: BackgroundIndexSettings,
) {
    private class Job(val uri: String)

    private enum class Skip { FAILED, OVERSIZED }

    private val lock = PlatformReadWriteLock()
    private var folders = emptyList<String>()
    private val queue = linkedMapOf<String, Job>()
    private val pending = linkedMapOf<String, String>()

    /** Every file the scan or the watcher found, by key. */
    private val known = KnownFiles()
    private val indexed = mutableSetOf<String>()

    /** Why a file is left out, if it is. A file has at most one current reason. */
    private val skips = mutableMapOf<String, Skip>()

    /** Files deleted on disk; an open one is dropped from the index when it is closed, and a read that finished after the deletion is discarded. */
    private val deleted = mutableSetOf<String>()
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
                // Models first: a ZDL or ZFL says more about who uses what than a spec, and the cap cuts the tail.
                val models = mutableListOf<String>()
                val others = mutableListOf<String>()
                val seen = mutableSetOf<String>()
                for (root in roots) {
                    for (uri in scanner.scan(root) { budget.tick() }) {
                        if (seen.add(FileUris.key(uri))) (if (isModel(uri)) models else others) += uri
                        budget.tick()
                    }
                }
                for (uri in models + others) {
                    lock.write {
                        val key = FileUris.key(uri)
                        known.add(key)
                        if (host.isOpen(uri) || key in indexed) return@write
                        if (indexed.size + queue.size < settings.maxFiles) queue[key] = Job(uri) else pending[key] = uri
                    }
                    budget.tick()
                }
            } finally {
                lock.write { scanning = false }
                host.scanned()
                launchWorker()
            }
        }
    }

    /** An editor opened [uri]: its snapshot indexes it from now on. */
    fun opened(uri: String) {
        val key = FileUris.key(uri)
        lock.write {
            queue.remove(key)
            deleted.remove(key)
            skips.remove(key)
            pending.remove(key)
            if (uri.startsWith("file:")) {
                indexed += key
                known.add(key)
            }
        }
    }

    /**
     * An editor closed [uri]: its references stay, and the file on disk is read again for what the editor discarded.
     * A file deleted while it was open leaves the index instead.
     */
    fun closed(uri: String) {
        val key = FileUris.key(uri)
        val gone = lock.write {
            if (key in deleted) {
                indexed.remove(key)
                known.remove(key)
                true
            } else {
                queue[key] = Job(uri)
                false
            }
        }
        if (gone) host.forget(uri) else launchWorker()
    }

    /** [uri] was created or changed on disk. Files outside the workspace folders or of no supported kind are ignored. */
    fun changedOnDisk(uri: String) {
        val key = FileUris.key(uri)
        val accepted = lock.write {
            if (folders.none { WorkspaceFileRules.accepts(it, uri, extensions) }) return@write false
            deleted.remove(key)
            known.add(key)
            if (key !in indexed && indexed.size + queue.size >= settings.maxFiles) pending[key] = uri else queue[key] = Job(uri)
            true
        }
        if (accepted) launchWorker()
    }

    fun deletedOnDisk(uri: String) {
        val key = FileUris.key(uri)
        // Decided under the lock that [closed] takes too, so a close racing with this deletion ends with the file removed.
        lock.write {
            deleted += key
            if (!host.isOpen(uri)) {
                queue.remove(key)
                pending.remove(key)
                indexed.remove(key)
                known.remove(key)
                skips.remove(key)
                host.forget(uri)
            }
        }
    }

    /** Whether the scan or the watcher has seen the local file [uri] on disk, and it has not been deleted since. */
    fun knows(uri: String): Boolean = FileUris.key(uri) in known

    fun status(): IndexStatus = lock.read {
        val state = when {
            !started -> IndexState.IDLE
            scanning || working || queue.isNotEmpty() -> IndexState.INDEXING
            else -> IndexState.READY
        }
        IndexStatus(state, indexed.size, known.size, pending.isNotEmpty() || Skip.FAILED in skips.values, skips.size)
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
            val job = lock.write {
                val nextKey = queue.keys.firstOrNull()
                if (nextKey == null) {
                    working = false
                    null
                } else {
                    nextKey to queue.remove(nextKey)!!
                }
            } ?: return
            try {
                index(job.first, job.second)
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

    /**
     * Takes a file out of the index because it cannot be indexed now, and records why ([why] null: the file is gone).
     * A file an editor has opened meanwhile is not touched: its snapshot is the authority.
     */
    private fun leave(key: String, uri: String, why: Skip?) {
        val removed = lock.write {
            if (key in deleted || host.isOpen(uri)) return@write false
            indexed.remove(key)
            skips.remove(key)
            if (why != null) skips[key] = why
            if (why == null) {
                known.remove(key)
                pending.remove(key)
            }
            host.forget(uri)
            why == null
        }
        if (removed) host.gone(uri)
    }

    private suspend fun index(key: String, job: Job) {
        val uri = job.uri
        if (host.isOpen(uri)) return
        val text = when (val read = host.read(uri)) {
            is ClosedRead.Text -> read.text
            ClosedRead.Missing -> return leave(key, uri, null)
            ClosedRead.Failed -> return leave(key, uri, Skip.FAILED)
        }
        if (text.length > settings.maxFileChars) return leave(key, uri, Skip.OVERSIZED)
        // Parsed outside every lock, so a request waits for this file's parse at most, not for the lock as well.
        val prepared = try {
            host.prepare(uri, text)
        } catch (e: CancellationException) {
            throw e
        } catch (e: Throwable) {
            if (e is Error && e !is NotImplementedError) throw e
            leave(key, uri, Skip.FAILED)
            return
        }
        // Applied under the lock, so a deletion reported meanwhile is not undone by this read.
        lock.write {
            if (key in deleted) return@write
            if (prepared == null) {
                indexed.remove(key)
                skips.remove(key)
                host.forget(uri)
                return@write
            }
            if (!prepared.apply()) return@write
            indexed += key
            skips.remove(key)
            pending.remove(key)
            // A file a closed one points at is wanted by someone: index it now instead of waiting to be opened.
            for (target in prepared.targets) {
                val targetKey = FileUris.key(target)
                val waiting = pending.remove(targetKey)
                if (waiting != null) queue[targetKey] = Job(waiting)
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

/** The JSON both servers send for `zenwave/indexStatus`: `{state, files, candidates, partial, skipped}`. */
object IndexStatusJson {
    fun status(status: IndexStatus): String =
        buildJsonObject {
            put("state", status.state.name.lowercase())
            put("files", status.files)
            put("candidates", status.candidates)
            put("partial", status.partial)
            put("skipped", status.skipped)
        }.toString()
}
