package io.zenwave360.lsp.core.platform

actual class PlatformReadWriteLock {
    actual fun <T> read(block: () -> T): T =
        block()

    actual fun <T> write(block: () -> T): T =
        block()
}
