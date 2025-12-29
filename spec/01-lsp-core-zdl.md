🎯 What lsp-core represents now

lsp-core operates on one document model:

ZDL text
→ parser
→ SemanticModel
- data (Map-of-Maps)
- problems (with jsonPath + range)
- location index (line/char → jsonPath)


Everything else (completion, hover, go-to-definition) is queries over this model.

0️⃣ Core invariants (tell the agent first)
- The semantic model is the source of truth
- jsonPath is the stable node identifier
- There is NO tree traversal
- There is NO AST rewrite logic
- Everything is index-based

1️⃣ Define the core domain types (replace AST entirely)

📄 lsp-core/src/commonMain/kotlin/io/zenwave360/lsp/core/model/SemanticModel.kt

package io.zenwave360.lsp.core.model

interface SemanticModel {

    /** Raw semantic data (Map-of-Maps) */
    val data: Map<String, Any?>

    /** Validation / parse problems */
    val problems: List<Problem>

    /**
     * Given a cursor position, returns the jsonPath
     * of the semantic element under the cursor.
     *
     * Example:
     *   entities.Customer.fields.customerId.name
     */
    fun getLocation(line: Int, character: Int): String?
}


This matches exactly what your test demonstrates.

2️⃣ Problems (diagnostics) model

📄 model/Problem.kt

package io.zenwave360.lsp.core.model

data class Problem(
val jsonPath: String,
val range: Range,
val message: String,
val severity: Severity
)

enum class Severity {
ERROR, WARNING, INFO
}


Later:

Problem → LSP Diagnostic is trivial

No extra logic needed

3️⃣ Range & Position (unchanged, but reused everywhere)

📄 model/Position.kt

package io.zenwave360.lsp.core.model

data class Position(
val line: Int,
val character: Int
)


📄 model/Range.kt

package io.zenwave360.lsp.core.model

data class Range(
val start: Position,
val end: Position
)

4️⃣ Parser abstraction (thin, intentional)

📄 parser/ZdlParser.kt

package io.zenwave360.lsp.core.parser

import io.zenwave360.lsp.core.model.SemanticModel

interface ZdlParser {
fun parse(text: String): SemanticModel
}

Important instruction

The parser implementation may live outside lsp-core.
lsp-core depends only on this interface.

5️⃣ Language service API (simplified, model-centric)

📄 ZenwaveLanguageService.kt

package io.zenwave360.lsp.core

import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*

interface ZenwaveLanguageService {

    fun parse(text: String): SemanticModel

    fun diagnostics(text: String): List<Problem>

    fun completion(request: CompletionRequest): List<CompletionItem>

    fun hover(request: HoverRequest): Hover?

    fun definition(request: DefinitionRequest): Location?
}

6️⃣ Feature requests (all jsonPath-based)

📄 features/Completion.kt

package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Position

data class CompletionRequest(
val text: String,
val position: Position
)

data class CompletionItem(
val label: String,
val kind: String? = null
)


📄 features/Hover.kt

package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Position

data class HoverRequest(
val text: String,
val position: Position
)

data class Hover(
val contents: String
)


📄 features/Definition.kt

package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Position

data class DefinitionRequest(
val text: String,
val position: Position
)


📄 features/Location.kt

package io.zenwave360.lsp.core.features

import io.zenwave360.lsp.core.model.Range

data class Location(
val uri: String,
val range: Range
)

7️⃣ Concrete implementation (v0, minimal but real)

📄 impl/ZenwaveLanguageServiceImpl.kt

package io.zenwave360.lsp.core.impl

import io.zenwave360.lsp.core.*
import io.zenwave360.lsp.core.features.*
import io.zenwave360.lsp.core.model.*
import io.zenwave360.lsp.core.parser.*

class ZenwaveLanguageServiceImpl(
private val parser: ZdlParser
) : ZenwaveLanguageService {

    override fun parse(text: String): SemanticModel =
        parser.parse(text)

    override fun diagnostics(text: String): List<Problem> =
        parse(text).problems

    override fun completion(request: CompletionRequest): List<CompletionItem> {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return emptyList()

        // v0: no smart completion yet
        return emptyList()
    }

    override fun hover(request: HoverRequest): Hover? {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return null

        return Hover(contents = path)
    }

    override fun definition(request: DefinitionRequest): Location? {
        val model = parse(request.text)
        val path = model.getLocation(request.position.line, request.position.character)
            ?: return null

        // v0: definition == same location
        return null
    }
}


This already gives you:

diagnostics

hover (path-based)

stable behavior on JVM + JS

8️⃣ How your existing test maps to this model (important sanity check)

Your test:

location = model.getLocation(86, 12)
assertEquals("entities.Customer.fields.customerId.name", location)


This means:

You already solved the hardest LSP problem

Cursor → semantic node resolution is DONE

Everything else is sugar on top

9️⃣ What NOT to implement yet

Tell the agent explicitly:

❌ No AST
❌ No symbol scopes
❌ No rename
❌ No refactors
❌ No caching
❌ No concurrency

This is intentional.

🔚 Definition of “done” for this iteration

You are done when:

✅ lsp-core compiles on JVM + JS

✅ existing parser plugs in cleanly

✅ diagnostics are returned via problems

✅ getLocation(line, char) drives all features

❌ no editor / LSP protocol code exists here

Final guidance (important)

Your parser already speaks the language of LSPs.
The job of lsp-core is not to reinterpret it, only to query it consistently.

When this is in place, lsp-jvm and lsp-js will be thin, boring, and correct.
