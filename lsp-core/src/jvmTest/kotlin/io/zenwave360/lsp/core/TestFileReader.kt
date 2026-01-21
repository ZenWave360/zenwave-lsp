package io.zenwave360.lsp.core

actual fun readTestFile(path: String): String =
    object {}.javaClass.getResource("/$path")!!.readText()
