package io.zenwave360.lsp.core.index

import io.zenwave360.lsp.core.CHECKOUT_FLOW_ZFL
import io.zenwave360.lsp.core.ORDERS_MODEL_ZDL
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.contracts.Position
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.DocumentReader
import io.zenwave360.lsp.core.platform.FileUris
import io.zenwave360.lsp.core.platform.LoaderDocumentReader
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
    var onRead: (String) -> Unit = {}
    var onEntries: () -> Unit = {}
    val unreadable = mutableSetOf<String>()

    private fun paths() = texts.keys.map { LoaderDocumentReader.loaderLocation(it) }

    override suspend fun entries(path: String): List<DirectoryEntry>? {
        onEntries()
        val prefix = path.trimEnd('/') + "/"
        val below = paths().filter { it.startsWith(prefix) }.map { it.removePrefix(prefix) }
        if (below.isEmpty()) return null
        return below.map { it.substringBefore('/') }.distinct().map { name ->
            DirectoryEntry(name, below.any { it.startsWith("$name/") })
        }
    }

    override suspend fun read(uri: String): String {
        reads += 1
        onRead(uri)
        val wanted = FileUris.key(uri)
        if (unreadable.any { FileUris.key(it) == wanted }) throw IllegalStateException("$uri cannot be read")
        return texts.entries.firstOrNull { FileUris.key(it.key) == wanted }?.value
            ?: throw IllegalStateException("$uri does not exist")
    }

    fun remove(uri: String) {
        texts.keys.removeAll { FileUris.key(it) == FileUris.key(uri) }
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
    private fun ZenwaveLanguageServer.createOrder(zdl: String = ZDL): Pair<String, String> {
        open(DocumentSnapshot(DocumentRef(zdl, "zdl", 1), ORDERS_MODEL_ZDL))
        val line = ORDERS_MODEL_ZDL.lines().indexOfFirst { "createOrder" in it }
        val symbol = symbolAt(zdl, Position(line, 6))!!
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
        disk.onRead = { _ -> now += 1.0 }
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
    fun closingADocumentWhoseFileIsGoneForgetsItsReferencesWithoutMakingTheIndexPartial() = runTest {
        val server = server(FakeDisk())
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val target = server.createOrder()

        server.close(FLOW)
        runCurrent()

        assertEquals(emptyList(), server.usesOf(target))
        assertEquals(false, server.indexStatus().partial)
        assertEquals(0, server.indexStatus().skipped)
    }

    @Test
    fun closingADocumentWhoseFileCannotBeReadForgetsItsReferencesAndCountsIt() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))
        val target = server.createOrder()
        disk.unreadable += FLOW

        server.close(FLOW)
        runCurrent()

        assertEquals(emptyList(), server.usesOf(target))
        assertEquals(1, server.indexStatus().skipped)
        assertTrue(server.indexStatus().partial)
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

        disk.remove(FLOW)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.DELETED)))

        assertEquals(emptyList(), server.usesOf(target))
        assertEquals(1, server.indexStatus().files)
    }

    /** NAV-R9: the index follows the first listed `@zdl` location that is known, and switches when one appears or goes. */
    @Test
    fun aListedZdlIsIndexedAgainstTheFirstKnownLocation() = runTest {
        val missing = "$FOLDER/orders/missing.zdl"
        val listed = CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(\"orders/missing.zdl\", \"orders/model.zdl\")")
        val disk = FakeDisk(mapOf(ZDL to ORDERS_MODEL_ZDL, FLOW to listed))
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        fun usesOf(zdl: String) = server.reverseReferences(zdl, "$zdl#services.OrderService.methods.createOrder").map { it.uri }

        assertEquals(listOf(FLOW), usesOf(ZDL), "the second location exists, the first does not")
        assertEquals(emptyList(), usesOf(missing))

        disk.texts[missing] = ORDERS_MODEL_ZDL
        server.workspaceFilesChanged(listOf(WatchedFileChange(missing, WatchedFileChangeKind.CREATED)))
        settle(server)
        assertEquals(listOf(FLOW), usesOf(missing), "creating the first location makes it the winner")
        assertEquals(emptyList(), usesOf(ZDL))

        disk.remove(missing)
        server.workspaceFilesChanged(listOf(WatchedFileChange(missing, WatchedFileChangeKind.DELETED)))
        settle(server)
        assertEquals(listOf(FLOW), usesOf(ZDL), "deleting it switches back")
        assertEquals(emptyList(), usesOf(missing))
    }

    @Test
    fun aListedZdlOfAnOpenFlowFollowsTheOpenDocuments() = runTest {
        val missing = "$FOLDER/orders/missing.zdl"
        val listed = CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(\"orders/missing.zdl\", \"orders/model.zdl\")")
        val server = server(FakeDisk())
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), listed))
        server.open(DocumentSnapshot(DocumentRef(ZDL, "zdl", 1), ORDERS_MODEL_ZDL))
        fun usesOf(zdl: String) = server.reverseReferences(zdl, "$zdl#services.OrderService.methods.createOrder").map { it.uri }

        assertEquals(listOf(FLOW), usesOf(ZDL))

        server.open(DocumentSnapshot(DocumentRef(missing, "zdl", 1), ORDERS_MODEL_ZDL))
        assertEquals(listOf(FLOW), usesOf(missing))
        assertEquals(emptyList(), usesOf(ZDL))

        server.close(missing)
        runCurrent() // the closed file is read again and is not on disk
        assertEquals(listOf(FLOW), usesOf(ZDL))
        assertEquals(emptyList(), usesOf(missing))
    }

    @Test
    fun aFlowOpenBeforeTheScanFindsTheWinnerWhenTheScanEnds() = runTest {
        val missing = "$FOLDER/orders/missing.zdl"
        val listed = CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(\"orders/missing.zdl\", \"orders/model.zdl\")")
        val server = server(FakeDisk(mapOf(ZDL to ORDERS_MODEL_ZDL, missing to ORDERS_MODEL_ZDL)))
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), listed))
        fun usesOf(zdl: String) = server.reverseReferences(zdl, "$zdl#services.OrderService.methods.createOrder").map { it.uri }
        assertEquals(listOf(FLOW), usesOf(missing), "nothing is known yet: the first location stands")

        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        assertEquals(listOf(FLOW), usesOf(missing))

        val onlySecond = server(FakeDisk(mapOf(ZDL to ORDERS_MODEL_ZDL)))
        onlySecond.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), listed))
        assertEquals(listOf(FLOW), onlySecond.reverseReferences(missing, "$missing#services.OrderService.methods.createOrder").map { it.uri })
        onlySecond.startBackgroundIndex(listOf(FOLDER))
        settle(onlySecond)
        assertEquals(listOf(FLOW), onlySecond.reverseReferences(ZDL, "$ZDL#services.OrderService.methods.createOrder").map { it.uri })
        assertEquals(emptyList(), onlySecond.reverseReferences(missing, "$missing#services.OrderService.methods.createOrder").map { it.uri })
    }

    private fun listing(vararg locations: String) =
        CHECKOUT_FLOW_ZFL.replace("@zdl(\"orders/model.zdl\")", "@zdl(${locations.joinToString(", ") { "\"$it\"" }})")

    @Test
    fun aDeletedFlowThatListsItselfIsNotIndexedAgain() = runTest {
        val disk = FakeDisk(mapOf(FLOW to listing("checkout.zfl")))
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        assertEquals(1, server.indexStatus().files)

        disk.remove(FLOW)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.DELETED)))
        runCurrent()
        val reads = disk.reads
        runCurrent()

        assertEquals(reads, disk.reads, "nothing reads the deleted file again")
        assertEquals(0, server.indexStatus().files)
        assertEquals(IndexState.READY, server.indexStatus().state)
    }

    @Test
    fun aClosedFileRejectedBecauseItWasOpenedKeepsTheOpenDocumentsLinks() = runTest {
        val alt = "$FOLDER/orders/alt.zdl"
        val disk = FakeDisk(mapOf(ZDL to ORDERS_MODEL_ZDL, FLOW to listing("orders/missing.zdl", "orders/model.zdl")))
        val server = server(disk)
        // The editor opens the flow, with other links, while the server is reading it from disk.
        disk.onRead = { uri ->
            if (uri == FLOW) server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), listing("orders/alt.zdl", "orders/model.zdl")))
        }
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        fun usesOf(zdl: String) = server.reverseReferences(zdl, "$zdl#services.OrderService.methods.createOrder").map { it.uri }
        assertEquals(listOf(FLOW), usesOf(ZDL))

        server.open(DocumentSnapshot(DocumentRef(alt, "zdl", 1), ORDERS_MODEL_ZDL))

        assertEquals(listOf(FLOW), usesOf(alt), "the open flow lists alt.zdl: it follows it")
        assertEquals(emptyList(), usesOf(ZDL))
    }

    @Test
    fun aClosedFlowIsContributedAgainWhateverTheWorkspaceFolders() = runTest {
        val missing = "$FOLDER/orders/missing.zdl"
        val disk = FakeDisk(mapOf(FLOW to listing("orders/missing.zdl", "orders/model.zdl")))
        val server = server(disk) // no background index: the watcher would admit nothing
        server.open(DocumentSnapshot(DocumentRef(ZDL, "zdl", 1), ORDERS_MODEL_ZDL))
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), disk.texts.getValue(FLOW)))
        server.close(FLOW)
        runCurrent()
        fun usesOf(zdl: String) = server.reverseReferences(zdl, "$zdl#services.OrderService.methods.createOrder").map { it.uri }
        assertEquals(listOf(FLOW), usesOf(ZDL))

        server.open(DocumentSnapshot(DocumentRef(missing, "zdl", 1), ORDERS_MODEL_ZDL))
        runCurrent()

        assertEquals(listOf(FLOW), usesOf(missing))
        assertEquals(emptyList(), usesOf(ZDL))
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

    @Test
    fun theSpellingsOfAWindowsFileUriAreOneKey() {
        val spellings = listOf(
            "file:///C:/ws/a b.zdl", "file:///c:/ws/a%20b.zdl", "file:///c%3A/ws/a%20b.zdl",
            "file:///C%3a/ws/a b.zdl", "file://C:/ws/a b.zdl", "file://localhost/c:/ws/a%20b.zdl",
        )
        assertEquals(setOf("file:///c:/ws/a%20b.zdl"), spellings.map { FileUris.key(it) }.toSet())
        assertEquals("file:///tmp/A/x.zdl#entities.A", FileUris.key("file:///tmp/A/x.zdl#entities.A"))
        assertEquals("zenwave:/a/b.zdl", FileUris.key("zenwave:/a/b.zdl"))
        val store = InMemoryDocumentSessionStore()
        store.open(DocumentSnapshot(DocumentRef("file:///c%3A/ws/x.zdl", "zdl", 1), "entity A {}"))
        assertEquals("file:///c%3A/ws/x.zdl", store.get("file:///C:/ws/x.zdl")?.ref?.uri, "the snapshot keeps the client's spelling")
        store.close("file:///C:/ws/x.zdl")
        assertEquals(null, store.get("file:///c%3A/ws/x.zdl"))
    }

    @Test
    fun aFileOpenedUnderAnotherSpellingIsNotIndexedTwice() = runTest {
        val folder = "file:///C:/ws"
        val disk = FakeDisk(mapOf("$folder/orders/model.zdl" to ORDERS_MODEL_ZDL, "$folder/checkout.zfl" to CHECKOUT_FLOW_ZFL))
        val server = server(disk)
        server.startBackgroundIndex(listOf(folder))
        settle(server)
        val zdl = "file:///c%3A/ws/orders/model.zdl"
        val target = server.createOrder(zdl)
        assertEquals(listOf("$folder/checkout.zfl"), server.usesOf(target), "found by the disk spelling")

        val flow = "file:///c%3A/ws/checkout.zfl"
        server.open(DocumentSnapshot(DocumentRef(flow, "zfl", 1), CHECKOUT_FLOW_ZFL.replace("createOrder", "cancelOrder")))

        assertEquals(emptyList(), server.usesOf(target), "the open document replaced the disk version")
        assertEquals(2, server.indexStatus().files)
        server.workspaceFilesChanged(listOf(WatchedFileChange("$folder/checkout.zfl", WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(emptyList(), server.usesOf(target), "a change on disk does not replace an open document")
    }

    @Test
    fun aFileIndexedAfterWaitingBeyondTheCapIsNoLongerPending() = runTest {
        val disk = FakeDisk(mapOf("$FOLDER/a.zdl" to "entity A {}", "$FOLDER/b.zdl" to "entity B {}", "$FOLDER/c.zdl" to "entity C {}"))
        val server = server(disk, testSettings(maxFiles = 2))
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        assertEquals(true, server.indexStatus().partial)
        assertEquals(3, server.indexStatus().candidates)

        disk.remove("$FOLDER/a.zdl")
        server.workspaceFilesChanged(listOf(WatchedFileChange("$FOLDER/a.zdl", WatchedFileChangeKind.DELETED)))
        val changed = WatchedFileChange("$FOLDER/c.zdl", WatchedFileChangeKind.CHANGED)
        server.workspaceFilesChanged(listOf(changed, changed))
        settle(server)

        assertEquals(IndexStatus(IndexState.READY, 2, 2, false, 0), server.indexStatus())
    }

    @Test
    fun aFileThatCannotBeReadOrIsTooLongLosesItsReferencesAndIsCounted() = runTest {
        val disk = workspace()
        val settings = BackgroundIndexSettings(maxFileChars = CHECKOUT_FLOW_ZFL.length + 10, clock = { 0.0 }, yielder = { yield() })
        val server = server(disk, settings)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        val target = server.createOrder()
        assertEquals(listOf(FLOW), server.usesOf(target))

        disk.texts[FLOW] = CHECKOUT_FLOW_ZFL + " ".repeat(100)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(emptyList(), server.usesOf(target), "too long: forgotten")
        assertEquals(1, server.indexStatus().skipped)
        assertEquals(false, server.indexStatus().partial, "a file skipped for its size does not make the index partial")

        disk.texts[FLOW] = CHECKOUT_FLOW_ZFL
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(listOf(FLOW), server.usesOf(target))
        assertEquals(0, server.indexStatus().skipped)

        disk.unreadable += FLOW
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(emptyList(), server.usesOf(target), "unreadable: forgotten")
        assertEquals(1, server.indexStatus().skipped)
        assertTrue(server.indexStatus().partial, "a file that could not be read leaves the index partial")
    }

    @Test
    fun aFileDeletedWhileOpenLeavesTheIndexWhenItIsClosed() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        val target = server.createOrder()
        server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))

        disk.remove(FLOW)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.DELETED)))
        assertEquals(listOf(FLOW), server.usesOf(target), "still open")
        server.close(FLOW)
        runCurrent()

        assertEquals(emptyList(), server.usesOf(target))
        assertEquals(1, server.indexStatus().files)
    }

    @Test
    fun aFileOpenedWhileItWasBeingParsedKeepsTheEditorsReferences() = runTest {
        val disk = workspace()
        val server = server(disk)
        disk.onRead = { uri ->
            if (uri == FLOW) {
                server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL.replace("createOrder", "cancelOrder")))
            }
        }
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertEquals(emptyList(), server.usesOf(server.createOrder()), "the disk result was discarded")
    }

    @Test
    fun theDirectoryScanYieldsBeforeTheFirstFileIsRead() = runTest {
        val disk = FakeDisk((1..30).associate { "$FOLDER/dir/f$it.zdl" to "entity E$it {}" })
        var now = 0.0
        disk.onEntries = { now += 1.0 }
        var yields = 0
        var yieldsAtFirstRead = -1
        disk.onRead = { _ -> if (yieldsAtFirstRead < 0) yieldsAtFirstRead = yields }
        val server = server(disk, BackgroundIndexSettings(clock = { now.also { now += 0.5 } }, yielder = { yields += 1; yield() }))

        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertTrue(yieldsAtFirstRead >= 2, "the scan of 30 entries yielded $yieldsAtFirstRead times before reading")
    }

    @Test
    fun aFileUnreadableForTheIndexDoesNotEraseTheReferencesOfAnEditorThatOpenedIt() = runTest {
        val disk = workspace()
        val server = server(disk)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        val target = server.createOrder()
        disk.unreadable += FLOW
        disk.onRead = { uri ->
            if (uri == FLOW) server.open(DocumentSnapshot(DocumentRef(FLOW, "zfl", 1), CHECKOUT_FLOW_ZFL))
        }

        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)

        assertEquals(listOf(FLOW), server.usesOf(target), "the open document keeps its references")
        assertEquals(0, server.indexStatus().skipped)
    }

    @Test
    fun aFileHasOneSkipReasonAtATime() = runTest {
        val disk = workspace()
        val settings = BackgroundIndexSettings(maxFileChars = CHECKOUT_FLOW_ZFL.length + 10, clock = { 0.0 }, yielder = { yield() })
        val server = server(disk, settings)
        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)
        disk.texts[FLOW] = CHECKOUT_FLOW_ZFL + " ".repeat(100)
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)
        assertEquals(1, server.indexStatus().skipped)

        disk.unreadable += FLOW
        server.workspaceFilesChanged(listOf(WatchedFileChange(FLOW, WatchedFileChangeKind.CHANGED)))
        settle(server)

        val status = server.indexStatus()
        assertEquals(2, status.candidates)
        assertEquals(1, status.skipped, "unreadable replaced too long")
        assertTrue(status.partial)
    }

    @Test
    fun theScanAfterListingYieldsWhileItSortsAndQueuesTheCandidates() = runTest {
        val disk = FakeDisk((1..60).associate { "$FOLDER/dir/f$it.zdl" to "entity E$it {}" })
        var now = 0.0
        var yields = 0
        var yieldsAtFirstRead = -1
        disk.onRead = { _ -> if (yieldsAtFirstRead < 0) yieldsAtFirstRead = yields }
        // One millisecond per look at the clock: a yield every fourth tick. The listing and the dedup alone make 121 ticks.
        val server = server(disk, BackgroundIndexSettings(clock = { now += 1.0; now }, yielder = { yields += 1; yield() }))

        server.startBackgroundIndex(listOf(FOLDER))
        settle(server)

        assertTrue(yieldsAtFirstRead >= 44, "yielded $yieldsAtFirstRead times before the first read; the queueing ticks are the rest")
    }

    @Test
    fun aUncAuthorityIsNotTheLocalMachine() {
        assertTrue(FileUris.key("file://server/share/a.zdl") != FileUris.key("file:///server/share/a.zdl"))
        assertEquals(FileUris.key("file://server/share/a.zdl"), FileUris.key("file://SERVER/share/a.zdl"))
        assertEquals(FileUris.key("file:///server/share/a.zdl"), FileUris.key("file://localhost/server/share/a.zdl"))
        assertEquals(FileUris.key("file:///c:/a.zdl"), FileUris.key("file://C:/a.zdl"))
    }

    /** The indexer alone, with a host whose open documents the test controls, to put a deletion between a close and its notification. */
    private class FakeHost : ClosedFileIndexing {
        val open = mutableSetOf<String>()
        val forgotten = mutableListOf<String>()
        var read: ClosedRead = ClosedRead.Missing
        override fun isOpen(uri: String) = uri in open
        override suspend fun read(uri: String) = read
        override fun prepare(uri: String, text: String): PreparedClosedIndex? = null
        override fun forget(uri: String) { forgotten += uri }
    }

    private fun TestScope.indexer(host: FakeHost) = BackgroundReferenceIndexer(
        scope = backgroundScope,
        directories = WorkspaceDirectories { null },
        extensions = setOf(".zfl"),
        host = host,
        settings = testSettings(),
    )

    @Test
    fun aDeletionBetweenTheEditorClosingAndTheCloseReachingTheIndexRemovesTheFile() = runTest {
        val host = FakeHost().apply { read = ClosedRead.Failed }
        val indexer = indexer(host)
        host.open += FLOW
        indexer.opened(FLOW)

        host.open -= FLOW // the editor closed it
        indexer.deletedOnDisk(FLOW) // before the close reached the indexer
        indexer.closed(FLOW)
        runCurrent()

        assertTrue(FLOW in host.forgotten)
        assertEquals(0, indexer.status().files)
    }

    @Test
    fun aDeletionWhileOpenIsRemovedOnCloseEvenWhenTheFileCannotBeRead() = runTest {
        val host = FakeHost().apply { read = ClosedRead.Failed }
        val indexer = indexer(host)
        host.open += FLOW
        indexer.opened(FLOW)

        indexer.deletedOnDisk(FLOW)
        assertEquals(emptyList(), host.forgotten, "open: the editor's references stay")
        host.open -= FLOW
        indexer.closed(FLOW)
        runCurrent()

        assertEquals(listOf(FLOW), host.forgotten)
        assertEquals(0, indexer.status().skipped, "a deleted file is not a failure")
    }
}
