# ZFL Hierarchy Enrichment — Implementation Plan

## Context

The LSP sits between the grammar (dsl-kotlin parser) and the IDE plugin.

- **Grammar / dsl-kotlin** — parses ZFL text. Currently exposes `system → service` only. Commands are not declared under services; they are in `when` blocks inside flows.
- **LSP (`ZflHierarchyBuilder`)** — enriches the raw model into a navigable semantic hierarchy with correct `kind`, `id`, and source ranges. Derives `service → command` by cross-referencing `when` blocks. Attaches ZDL file links from `@zdl` annotations.
- **IDE plugin** — consumes hierarchy nodes and decides how to render labels, icons, and aggregates. The LSP does not format display strings like `service ServiceName for (Agg1,Agg2)`.

### Core challenge

`service → command` is not declared in `systems {}`. It must be derived in a second pass over all `when` blocks across all flows. The current single-pass nested-lambda builder cannot do this.

---

## File structure after implementation

```
zfl/
  ZflModelAccess.kt                  existing, unchanged
  ZflCrossReferenceContributor.kt    existing, unchanged
  ZflParserAdapter.kt                existing, unchanged
  ZflSemanticModel.kt                existing, unchanged
  ZflLanguageModule.kt               existing, unchanged
  ZflEnrichedModel.kt                NEW  — pure data classes, no logic
  ZflModelEnricher.kt                NEW  — raw model → enriched model
  ZflHierarchyBuilder.kt             REFACTOR — enriched model → HierarchyNodes
```

---

## Step 1 — `ZflEnrichedModel.kt` (new)

Pure data classes. No `Map<String, Any?>`, no parsing logic, no HierarchyNode. This is the stable intermediate representation.

Separating data from transformation means:
- parser output changes → only enricher needs updating
- hierarchy shape changes → only builder needs updating
- new consumers (diagnostics, completion) can reuse `EnrichedZflModel` without re-parsing

```kotlin
data class EnrichedZflModel(
    val systems: List<EnrichedSystem>,
    val flows: List<EnrichedFlow>
)

data class EnrichedSystem(
    val name: String,
    val zdlUri: String?,                      // from @zdl annotation — for relatedResources
    val services: List<EnrichedService>,
    val sourcePath: String                    // "systems.<name>"
)

data class EnrichedService(
    val systemName: String,
    val name: String,
    val aggregates: List<String>,             // empty if parser does not expose them yet
    val commands: List<EnrichedCommand>,      // derived from when blocks
    val sourcePath: String                    // "systems.<system>.services.<name>"
)

data class EnrichedCommand(
    val name: String,
    val events: List<String>,
    val sourcePath: String                    // "flows.<flow>.whens[<i>].command"
)

data class EnrichedFlow(
    val name: String,
    val starts: List<String>,
    val whens: List<EnrichedWhen>,
    val end: EnrichedEnd,
    val sourcePath: String                    // "flows.<name>"
)

data class EnrichedWhen(
    val index: Int,
    val triggers: List<String>,
    val systemName: String?,                  // "Subscription" — split by parser
    val service: String?,                     // "Subscription.SubscriptionService" — full ref for display
    val command: String?,
    val events: List<String>,
    val sourcePath: String                    // "flows.<flow>.whens[<i>]"
)

data class EnrichedEnd(
    val outcomes: Map<String, List<String>>,  // outcomeName → [eventName, ...]
    val sourcePath: String                    // "flows.<flow>.end"
)
```

---

## Step 2 — `ZflModelEnricher.kt` (new)

Single public function, no state, pure transformation. Internal helpers stay private.

### Pipeline

```
raw model
  │
  ├─ readFlows()         → List<EnrichedFlow>   (starts, whens, end outcomes)
  ├─ buildCommandIndex() → Map<(systemName, serviceName), List<EnrichedCommand>>
  └─ readSystems()       → List<EnrichedSystem> (services + aggregates + merged commands + zdlUri)
```

Flows are read first because the command index is built from them before systems are finalized.

### Skeleton

```kotlin
internal class ZflModelEnricher {
    fun enrich(uri: String, model: Map<String, Any?>): EnrichedZflModel {
        val zdlUris    = declaredZdlUris(uri, model)      // reuse existing helper in ZflModelAccess
        val rawFlows   = model.mapAt("flows")
        val rawSystems = model.mapAt("systems")

        val flows   = readFlows(rawFlows)
        val index   = buildCommandIndex(flows)            // Map<Pair<String,String>, List<EnrichedCommand>>
        val systems = readSystems(rawSystems, zdlUris, index)

        return EnrichedZflModel(systems, flows)
    }
}
```

### `buildCommandIndex` — the key new logic

For each flow → each when that has both `systemName` and `command`:

- key: `Pair(systemName, serviceName)`
- value: `EnrichedCommand(name, events, sourcePath = "flows.$flowName.whens[$index].command")`
- one entry per `when` occurrence (deduplicate later only if needed)
- sourcePath enables precise navigation to the command declaration line in the flow

### `readSystems`

- Read service names and aggregates from the raw model
- Try `serviceMap["aggregates"].asZflList()` — if the parser does not expose them, returns empty list (graceful degradation; no crash)
- Look up derived commands from the index by `(systemName, serviceName)` key
- Attach `zdlUri` from the zdlUris map by system name

### `readFlows`

- Read starts: keys of `flow["starts"]` map
- Read whens: `triggers`, `system` (system name, already split by parser), `service` (full "System.Service" string), `command`, `events`
- Read end: `flow["end"].asZflMap()` — each key is an outcome name, value is a string or list; normalize to `List<String>` in both cases

### Known parser field names (confirmed from `ZflCrossReferenceContributor`)

| When block field | Raw key                 | Notes                                               |
|-----------------|-------------------------|-----------------------------------------------------|
| system name     | `whenModel["system"]`   | already split, e.g. `"Subscription"`               |
| service ref     | `whenModel["service"]`  | full ref, e.g. `"Subscription.SubscriptionService"` |
| command         | `whenModel["command"]`  | string                                              |
| events          | `whenModel["events"]`   | list of strings                                     |
| triggers        | `whenModel["triggers"]` | list of strings                                     |

---

## Step 3 — Refactor `ZflHierarchyBuilder.kt`

Replace the current nested-lambda structure with a clean two-method design mirroring `ZdlHierarchyBuilder`.

```kotlin
internal class ZflHierarchyBuilder(
    private val enricher: ZflModelEnricher = ZflModelEnricher()
) {
    fun build(uri: String, model: Map<String, Any?>): List<HierarchyNode> {
        val locations = model.locationTable()
        val enriched  = enricher.enrich(uri, model)
        return listOfNotNull(
            buildSystemsSection(uri, enriched.systems, locations),
            buildFlowsSection(uri, enriched.flows, locations)
        )
    }
}
```

### Systems section

```
system  (kind=system)
  └─ service  (kind=service, relatedResources → ZDL file if zdlUri set on parent system)
       └─ command  (kind=command, source → the `when` line in the flow)
```

- System node: `relatedResources` contains a `NavigationTarget` pointing to the ZDL file when `system.zdlUri` is set
- Service label: `service.name` — IDE formats aggregates
- Command label: `command.name` — IDE formats `emit EventA, EventB`
- Command source: `command.sourcePath` → navigates to the originating `when` line

### Flows section

```
flow  (kind=flow)
  ├─ start    (kind=start)             label = start name
  ├─ when     (kind=policy)            label = triggers joined by " and "
  │    ├─ service  (kind=service)      label = full "System.ServiceName" ref
  │    └─ event    (kind=event)        label = event name
  └─ end      (kind=end)
       └─ outcome  (kind=outcome)      label = outcome name
            └─ event  (kind=event)    label = event name
```

- When label: `triggers.joinToString(" and ")` — IDE decorates with command if desired
- Service child: present only when `when.service != null`; source = `flows.$flowName.whens[$i].service`
- Event children in when: source = `flows.$flowName.whens[$i].events.$eventName`
- No command child under when — command is navigable via system → service → command path
- End children: outcome nodes → event grandchildren

---

## Step 4 — Tests

### New: `ZflModelEnricherTest`

Tests the enricher in isolation — no `HierarchyNode` involved. Fast, pure unit tests.

| # | What is tested |
|---|----------------|
| 1 | Services include aggregates when the parser provides them |
| 2 | Services with no aggregates have an empty aggregates list (no crash) |
| 3 | Command index groups commands correctly by `(systemName, serviceName)` |
| 4 | Each command carries the correct `sourcePath` pointing to the originating `when` |
| 5 | Multi-event commands list all events |
| 6 | Multiple `when` blocks for the same service produce multiple command entries |
| 7 | `when` blocks with no command or no service are skipped in the command index |
| 8 | End outcomes are parsed correctly for single-event and multi-event outcomes |
| 9 | `@zdl` URI is attached to the correct system |
| 10 | Flows with no `end` block produce an empty `EnrichedEnd` |

### Update: `ZflLanguageModuleTest`

Update the existing test `hierarchyBuildsFlowPolicyCommandAndEventNesting`:

- Remove assertion for `kind=="command"` child under policy node
- Assert service child present under policy with `kind=="service"`
- Assert event children present under policy with `kind=="event"`
- Assert system node has `relatedResources` pointing to ZDL file

New assertions to add:

| # | What is tested |
|---|----------------|
| 1 | System node has `relatedResources` with ZDL file link |
| 2 | Service node under system has command children derived from flows |
| 3 | Command node source navigates to the correct `when` line |
| 4 | End node has outcome children |
| 5 | Outcome children have event grandchildren |
| 6 | Start node has `kind=="start"` |
| 7 | When node has `kind=="policy"` and label is the trigger expression |
| 8 | When node with multiple triggers joins them with " and " |

---

## Implementation order

1. `ZflEnrichedModel.kt` — define data classes; nothing can break
2. `ZflModelEnricher.kt` — implement + `ZflModelEnricherTest`
3. `ZflHierarchyBuilder.kt` — refactor to consume enriched model, fix all node types
4. `ZflLanguageModuleTest` — update broken test, add new assertions
5. End-to-end validation against `subscriptions.zfl`

---

## Open questions

| Question | Resolution |
|----------|------------|
| Does the parser expose `aggregates` on service maps? | Try `serviceMap["aggregates"].asZflList()` — treat empty as graceful degradation |
| Is `end` outcome value a string or a list? | Normalize both in `readFlows` — check string first, fallback to list |
| Should identical service+command+event combinations be deduplicated? | No for now — one command child per `when` occurrence. Deduplicate later if IDE requests it |
