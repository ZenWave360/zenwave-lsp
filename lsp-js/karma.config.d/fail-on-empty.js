// The browser test run is the check that lsp-js loads without a Node runtime.
// A run that executes no test proves nothing, so it must fail.
config.set({
    failOnEmptyTestSuite: true,
});
