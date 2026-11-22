package io.zenwave360.lsp.core.platform

/**
 * True when [error] says the file is not there (JVM `NoSuchFileException`, `FileNotFoundException`, Node `ENOENT`, a
 * Windows "cannot find the file"), as opposed to a file that is there and cannot be read. The loaders do not share an
 * exception type, so this goes by what they report.
 */
fun isMissingFile(error: Throwable): Boolean {
    val name = error::class.simpleName.orEmpty()
    if (name.contains("NotFound", ignoreCase = true) || name.contains("NoSuchFile", ignoreCase = true)) return true
    val message = error.message.orEmpty()
    return listOf("ENOENT", "no such file", "cannot find the file", "cannot find the path", "does not exist", "not found")
        .any { message.contains(it, ignoreCase = true) }
}
