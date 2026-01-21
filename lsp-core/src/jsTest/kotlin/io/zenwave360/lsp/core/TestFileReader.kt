package io.zenwave360.lsp.core

actual fun readTestFile(path: String): String =
    js("require('fs').readFileSync(path, 'utf8')") as String
