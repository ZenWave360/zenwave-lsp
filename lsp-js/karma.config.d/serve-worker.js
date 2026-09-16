// Serve the packaged Web Worker entry point (copied into the test resources by jsTestProcessResources) so
// WorkerWireTest can start it with `new Worker('/base/kotlin/lsp-js-package/dist/browser/zenwave-lsp-worker.js')`.
config.files.push({
    pattern: 'kotlin/lsp-js-package/**/*',
    included: false,
    served: true,
    watched: false,
});
