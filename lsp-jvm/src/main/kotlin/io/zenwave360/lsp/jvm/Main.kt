package io.zenwave360.lsp.jvm

import io.zenwave360.lsp.core.avro.AvroLanguageModule
import io.zenwave360.lsp.core.manifest.ArchitectureManifestLanguageModule
import io.zenwave360.lsp.core.platform.InMemoryDocumentSessionStore
import io.zenwave360.lsp.core.platform.ZenwaveLanguageServer
import io.zenwave360.lsp.core.spec.asyncapi.AsyncApiLanguageModule
import io.zenwave360.lsp.core.spec.openapi.OpenApiLanguageModule
import io.zenwave360.lsp.core.xref.InMemoryCrossReferenceIndex
import io.zenwave360.lsp.core.zdl.ZdlLanguageModule
import io.zenwave360.lsp.core.zfl.ZflLanguageModule
import org.eclipse.lsp4j.launch.LSPLauncher

fun defaultCoreLanguageServer(): ZenwaveLanguageServer =
    ZenwaveLanguageServer(
        modules = listOf(
            ArchitectureManifestLanguageModule(),
            ZdlLanguageModule(),
            AsyncApiLanguageModule(),
            OpenApiLanguageModule(),
            AvroLanguageModule(),
            ZflLanguageModule()
        ),
        sessionStore = InMemoryDocumentSessionStore(),
        crossReferenceIndex = InMemoryCrossReferenceIndex()
    )

fun main() {
    val lspServer = ZenwaveLspServer(defaultCoreLanguageServer())
    val launcher = LSPLauncher.createServerLauncher(lspServer, System.`in`, System.out)
    lspServer.connect(launcher.remoteProxy)
    launcher.startListening()
}
