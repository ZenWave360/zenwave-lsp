package io.zenwave360.lsp.core

// The JS test compilation is also bundled for the browser run (jsBrowserTest), so Node modules are
// resolved at call time through process.getBuiltinModule rather than a require() the bundler would follow.
// Only the Node test run (jsNodeTest) reads fixtures.
internal fun nodeBuiltinModule(name: String): dynamic =
    js("globalThis.process.getBuiltinModule(name)")

actual fun readTestFile(path: String): String =
    nodeBuiltinModule("node:fs").readFileSync(path, "utf8") as String
