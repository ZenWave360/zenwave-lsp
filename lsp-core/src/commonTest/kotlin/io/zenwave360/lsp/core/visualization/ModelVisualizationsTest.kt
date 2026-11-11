package io.zenwave360.lsp.core.visualization

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.contracts.DocumentRef
import io.zenwave360.lsp.core.contracts.DocumentSnapshot
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import io.zenwave360.language.eventflow.view.FlowLaneKind
import io.zenwave360.language.eventflow.view.FlowLanes
import kotlinx.coroutines.test.runTest
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

internal const val CHECKOUT_ZFL_URI = "file:///workspace/checkout.zfl"
internal val CHECKOUT_ZFL = """
    flow CheckoutFlow {
        @actor(Customer)
        start CheckoutStarted {
        }

        when CheckoutStarted do createOrder {
            service Orders.OrderService
            emits OrderCreated
        }

        when OrderCreated do authorizePayment {
            service Payments.PaymentService
            emits PaymentAuthorized
            emits PaymentDeclined
        }

        end {
            completed: PaymentAuthorized
            cancelled: PaymentDeclined
        }
    }
""".trimIndent()

internal val CHECKOUT_SYSTEMS_ZFL = """
    systems {
        Orders { service OrderService { commands: createOrder } }
        Payments { service PaymentService { commands: authorizePayment } }
    }

""".trimIndent() + "\n" + CHECKOUT_ZFL

internal const val ORDERS_ZDL_URI = "file:///workspace/orders.zdl"
internal const val ORDERS_ZW_URI = "file:///workspace/orders.zw"
internal val ORDERS_ZDL = """
    entity Customer {
        name String required
    }

    entity CustomerOrder {
        customer Customer
        total Integer
    }
""".trimIndent()

internal val DIAGRAMS_ZDL = """
    @aggregate
    @lifecycle(field: status, initial: DRAFT)
    entity Order {
        status String
    }

    entity Loose {
        name String
    }

    input PlaceOrderInput {
        name String
    }

    output PlaceOrderOutput {
        id String
    }

    event OrderPlaced {
        id String
    }

    service Orders for (Order) {
        @transition(to: DRAFT)
        placeOrder(PlaceOrderInput) PlaceOrderOutput withEvents OrderPlaced
    }
""".trimIndent()

/** A ZDL syntax error (a stray token) that dsl-kotlin's parser survives. */
internal val BROKEN_ZDL = "entity Customer {\n    name String required\n    ) ) )\n}\n"

class ModelVisualizationsTest {

    private fun server(vararg documents: Triple<String, String, String>): ZenwaveLanguageServer =
        ZenwaveLanguageServer(
            modules = listOf(
                ArchitectureManifestLanguageModule(),
                ZdlLanguageModule(),
                ZdlLanguageModule.zw(),
                AsyncApiLanguageModule(),
                OpenApiLanguageModule(),
                AvroLanguageModule(),
                ZflLanguageModule(),
            ),
            sessionStore = InMemoryDocumentSessionStore(),
            crossReferenceIndex = InMemoryCrossReferenceIndex(),
        ).also { server ->
            documents.forEach { (uri, languageId, text) -> server.open(DocumentSnapshot(DocumentRef(uri, languageId, 1), text)) }
        }

    @Test
    fun zflPreviewListsTheFlowchartThenOneSequencePerOutcome() {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))

        val preview = server.preview(CHECKOUT_ZFL_URI)

        assertEquals(
            listOf("flowchart", "sequence:completed:0", "sequence:cancelled:1"),
            preview.representations.map { it.id },
        )
        assertEquals("sequence:completed:0", preview.defaultRepresentationId)
        assertEquals("Flowchart", preview.representations[0].title)
        assertEquals("Sequence: from CheckoutStarted → PaymentAuthorized to completed", preview.representations[1].title)
        assertTrue(preview.representations.all { it.format == PreviewFormat.MERMAID })
        assertTrue(preview.representations[0].content.startsWith("flowchart"))
        assertTrue(preview.representations.drop(1).all { it.content.startsWith("sequenceDiagram") })
    }

    @Test
    fun zflPreviewFollowsTheSequenceRenderMode() {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))

        assertEquals(server.preview(CHECKOUT_ZFL_URI, "ALT_BLOCKS"), server.preview(CHECKOUT_ZFL_URI))
        assertTrue(server.preview(CHECKOUT_ZFL_URI, "SEPARATE_VARIANTS").representations.size >= 3)
        val failure = assertFailsWith<InvalidRequestParamsException> { server.preview(CHECKOUT_ZFL_URI, "SIDEWAYS") }
        assertTrue(failure.message!!.contains("SIDEWAYS"))
    }

    @Test
    fun zdlPreviewIsOneMarkdownDomainPage() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", ORDERS_ZDL))

        val preview = server.preview(ORDERS_ZDL_URI)

        assertEquals(1, preview.representations.size)
        val representation = preview.representations.single()
        assertEquals("domain", representation.id)
        assertEquals("Domain model", representation.title)
        assertEquals(PreviewFormat.MARKDOWN, representation.format)
        assertEquals("domain", preview.defaultRepresentationId)
        assertTrue(representation.content.contains("CustomerOrder"), representation.content)
        assertFalse(representation.content.contains("plantuml", ignoreCase = true))
    }

    @Test
    fun zdlDiagramsPreviewUsesGroupedIndividualRepresentations() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", DIAGRAMS_ZDL))

        val preview = server.preview(ORDERS_ZDL_URI, mode = "diagrams")

        assertEquals(
            listOf("service:Orders", "aggregate:Order", "state-machine:Order", "events", "command:Orders:placeOrder", "other-entities"),
            preview.representations.map { it.id },
        )
        assertEquals(
            listOf("Services", "Aggregates", "State Machines", "Events", "Commands", "Unused entities and enums"),
            preview.representations.map { it.group },
        )
        val unused = preview.representations.single { it.id == "other-entities" }
        assertEquals("Unused entities and enums", unused.title)
        assertTrue(unused.content.contains("Loose"), unused.content)
        assertFalse(unused.content.contains("Order {"), unused.content)
        assertEquals("service:Orders", preview.defaultRepresentationId)
        assertTrue(preview.representations.all { it.format == PreviewFormat.MERMAID && it.content.isNotBlank() })
        assertEquals("Orders.placeOrder", preview.representations.single { it.id == "command:Orders:placeOrder" }.title)
        assertFailsWith<InvalidRequestParamsException> { server.preview(ORDERS_ZDL_URI, mode = "page") }
    }

    @Test
    fun aZdlDescriptionDoesNotReachThePreviewAsScript() {
        val text = "/** <script>alert(1)</script> */\nentity Customer {\n    name String required\n}\n"
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", text))

        val preview = server.preview(ORDERS_ZDL_URI)
        val content = preview.representations.single().content
        val wire = VisualizationJson.preview(preview)

        assertFalse(content.contains("<script", ignoreCase = true), content)
        assertTrue(content.contains("&lt;script&gt;"), content)
        assertFalse(wire.contains("<script", ignoreCase = true), wire)
    }

    @Test
    fun aZdlWithSemanticProblemsIsStillPreviewed() {
        val text = "entity Customer {\n    name String required\n}\n\naggregate CustomerAggregate(MissingEntity) {\n}\n"
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", text))
        assertTrue(server.diagnostics(ORDERS_ZDL_URI).isNotEmpty())

        assertEquals("domain", server.preview(ORDERS_ZDL_URI).defaultRepresentationId)
    }

    @Test
    fun aZdlWithASyntaxErrorIsUnreadable() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", BROKEN_ZDL))

        val failure = assertFailsWith<DocumentRequestException> { server.preview(ORDERS_ZDL_URI) }

        assertEquals(DocumentFailureKind.DOCUMENT_UNREADABLE, failure.kind)
        assertTrue(failure.diagnostics.isNotEmpty())
        assertTrue(failure.diagnostics.all { it.code == "syntax" && it.uri == ORDERS_ZDL_URI })
    }

    @Test
    fun aZwDocumentIsPreviewedAsTheConfigurationPageWhetherIdentifiedByLanguageIdOrExtension() {
        val byLanguageId = server(Triple(ORDERS_ZW_URI, "zw", ORDERS_ZDL))
        val byExtension = server(Triple(ORDERS_ZW_URI, "plaintext", ORDERS_ZDL))

        listOf(byLanguageId, byExtension).forEach { server ->
            val preview = server.preview(ORDERS_ZW_URI)
            assertEquals("configuration", preview.defaultRepresentationId)
            val representation = preview.representations.single()
            assertEquals(PreviewFormat.MARKDOWN, representation.format)
        }
    }

    @Test
    fun aZwDocumentGetsTheZdlFeatures() {
        val broken = server(Triple(ORDERS_ZW_URI, "zw", BROKEN_ZDL))
        val semantic = "entity Customer {\n    name String required\n}\n\naggregate CustomerAggregate(MissingEntity) {\n}\n"
        val withProblems = server(Triple(ORDERS_ZW_URI, "zw", semantic))
        val zdlEquivalent = server(Triple(ORDERS_ZDL_URI, "zdl", semantic))

        assertTrue(broken.diagnostics(ORDERS_ZW_URI).isNotEmpty())
        assertEquals(
            zdlEquivalent.diagnostics(ORDERS_ZDL_URI).map { it.message to it.range },
            withProblems.diagnostics(ORDERS_ZW_URI).map { it.message to it.range },
        )
        assertEquals(zdlEquivalent.format(ORDERS_ZDL_URI), withProblems.format(ORDERS_ZW_URI))
    }

    @Test
    fun aZwWithASyntaxErrorIsUnreadable() {
        val server = server(Triple(ORDERS_ZW_URI, "zw", BROKEN_ZDL))

        val failure = assertFailsWith<DocumentRequestException> { server.preview(ORDERS_ZW_URI) }

        assertEquals(DocumentFailureKind.DOCUMENT_UNREADABLE, failure.kind)
        assertTrue(failure.diagnostics.all { it.code == "syntax" && it.uri == ORDERS_ZW_URI })
    }

    @Test
    fun aZflWithASyntaxErrorIsUnreadableForBothRequests() = runTest {
        val text = "flow Broken {\n    when {{ do\n"
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", text))

        val preview = assertFailsWith<DocumentRequestException> { server.preview(CHECKOUT_ZFL_URI) }
        val views = assertFailsWith<DocumentRequestException> { server.eventFlowViews(CHECKOUT_ZFL_URI) }

        listOf(preview, views).forEach { failure ->
            assertEquals(DocumentFailureKind.DOCUMENT_UNREADABLE, failure.kind)
            assertTrue(failure.diagnostics.isNotEmpty())
            assertTrue(failure.diagnostics.all { it.code == "syntax" || it.code == "parser" })
        }
    }

    @Test
    fun aDocumentThatIsNotOpenIsNotFound() = runTest {
        val server = server()

        assertEquals(
            DocumentFailureKind.DOCUMENT_NOT_FOUND,
            assertFailsWith<DocumentRequestException> { server.preview(ORDERS_ZDL_URI) }.kind,
        )
        assertEquals(
            DocumentFailureKind.DOCUMENT_NOT_FOUND,
            assertFailsWith<DocumentRequestException> { server.eventFlowViews(CHECKOUT_ZFL_URI) }.kind,
        )
    }

    @Test
    fun documentsARequestDoesNotCoverAreUnsupported() = runTest {
        val server = server(
            Triple(ORDERS_ZDL_URI, "zdl", ORDERS_ZDL),
            Triple("file:///workspace/notes.txt", "plaintext", "hello"),
        )

        assertEquals(
            DocumentFailureKind.UNSUPPORTED_DOCUMENT,
            assertFailsWith<DocumentRequestException> { server.eventFlowViews(ORDERS_ZDL_URI) }.kind,
        )
        assertEquals(
            DocumentFailureKind.UNSUPPORTED_DOCUMENT,
            assertFailsWith<DocumentRequestException> { server.preview("file:///workspace/notes.txt") }.kind,
        )
    }

    @Test
    fun eventFlowViewsAreLaidOutAndNameTheirSchema() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))

        val views = server.eventFlowViews(CHECKOUT_ZFL_URI)

        assertEquals("zfl.eventflow.view@1", views.flowGraph.schema)
        assertEquals("zfl.services.view@1", views.serviceGraph.schema)
        assertTrue(views.flowGraph.nodes.isNotEmpty())
        assertTrue(views.flowGraph.hasLayout(), "every flow node has a position")
        assertTrue(views.flowGraph.nodes.all { it.dimensions != null })
        assertNotNull(views.flowGraph.layout)
        assertTrue(views.serviceGraph.nodes.isNotEmpty())
        assertTrue(views.serviceGraph.nodes.all { it.position != null && it.dimensions != null })
        assertTrue(views.flowGraph.nodes.all { it.sourceRef.line > 0 }, "nodes point back at the source")
        assertEquals(listOf("sequence:completed:0", "sequence:cancelled:1"), views.variants.map { it.id })
        assertEquals(listOf("from CheckoutStarted → PaymentAuthorized to completed", "from CheckoutStarted → PaymentDeclined to cancelled"), views.variants.map { it.title })
    }

    @Test
    fun eventFlowViewsUseSavedPositionsWhenEveryNodeHasOne() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))
        val auto = server.eventFlowViews(CHECKOUT_ZFL_URI)
        assertEquals(null, auto.savedPositions)
        val saved = auto.flowGraph.nodes.associate { it.id to SavedPoint(it.position!!.x + 1000.0, it.position!!.y + 500.0) }

        val views = server.eventFlowViews(CHECKOUT_ZFL_URI, positions = saved)

        assertEquals(SavedPositions.Applied, views.savedPositions)
        assertEquals(saved, views.flowGraph.nodes.associate { it.id to SavedPoint(it.position!!.x, it.position!!.y) })
        assertEquals(auto.serviceGraph, views.serviceGraph)
        val json = Json.parseToJsonElement(VisualizationJson.eventFlowViews(views)).jsonObject
        assertEquals("true", json.getValue("savedPositions").jsonObject.getValue("applied").jsonPrimitive.content)
    }

    @Test
    fun eventFlowViewsKeepAutomaticLayoutAndNameTheNodesWithoutAPosition() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))
        val auto = server.eventFlowViews(CHECKOUT_ZFL_URI)
        val ids = auto.flowGraph.nodes.map { it.id }

        val missing = server.eventFlowViews(CHECKOUT_ZFL_URI, positions = ids.drop(1).associateWith { SavedPoint(1.0, 2.0) })
        assertEquals(SavedPositions.NotApplied("missing-nodes", listOf(ids.first())), missing.savedPositions)
        assertEquals(auto.flowGraph, missing.flowGraph)

        val invalid = server.eventFlowViews(CHECKOUT_ZFL_URI, positions = ids.associateWith { SavedPoint(Double.NaN, 2.0) })
        assertEquals("invalid", (invalid.savedPositions as SavedPositions.NotApplied).reason)
        val json = Json.parseToJsonElement(VisualizationJson.eventFlowViews(missing)).jsonObject.getValue("savedPositions").jsonObject
        assertEquals("missing-nodes", json.getValue("reason").jsonPrimitive.content)
        assertEquals(listOf(ids.first()), json.getValue("missing").jsonArray.map { it.jsonPrimitive.content })
    }

    @Test
    fun eventFlowViewsIgnoreSavedPositionsForAVariant() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))
        val variant = server.eventFlowViews(CHECKOUT_ZFL_URI, "sequence:completed:0")

        val withPositions = server.eventFlowViews(CHECKOUT_ZFL_URI, "sequence:completed:0", mapOf("nope" to SavedPoint(1.0, 1.0)))

        assertEquals(variant, withPositions)
        assertEquals(null, withPositions.savedPositions)
    }

    @Test
    fun eventFlowViewsCanSelectALaidOutVariantWithoutChangingTheServiceGraph() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))

        val full = server.eventFlowViews(CHECKOUT_ZFL_URI)
        val variant = server.eventFlowViews(CHECKOUT_ZFL_URI, "sequence:completed:0")

        assertTrue(variant.flowGraph.nodes.size < full.flowGraph.nodes.size)
        assertTrue(variant.flowGraph.hasLayout())
        assertEquals(full.serviceGraph, variant.serviceGraph)
        assertEquals(full.variants, variant.variants)
        assertEquals(
            "unknown variant: sequence:missing:0",
            assertFailsWith<InvalidRequestParamsException> {
                server.eventFlowViews(CHECKOUT_ZFL_URI, "sequence:missing:0")
            }.message,
        )
    }

    @Test
    fun eventFlowViewsWithoutLanesAreThePreviousOutput() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))
        val full = server.eventFlowViews(CHECKOUT_ZFL_URI)

        val none = server.eventFlowViews(CHECKOUT_ZFL_URI, lanes = "none")

        assertEquals(full.flowGraph, none.flowGraph)
        assertEquals(null, full.flowGraph.lanes)
        assertEquals(null, full.lanes)
        assertEquals(FlowLanes.NONE, none.lanes)
        assertFalse(VisualizationJson.eventFlowViews(full).contains("\"lanes\""))
        assertEquals("none", Json.parseToJsonElement(VisualizationJson.eventFlowViews(none)).jsonObject.getValue("lanes").jsonPrimitive.content)
    }

    @Test
    fun eventFlowViewsDrawSystemsAndServicesInBands() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_SYSTEMS_ZFL))

        val systems = server.eventFlowViews(CHECKOUT_ZFL_URI, lanes = "systems")
        val services = server.eventFlowViews(CHECKOUT_ZFL_URI, lanes = "services")

        assertEquals(FlowLanes.SYSTEMS, systems.lanes)
        assertEquals(FlowLanes.SERVICES, services.lanes)
        val systemBands = assertNotNull(systems.flowGraph.lanes)
        assertTrue(systemBands.size >= 2)
        assertTrue(systemBands.all { it.kind == FlowLaneKind.SYSTEM })
        assertTrue(systems.flowGraph.nodes.all { it.lane != null })
        val serviceBands = assertNotNull(services.flowGraph.lanes)
        assertTrue(serviceBands.all { it.kind == FlowLaneKind.SERVICE })
        assertEquals(systems.serviceGraph, services.serviceGraph)
        val json = Json.parseToJsonElement(VisualizationJson.eventFlowViews(services)).jsonObject
        assertEquals("services", json.getValue("lanes").jsonPrimitive.content)
        assertTrue(json.getValue("flowGraph").jsonObject.getValue("lanes").jsonArray.isNotEmpty())
        assertTrue(json.getValue("flowGraph").jsonObject.getValue("nodes").jsonArray.all { it.jsonObject.containsKey("lane") })
    }

    @Test
    fun eventFlowViewsDrawAVariantInLanes() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_SYSTEMS_ZFL))

        val variant = server.eventFlowViews(CHECKOUT_ZFL_URI, "sequence:completed:0", lanes = "services")

        assertTrue(assertNotNull(variant.flowGraph.lanes).isNotEmpty())
        assertTrue(variant.flowGraph.nodes.all { it.lane != null })
        assertEquals(null, variant.savedPositions)
        assertEquals(FlowLanes.SERVICES, variant.lanes)
    }

    @Test
    fun eventFlowViewsIgnoreSavedPositionsWithLanesAndSayTheyWereNotUsed() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_SYSTEMS_ZFL))
        val auto = server.eventFlowViews(CHECKOUT_ZFL_URI, lanes = "systems")
        val saved = auto.flowGraph.nodes.associate { it.id to SavedPoint(7.0, 9.0) }

        val views = server.eventFlowViews(CHECKOUT_ZFL_URI, positions = saved, lanes = "systems")

        assertEquals(auto.flowGraph, views.flowGraph)
        assertEquals(SavedPositions.NotApplied("lanes", emptyList()), views.savedPositions)
        val json = Json.parseToJsonElement(VisualizationJson.eventFlowViews(views)).jsonObject.getValue("savedPositions").jsonObject
        assertEquals("lanes", json.getValue("reason").jsonPrimitive.content)
        assertEquals("false", json.getValue("applied").jsonPrimitive.content)
        assertEquals(SavedPositions.Applied, server.eventFlowViews(CHECKOUT_ZFL_URI, positions = saved.mapValues { SavedPoint(1.0, 2.0) }, lanes = "none").savedPositions)
    }

    @Test
    fun eventFlowViewsRejectUnknownLanes() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))

        assertEquals("unknown lanes: teams", assertFailsWith<InvalidRequestParamsException> {
            server.eventFlowViews(CHECKOUT_ZFL_URI, lanes = "teams")
        }.message)
    }

    @Test
    fun eventFlowViewsJsonIsTheViewModelsWithoutNulls() = runTest {
        val server = server(Triple(CHECKOUT_ZFL_URI, "zfl", CHECKOUT_ZFL))
        val views = server.eventFlowViews(CHECKOUT_ZFL_URI)

        val json = Json.parseToJsonElement(VisualizationJson.eventFlowViews(views)).jsonObject

        assertEquals(setOf("flowGraph", "serviceGraph", "variants"), json.keys)
        assertEquals(listOf("sequence:completed:0", "sequence:cancelled:1"), json.getValue("variants").jsonArray.map { it.jsonObject.getValue("id").jsonPrimitive.content })
        val flowGraph = json.getValue("flowGraph").jsonObject
        assertEquals("zfl.eventflow.view@1", flowGraph.getValue("schema").jsonPrimitive.content)
        assertEquals("zfl.services.view@1", json.getValue("serviceGraph").jsonObject.getValue("schema").jsonPrimitive.content)
        val firstNode = flowGraph.getValue("nodes").jsonArray.first().jsonObject
        assertTrue(listOf("id", "type", "label", "sourceRef", "position", "dimensions").all { it in firstNode }, firstNode.toString())
        assertFalse(containsNull(json), "null properties are omitted")
        // Everything else is dsl-kotlin's own serialisation.
        val dslJson = Json.parseToJsonElement(views.flowGraph.toJson())
        assertEquals(withoutNulls(dslJson), flowGraph)
    }

    @Test
    fun previewJsonCarriesIdTitleFormatAndContent() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", ORDERS_ZDL))

        val json = Json.parseToJsonElement(VisualizationJson.preview(server.preview(ORDERS_ZDL_URI))).jsonObject

        assertEquals("domain", json.getValue("defaultRepresentationId").jsonPrimitive.content)
        val representation = json.getValue("representations").jsonArray.single().jsonObject
        assertEquals(setOf("id", "title", "format", "content"), representation.keys)
        assertEquals("MARKDOWN", representation.getValue("format").jsonPrimitive.content)
    }

    @Test
    fun previewJsonIncludesGroupOnlyForDiagramRepresentations() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", DIAGRAMS_ZDL))

        val json = Json.parseToJsonElement(VisualizationJson.preview(server.preview(ORDERS_ZDL_URI, mode = "diagrams"))).jsonObject

        assertEquals("Services", json.getValue("representations").jsonArray.first().jsonObject.getValue("group").jsonPrimitive.content)
        val docs = Json.parseToJsonElement(VisualizationJson.preview(server.preview(ORDERS_ZDL_URI))).jsonObject
        assertFalse("group" in docs.getValue("representations").jsonArray.single().jsonObject)
    }

    @Test
    fun failureDataNamesTheKindAndCarriesDiagnosticsWhenUnreadable() {
        val server = server(Triple(ORDERS_ZDL_URI, "zdl", BROKEN_ZDL))
        val unreadable = assertFailsWith<DocumentRequestException> { server.preview(ORDERS_ZDL_URI) }

        val data = Json.parseToJsonElement(VisualizationJson.failureData(unreadable)).jsonObject

        assertEquals("documentUnreadable", data.getValue("kind").jsonPrimitive.content)
        val diagnostic = data.getValue("diagnostics").jsonArray.first().jsonObject
        assertEquals(1, diagnostic.getValue("severity").jsonPrimitive.content.toInt())
        assertTrue("range" in diagnostic && "message" in diagnostic)

        val notFound = DocumentRequestException(DocumentFailureKind.DOCUMENT_NOT_FOUND, "gone")
        assertEquals("""{"kind":"documentNotFound"}""", VisualizationJson.failureData(notFound))
        val unsupported = DocumentRequestException(DocumentFailureKind.UNSUPPORTED_DOCUMENT, "no")
        assertEquals("""{"kind":"unsupportedDocument"}""", VisualizationJson.failureData(unsupported))
    }

    @Test
    fun customRequestsListTheVisualisationRequests() {
        assertEquals(
            listOf(
                "zenwave/hierarchy",
                "zenwave/forwardReferences",
                "zenwave/reverseReferences",
                "zenwave/organizeZflServices",
                "zenwave/eventFlowViews",
                "zenwave/preview",
                "zenwave/symbolAt",
                "zenwave/statResource",
                "zenwave/readResource",
            ),
            ZenwaveCustomRequests.ALL,
        )
    }

    private fun containsNull(element: JsonElement): Boolean =
        when (element) {
            is JsonNull -> true
            is JsonObject -> element.values.any(::containsNull)
            is JsonArray -> element.any(::containsNull)
            is JsonPrimitive -> false
        }

    private fun withoutNulls(element: JsonElement): JsonElement =
        when (element) {
            is JsonObject -> JsonObject(element.filterValues { it !is JsonNull }.mapValues { withoutNulls(it.value) })
            is JsonArray -> JsonArray(element.map(::withoutNulls))
            else -> element
        }
}
