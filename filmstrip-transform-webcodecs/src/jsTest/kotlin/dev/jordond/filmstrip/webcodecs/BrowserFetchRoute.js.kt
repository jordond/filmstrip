package dev.jordond.filmstrip.webcodecs

internal actual fun routeFetch(
  uri: String,
  dataUri: String,
) {
  js(
    """
    var state = globalThis.filmstripFetchRoutes;
    if (!state) {
      state = { routes: {}, counts: {}, delays: {}, caches: {} };
      globalThis.filmstripFetchRoutes = state;
      var real = globalThis.fetch.bind(globalThis);
      globalThis.fetch = function (input, init) {
        var route = typeof input === 'string' ? state.routes[input] : undefined;
        if (route === undefined) return real(input, init);
        state.counts[input] = (state.counts[input] || 0) + 1;
        state.caches[input] = (init && init.cache) || '';
        if (route === null) return Promise.resolve(new Response(null, { status: 404 }));
        var delay = state.delays[input];
        if (!delay) return real(route, init);
        delete state.delays[input];
        return new Promise(function (done) { setTimeout(done, delay); })
          .then(function () { return real(route, init); });
      };
    }
    state.routes[uri] = dataUri;
    """,
  )
}

internal actual fun fetchesOf(uri: String): Int =
  js("(globalThis.filmstripFetchRoutes && globalThis.filmstripFetchRoutes.counts[uri]) || 0") as Int

internal actual fun delayNextFetch(
  uri: String,
  millis: Int,
) {
  js("globalThis.filmstripFetchRoutes.delays[uri] = millis")
}

internal actual fun dropFetch(uri: String) {
  js("globalThis.filmstripFetchRoutes.routes[uri] = null")
}

internal actual fun fetchCacheOf(uri: String): String =
  js("(globalThis.filmstripFetchRoutes && globalThis.filmstripFetchRoutes.caches[uri]) || ''") as String
