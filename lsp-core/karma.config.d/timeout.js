// Parsing runs on the page's main thread; allow slower CI browsers time.
config.set({
    client: {
        ...(config.client || {}),
        mocha: {
            ...((config.client && config.client.mocha) || {}),
            timeout: 30000,
        },
    },
});
