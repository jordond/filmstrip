package dev.jordond.filmstrip.webcodecs

/**
 * Makes the browser's `fetch` answer [uri] with what [dataUri] holds, replacing any answer it gave before.
 *
 * Every other URI still goes to the real `fetch`.
 */
internal expect fun routeFetch(
  uri: String,
  dataUri: String,
)

/**
 * How many times `fetch` was called for a [uri] given to [routeFetch].
 */
internal expect fun fetchesOf(uri: String): Int

/**
 * Makes the next `fetch` of a routed [uri] answer [millis] late, with what the route held when it was called.
 */
internal expect fun delayNextFetch(
  uri: String,
  millis: Int,
)

/**
 * Makes `fetch` answer [uri] with a 404, the way a server answers for a picture it no longer has.
 */
internal expect fun dropFetch(uri: String)

/**
 * The `cache` option the last `fetch` of a routed [uri] asked for, or an empty string when it asked for none.
 */
internal expect fun fetchCacheOf(uri: String): String
