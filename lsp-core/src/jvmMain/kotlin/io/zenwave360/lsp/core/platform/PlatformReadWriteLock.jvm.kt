package io.zenwave360.lsp.core.platform

import java.util.concurrent.locks.ReentrantReadWriteLock
import kotlin.concurrent.read
import kotlin.concurrent.write

actual class PlatformReadWriteLock {
    private val delegate = ReentrantReadWriteLock()

    actual fun <T> read(block: () -> T): T =
        delegate.read(block)

    actual fun <T> write(block: () -> T): T =
        delegate.write(block)
}
