@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ArrayBuffer
import dev.jordond.filmstrip.media.fetch
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsName
import kotlin.js.Promise

/**
 * The family `colr-glyphs.ttf` is registered under once [loadColorFont] has run.
 *
 * The font maps "A" to a square whose left half is red and right half blue, and "B" to one whose top half is green and
 * bottom half yellow, each half a COLRv0 layer. `colr-glyphs.py` beside it builds it.
 */
internal const val COLOR_FAMILY: String = "Filmstrip Colr"

/**
 * The palette colors of `colr-glyphs.ttf` as opaque ARGB, red, blue, green and yellow.
 */
internal val COLOR_PALETTE: List<Int> =
  listOf(0xFFFF0000.toInt(), 0xFF0000FF.toInt(), 0xFF009900.toInt(), 0xFFFFCC00.toInt())

/**
 * Fetches `colr-glyphs.ttf` from the test server and registers it on the page as [COLOR_FAMILY].
 *
 * Karma serves the test resources beside the test bundle, as `karma.config.d/resources.js` lists them. The face is
 * loaded before it is added, so a canvas draws in it at once. A second call registers nothing more.
 */
internal suspend fun loadColorFont() {
  if (colorFontLoaded) return
  val response = fetch(COLOR_FONT_URL).await()
  check(response.ok) { "the test server has no $COLOR_FONT_URL" }
  val bytes =
    response
      .blob()
      .await()
      .arrayBuffer()
      .await()
  val face = FontFace(COLOR_FAMILY, bytes)
  face.load().await()
  page.fonts.add(face)
  colorFontLoaded = true
}

private var colorFontLoaded = false

private const val COLOR_FONT_URL = "/base/kotlin/colr-glyphs.ttf"

private external class FontFace(
  family: String,
  source: ArrayBuffer,
) : JsAny {
  fun load(): Promise<FontFace>
}

private external interface PageDocument : JsAny {
  val fonts: PageFonts
}

private external interface PageFonts : JsAny {
  fun add(face: FontFace)
}

@JsName("document")
private external val page: PageDocument
