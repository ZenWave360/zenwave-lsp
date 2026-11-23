// elkjs probes for the optional 'web-worker' package (used only when ELK is given a workerUrl, which the
// EventFlow layout never does). Resolve it to an empty module so webpack bundles do not fail on it.
config.resolve = config.resolve || {};
config.resolve.fallback = Object.assign({}, config.resolve.fallback, { 'web-worker': false });
