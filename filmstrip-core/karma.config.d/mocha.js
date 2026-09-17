// Mocha's default 2000ms per-test timeout is well below runTest's own 60s timeout, so let
// runTest be the one that fails a slow test instead of Mocha cutting it off first.
config.set({
  client: {
    mocha: {
      timeout: 120000,
    },
  },
});
