package io.zenwave360.lsp.core.index

import io.zenwave360.lsp.core.CHECKOUT_FLOW_ZFL
import io.zenwave360.lsp.core.ORDERS_MODEL_ZDL
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.DocumentReader
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.WatchedFileChange
import io.zenwave360.lsp.core.platform.WatchedFileChangeKind
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.yield
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

private const val FOLDER = "file:///ws"
private const val ZDL = "$FOLDER/orders/model.zdl"
private const val FLOW = "$FOLDER/checkout.zfl"

/** A workspace on disk made of texts, listed and read through the seams the server uses. */
private class FakeDisk(files: Map<String, String> = emptyMap()) : WorkspaceDirectories, DocumentReader {
    val texts = files.toMutableMap()
    var reads = 0
    var onRead: () -> Unit = {}

    override suspend fun entries(path: String): List<DirectoryEntry>? {
        val prefix = path.trimEnd('/') + "/"
        val below = texts.keys.map { it.removePrefix("file://") }.filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        if (below.isEmpty()) return null
        return below.map { it.substringBefore('/') }.distinct().map { name ->
            DirectoryEntry(name, below.any { it.startsWith("$name/") })
        }
    }

    override suspend fun read(uri: String): String {
        reads += 1
        onRead()
        return texts[uri] ?: throw IllegalStateException("$uri does not exist")
    }
}

@OptIn(ExperimentalCoroutinesApi::class)
class BackgroundIndexTest {

    private fun TestScope.server(disk: FakeDisk, settings: BackgroundIndexSettings = testSettings()) = ZenwaveLanguageServer(
        modules = listOf(
            ArchitectureManifestLanguageModule(),
            ZdlLanguageModule(),
            AsyncApiLanguageModule(),
            OpenApiLanguageModule(),
            ZflLanguageModule(),
        ),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex(),
        documentReader = disk,
        workspaceDirectories = disk,
        indexScope = backgroundScope,
        indexSettings = settings,
    )

    /** The test scheduler is the only thread: yielding is `yield()`, so the pass stays inside the test's virtual time. */
    private fun testSettings(maxFiles: Int = 500, clock: () -> Double = { 0.0 }) =
        BackgroundIndexSettings(maxFiles = maxFiles, clock = clock, yielder = { yield() })

    private fun TestScope.settle(server: ZenwaveLanguageServer) {
        runCurrent()
        assertEquals(IndexState.READY, server.indexStatus().state)
    }

    private fun workspace() = FakeDisk(mapOf(ZDL to ORDERS_MODEL_ZDL, FLOW to CHECKOUT_FLOW_ZFL))

    /** The id of `createOrder` in the ZDL, which the flow uses. */
    private fun ZenwaveLanguageServer.createOrder(): Pair<String, String> {
        open(DocumentSnapshot(DocumentRef(ZDL, "zdl", 1), ORDERS_MODEL_ZDL))
        val line = ORDERS_MODEL_ZDL.lines().indexOfFirst { "createOrder" in it }
        val symbol = symbolAt(ZDL, Position(line, 6))!!
        return symbol.uri to symbol.semanticId
    }

    private fun ZenwaveLanguageServer.usesOf(target: Pair<String, String>) =
        reverseReferences(target.first, target.second).map { it.uri }

    @Test
    fun aClosedFileUsesAreFoundByReferences() = runTest {
        val server = server(workspace())
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        val uses = server.usesOf(server.createOrder())

        assertEquals(listOf(FLOW), uses)
        assertEquals(IndexState.READY, server.indexStatus().state)
        assertEquals(2, server.indexStatus().files)
    }

    @Test
    fun dotDirectoriesAndBuildDirectoriesAreSkipped() = runTest {
        val disk = workspace()
        listOf(".git", ".claude/skills", ".zenwave", "node_modules/pkg", "target", "build").forEach { dir ->
            disk.texts["$FOLDER/$dir/other.zfl"] = CHECKOUT_FLOW_ZFL
        }
        disk.texts["$FOLDER/notes.txt"] = "not a model"
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertEquals(listOf(FLOW), server.usesOf(server.createOrder()))
        assertEquals(2, server.indexStatus().candidates)
        assertTrue(WorkspaceFileRules.skipsDirectory(".agents") && WorkspaceFileRules.skipsDirectory(".grok"))
    }

    @Test
    fun beyondTheCapFilesAreIndexedWhenOpenedAndTheIndexIsPartial() = runTest {
        val disk = workspace()
        (1..4).forEach { disk.texts["$FOLDER/flow$it.zfl"] = CHECKOUT_FLOW_ZFL }
        val server = server(disk, testSettings(maxFiles = 3))
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        val status = server.indexStatus()
        // Three by the cap, and the ZDL they point at, which was waiting beyond it.
        assertEquals(4, status.files)
        assertEquals(6, status.candidates)
        assertTrue(status.partial)
        val target = server.createOrder()
        val before = server.usesOf(target).toSet()
        assertTrue(before.size < 5, "uses before: $before")

        val missing = listOf("$FOLDER/checkout.zfl", "$FOLDER/flow1.zfl", "$FOLDER/flow2.zfl", "$FOLDER/flow3.zfl", "$FOLDER/flow4.zfl")
            .first { it !in before }
        server.open(DocumentSnapshot(DocumentRef(missing, "zfl", 1), CHECKOUT_FLOW_ZFL))

        assertTrue(missing in server.usesOf(target))
    }

    @Test
    fun aWorkspaceWithinTheCapIsNotPartial() = runTest {
        val server = server(workspace())
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertEquals(false, server.indexStatus().partial)
    }

    @Test
    fun statusIsIdleUntilTheIndexStarts() = runTest {
        assertEquals(IndexState.IDLE, server(workspace()).indexStatus().state)
    }

    @Test
    fun theIndexingYieldsSoARequestIsAnsweredBeforeItFinishes() = runTest {
        val disk = workspace()
        (1..12).forEach { disk.texts["$FOLDER/flow$it.zfl"] = CHECKOUT_FLOW_ZFL }
        var now = 0.0
        disk.onRead = { now += 1.0 }
        var yields = 0
        val settings = BackgroundIndexSettings(clock = { now }, yielder = { yields += 1; yield() })
        val server = server(disk, settings)
        var filesWhenAnswered = -1

        server.startBackgroundIndex(listOf(FOLDER))
        launch { filesWhenAnswered = server.indexStatus().files }
        runCurrent()

        assertTrue(filesWhenAnswered in 0 until 14, "the request ran after $filesWhenAnswered files of 14")
        settle(server)
        // 4 ms slices at 1 ms a read: at most four files between two yields.
        assertTrue(yields >= 14 / 4, "yielded $yields times")
        assertEquals(14, server.indexStatus().files)
    }

    @Test
    fun closingADocumentKeepsItsReferences() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val target = server.createOrder()
        assertEquals(listOf(FLOW), server.usesOf(target))

        server.close(FLOW)
        runCurrent()

        assertEquals(listOf(FLOW), server.usesOf(target))
    }

    @Test
    fun closingADocumentKeepsItsReferencesWhenTheFileCannotBeRead() = runTest {
        val server = server(FakeDisk())
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val target = server.createOrder()

        server.close(FLOW)
        runCurrent()

        assertEquals(listOf(FLOW), server.usesOf(target))
    }

    @Test
    fun closingADocumentReadsTheFileAgainForWhatTheEditorDiscarded() = runTest {
        val disk = workspace()
        val server = server(disk)
        val edited = CHECKOUT_FLOW_ZFL.replace("createOrder", "cancelOrder")
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), edited))
        val target = server.createOrder()
        assertEquals(emptyList(), server.usesOf(target))

        server.close(FLOW)
        runCurrent()

        assertEquals(listOf(FLOW), server.usesOf(target))
    }

    @Test
    fun aDocumentThatIsOpenIsNotReplacedByTheFileOnDisk() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL.replace("createOrder", "cancelOrder")))
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertEquals(emptyList(), server.usesOf(server.createOrder()))
    }

    @Test
    fun aFileDeletedOnDiskLeavesTheIndex() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        val target = server.createOrder()
        assertEquals(listOf(FLOW), server.usesOf(target))

        disk.texts.remove(FLOW)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.DELETED)))

        assertEquals(emptyList(), server.usesOf(target))
        assertEquals(1, server.indexStatus().files)
    }

    @Test
    fun aFileCreatedOrChangedOnDiskIsIndexedAgain() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        val target = server.createOrder()
        val other = "$FOLDER/other.zfl"
        disk.texts[other] = CHECKOUT_FLOW_ZFL
        disk.texts["$FOLDER/.git/ignored.zfl"] = CHECKOUT_FLOW_ZFL

        server.workspaceFilesChanged(
            listOf(
                WatchedFileChange(other, WatchedFileChangeKind.CREATED),
                WatchedFileChange("$FOLDER/.git/ignored.zfl", WatchedFileChangeKind.CREATED),
            ),
        )
        settle(server)
        assertEquals(setOf(FLOW, other), server.usesOf(target).toSet())

        disk.texts[FLOW] = CHECKOUT_FLOW_ZFL.replace("createOrder", "cancelOrder")
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(listOf(other), server.usesOf(target))
    }
}
