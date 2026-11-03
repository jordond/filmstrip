@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

// The browser globals a picture is fetched and decoded through, declared once for both targets and
// for every module that reads a picture in a browser.

/**
 * A decoded picture, which a bounds read closes at once and an overlay uploads as a texture.
 */
@InternalFilmstripApi
public external interface ImageBitmap : JsAny {
  public val width: Int

  public val height: Int

  public fun close()
}

/**
 * Decodes [image], a blob or another bitmap, into an [ImageBitmap] with the decode [options] asked for.
 */
@InternalFilmstripApi
public external fun createImageBitmap(
  image: JsAny,
  options: JsAny,
): Promise<ImageBitmap>

/**
 * Enough of a fetch response to pull a blob out of it.
 */
@InternalFilmstripApi
public external interface FetchResponse : JsAny {
  public val ok: Boolean

  public fun blob(): Promise<Blob>
}

/**
 * Fetches [input], a URL or an object URL minted from a picked file.
 */
@InternalFilmstripApi
public external fun fetch(input: String): Promise<FetchResponse>
