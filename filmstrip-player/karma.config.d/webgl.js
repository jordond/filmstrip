// Karma's stock ChromeHeadless has no GL at all, so getContext('webgl2') returns null and every
// export fails on a null context. These flags give it ANGLE over SwiftShader, a software
// rasteriser, which is enough to composite correctly but far too slow to time anything against.
//
// Set FILMSTRIP_CHROME_FLAGS to override, for a run against a real GPU.
// FILMSTRIP_CHROME_BASE=Chrome with FILMSTRIP_CHROME_FLAGS='--headless=new --use-angle=metal'
// runs the same tests against a real GPU, because karma-chrome-launcher's ChromeHeadless base
// passes --disable-gpu and nothing later can undo it.
config.set({
  customLaunchers: {
    FilmstripChrome: {
      base: process.env.FILMSTRIP_CHROME_BASE || 'ChromeHeadless',
      flags: (process.env.FILMSTRIP_CHROME_FLAGS || [
        '--use-gl=angle',
        '--use-angle=swiftshader',
        '--enable-unsafe-swiftshader',
        '--ignore-gpu-blocklist',
        '--no-sandbox',
      ].join(' ')).split(' ').filter(Boolean),
    },
  },
  browsers: ['FilmstripChrome'],
  browserNoActivityTimeout: 600000,
  browserDisconnectTimeout: 60000,
  captureTimeout: 120000,
  client: {
    mocha: {
      timeout: 300000,
    },
  },
});
