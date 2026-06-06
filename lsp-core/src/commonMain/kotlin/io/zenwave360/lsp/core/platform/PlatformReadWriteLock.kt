package io.zenwave360.lsp.core.platform

expect class PlatformReadWriteLock() {
    fun <T> read(block: () -> T): T

    fun <T> write(block: () -> T): T
}
