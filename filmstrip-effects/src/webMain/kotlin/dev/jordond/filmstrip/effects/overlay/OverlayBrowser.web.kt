@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.media.ArrayBuffer
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

// The browser globals an overlay is rasterised and decoded through. They are the same globals with
// the same shapes on js and wasmJs, so every one is declared once here.

/**
 * The id a canvas hands out its two-dimensional context under.
 */
internal const val CONTEXT_2D: String = "2d"

/**
 * A canvas no document ever sees, which text is drawn into and a decoded picture is read back through.
 *
 * Setting [width] or [height] clears the canvas and resizes its backing store, so a canvas set to nothing holds no
 * pixels.
 */
internal external class OffscreenCanvas(
  width: Int,
  height: Int,
) : JsAny {
  var width: Int

  var height: Int

  fun getContext(contextId: String): OverlayContext2D?
}

/**
 * The part of a two-dimensional context that measures and draws text, fills a rectangle, moves its origin and reads a
 * picture's codes back, along with the canvas it draws on.
 */
internal external interface OverlayContext2D : JsAny {
  val canvas: OffscreenCanvas

  var font: String

  var fillStyle: String

  var textBaseline: String

  fun measureText(text: String): TextMetrics

  fun fillText(
    text: String,
    x: Double,
    y: Double,
  )

  fun fillRect(
    x: Double,
    y: Double,
    width: Double,
    height: Double,
  )

  fun translate(
    x: Double,
    y: Double,
  )

  fun drawImage(
    image: JsAny,
    x: Double,
    y: Double,
  )

  fun getImageData(
    x: Int,
    y: Int,
    width: Int,
    height: Int,
  ): OverlayImageData
}

/**
 * The codes a canvas reads back, four bytes a texel, red, green, blue then alpha, row by row from the top left.
 */
internal external interface OverlayImageData : JsAny {
  val data: OverlayImageBytes
}

/**
 * The typed array behind [OverlayImageData], read whole through its buffer.
 */
internal external interface OverlayImageBytes : JsAny {
  val buffer: ArrayBuffer
}

/**
 * What `measureText` answers for one run of text in the context's current font.
 */
internal external interface TextMetrics : JsAny {
  val width: Double

  val actualBoundingBoxLeft: Double

  val actualBoundingBoxRight: Double

  val actualBoundingBoxAscent: Double

  val actualBoundingBoxDescent: Double

  val fontBoundingBoxAscent: Double

  val fontBoundingBoxDescent: Double
}

/**
 * A decoded image, ready to upload as a texture.
 */
internal external interface ImageBitmap : JsAny {
  val width: Int

  val height: Int
}

internal external fun createImageBitmap(
  image: JsAny,
  options: JsAny,
): Promise<ImageBitmap>

/**
 * Enough of a fetch response to pull a blob out of it.
 */
internal external interface OverlayResponse : JsAny {
  val ok: Boolean

  fun blob(): Promise<OverlayBlob>
}

/**
 * A fetched picture's bytes, which decode to a bitmap and read back whole.
 */
internal external interface OverlayBlob : JsAny {
  fun arrayBuffer(): Promise<ArrayBuffer>
}

internal external fun fetch(input: String): Promise<OverlayResponse>

/**
 * Parses an options object, which is the one way to build an object literal that reads the same on
 * both targets.
 */
internal external object JSON {
  fun parse(text: String): JsAny
}

/**
 * The fonts a page or a worker has registered, which `load` waits on before a face is drawn.
 */
internal external interface FontFaceSet : JsAny {
  fun load(font: String): Promise<JsAny?>
}

/**
 * A global that may carry a [FontFaceSet]. A worker carries one itself and a window through its
 * document.
 */
internal external interface FontScope : JsAny {
  val fonts: FontFaceSet?

  val document: FontScope?
}

internal external val globalThis: FontScope
