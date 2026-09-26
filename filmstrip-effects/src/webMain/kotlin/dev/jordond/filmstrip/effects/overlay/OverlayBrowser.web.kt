@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

// The browser globals an overlay is rasterised and decoded through. They are the same globals with
// the same shapes on js and wasmJs, so every one is declared once here.

/**
 * A canvas no document ever sees, which text is drawn into.
 */
internal external class OffscreenCanvas(
  width: Int,
  height: Int,
) : JsAny {
  val width: Int

  val height: Int

  fun getContext(contextId: String): OverlayContext2D?
}

/**
 * The part of a two-dimensional context that measures and draws text and fills a plate.
 */
internal external interface OverlayContext2D : JsAny {
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
}

/**
 * What `measureText` answers for one run of text in the context's current font.
 */
internal external interface TextMetrics : JsAny {
  val width: Double

  val actualBoundingBoxAscent: Double

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

  fun blob(): Promise<JsAny>
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
