package io.zenwave360.lsp.core.catalogue

import io.zenwave360.language.docs.PluginCatalogue
import io.zenwave360.lsp.core.platform.PlatformReadWriteLock

/**
 * The plugin catalogue the `.zw` preview describes plugins with (MP-R16): the last valid one a client sent, or the
 * server's own copy. The copy is parsed on first use, so the server starts without paying for it.
 */
class PluginCatalogues(private val own: () -> PluginCatalogue = ShippedPluginCatalogue::catalogue) {
    private val lock = PlatformReadWriteLock()
    private val shipped by lazy(own)
    private var replacement: PluginCatalogue? = null

    /** The catalogue the next preview uses. */
    fun current(): PluginCatalogue = lock.read { replacement } ?: shipped

    /**
     * Makes the catalogue in [json] the current one.
     *
     * @throws IllegalArgumentException when it is not a catalogue; the current one is kept
     */
    fun replace(json: String) {
        val parsed = PluginCatalogue.parse(json)
        lock.write { replacement = parsed }
    }
}
