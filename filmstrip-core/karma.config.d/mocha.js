// Mocha's default 2000ms per-test timeout is well below runTest's own 60s timeout, so let
// runTest be the one that fails a slow test instead of Mocha cutting it off first.
// A long synchronous test also holds the browser past Karma's default ping and capture windows,
// so these give it the same ones the player uses before Karma drops the browser as disconnected.
config.set({
  browserNoActivityTimeout: 600000,
  browserDisconnectTimeout: 60000,
  captureTimeout: 120000,
  client: {
    mocha: {
      timeout: 120000,
    },
  },
});
