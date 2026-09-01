@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise

// The browser globals a still is encoded and delivered through, declared once for both targets.
// They are the same globals with the same shapes on js and wasmJs, so only the handful of things a
// target genuinely spells differently, building an object literal and moving bytes across the
// JavaScript heap boundary, lives behind expect/actual.

/**
 * The canvas the still is drawn on. Nothing is added to the document, so encoding never touches
 * layout.
 */
internal external class OffscreenCanvas(
  width: Int,
  height: Int,
) : JsAny {
  val width: Int

  val height: Int

  fun getContext(contextId: String): CanvasContext2D?

  fun convertToBlob(options: JsAny): Promise<Blob>
}

/**
 * The two-dimensional context, which is the only way to put a raw RGBA buffer on a canvas.
 */
internal external interface CanvasContext2D : JsAny {
  fun putImageData(
    data: ImageData,
    dx: Int,
    dy: Int,
  )

  fun drawImage(
    image: JsAny,
    dx: Int,
    dy: Int,
    dw: Int,
    dh: Int,
  )

  fun getImageData(
    sx: Int,
    sy: Int,
    sw: Int,
    sh: Int,
  ): ImageData
}

/**
 * A width, a height and a tightly packed RGBA buffer, which is the frame's own layout.
 */
internal external interface ImageData : JsAny

/**
 * Raw bytes owned by JavaScript.
 */
internal external interface ArrayBuffer : JsAny {
  val byteLength: Int
}

/**
 * The encoded still, either handed over as an object URL or downloaded.
 */
internal external interface Blob : JsAny {
  val type: String

  fun arrayBuffer(): Promise<ArrayBuffer>
}

/**
 * Object URL minting. A URL outlives the page's reference to the blob, so whoever mints one owns
 * revoking it.
 */
internal external object URL : JsAny {
  fun createObjectURL(obj: JsAny): String

  fun revokeObjectURL(url: String)
}

/**
 * The anchor a download is triggered through.
 */
internal external interface HtmlAnchor : JsAny {
  var href: String

  var download: String

  fun click()

  fun remove()
}

/**
 * Enough of the document to hang a download anchor off.
 */
internal external interface HtmlDocument : JsAny {
  val body: HtmlBody

  fun createElement(tagName: String): HtmlAnchor
}

/**
 * The document body, which a download anchor is attached to for the length of one click.
 */
internal external interface HtmlBody : JsAny {
  fun appendChild(child: JsAny): JsAny
}

internal external val document: HtmlDocument

/**
 * Schedules [handler] on a later task. Used once, to revoke a download's object URL after the click
 * that started it has been dispatched.
 */
internal external fun setTimeout(
  handler: () -> Unit,
  timeout: Int,
): Int

/**
 * Wraps the frame's pixels in an `ImageData` the canvas will take.
 */
internal expect fun imageDataOf(
  pixels: ByteArray,
  width: Int,
  height: Int,
): ImageData

/**
 * The options object `convertToBlob` reads the media type and the lossy quality out of.
 */
internal expect fun blobOptions(
  type: String,
  quality: Double,
): JsAny

/**
 * Wraps encoded bytes in a blob of [type], for an object URL or a download.
 */
internal expect fun blobOf(
  bytes: ByteArray,
  type: String,
): Blob

/**
 * Copies encoded bytes back out of the JavaScript heap.
 */
internal expect fun ArrayBuffer.toByteArray(): ByteArray
