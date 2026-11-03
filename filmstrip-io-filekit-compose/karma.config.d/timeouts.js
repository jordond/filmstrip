// A long synchronous test holds the browser past Karma's default ping and capture windows. These
// give it the same ones the player uses before Karma drops the browser as disconnected.
config.set({
  browserNoActivityTimeout: 600000,
  browserDisconnectTimeout: 60000,
  captureTimeout: 120000,
});
