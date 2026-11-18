@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AtlasGrid
import dev.jordond.filmstrip.picture.animated.CompositeResult
import dev.jordond.filmstrip.picture.animated.FramePixels
import dev.jordond.filmstrip.picture.animated.FrameSamples
import dev.jordond.filmstrip.picture.animated.StoredFrame
import dev.jordond.filmstrip.picture.animated.animatedFrameUndecoded
import dev.jordond.filmstrip.picture.animated.animatedPictureTooLarge
import dev.jordond.filmstrip.picture.animated.compositeFrames
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The frames of an animated picture composited into one eight-bit atlas, as [OverlayDecode.Animated] holds them.
 *
 * @property read The picture as [animatedRead] laid it out, which every overlay naming it resolves against.
 * @property codes The atlas as straight RGBA codes, four bytes a texel, row by row from the top left.
 */
internal class AnimatedAtlas(
  val read: AnimatedFlipbookRead.Drawn,
  val codes: ByteArray,
) {
  val grid: AtlasGrid get() = read.grid
  val flipbook: Flipbook get() = read.flipbook
  val color: PictureColor? get() = read.color
}

/**
 * Decodes [bytes] as an animated GIF, APNG or WebP into one atlas, or null when it is not animated.
 *
 * A browser decodes a WebP frame only asynchronously, so every one is decoded here and laid in its own cell of the
 * atlas, and the compositing that follows reads it back out of that cell and writes the composited frame over it. The
 * atlas is the one copy of the frames held, and the atlas side is checked against the page's texture side when the
 * overlay resolves. A file the reader refuses or whose peak passes [ceiling] is refused by name before any frame is
 * decoded.
 */
internal suspend fun animatedAtlasOf(
  bytes: ByteArray,
  ceiling: Long,
): OverlayDecode? {
  val drawn =
    when (val read = animatedRead(bytes, AnimatedCopies.Browser, ceiling = ceiling)) {
      AnimatedFlipbookRead.Still -> return null
      is AnimatedFlipbookRead.Refused -> return OverlayDecode.Refused(read.message)
      is AnimatedFlipbookRead.Drawn -> read
    }
  val picture = drawn.picture
  val grid = drawn.grid
  val width = grid.size.width
  val atlasBytes = width.toLong() * grid.size.height * TEXEL_BYTES
  if (atlasBytes > Int.MAX_VALUE) return OverlayDecode.Refused(animatedPictureTooLarge(atlasBytes, ceiling))
  val codes = ByteArray(atlasBytes.toInt())

  val stills = picture.frames.withIndex().filter { it.value.pixels is FramePixels.WebpStill }
  for ((index, frame) in stills) {
    if (!codes.stage(width, grid, index, frame)) return OverlayDecode.Refused(animatedFrameUndecoded(index))
  }
  var next = 0
  val result =
    compositeFrames(
      picture,
      decodeStill = { still ->
        val (index, frame) = stills.getOrNull(next++) ?: return@compositeFrames null
        if ((frame.pixels as FramePixels.WebpStill).bytes !== still) null else codes.unstaged(width, grid, index, frame)
      },
      each = { index, canvas ->
        val cell = grid.cells[index]
        val row = canvas.width * TEXEL_BYTES
        for (y in 0 until canvas.height) {
          val to = ((cell.top + y) * width + cell.left) * TEXEL_BYTES
          for (offset in 0 until row) codes[to + offset] = codeOf(canvas.rgba[y * row + offset])
        }
      },
    )
  if (result is CompositeResult.Refused) return OverlayDecode.Refused(result.message)
  return OverlayDecode.Animated(AnimatedAtlas(drawn, codes))
}

// The straight codes of the still, decoded without premultiplying, laid in its frame's cell at the frame's region.
// False when the browser could not decode it or it is not the size of its region.
private suspend fun ByteArray.stage(
  atlasWidth: Int,
  grid: AtlasGrid,
  index: Int,
  frame: StoredFrame,
): Boolean {
  val still = (frame.pixels as FramePixels.WebpStill).bytes
  val region = frame.region.size
  val decoded =
    awaitCatching {
      val bitmap = bitmapOf(still)
      try {
        if (bitmap.width == region.width && bitmap.height == region.height) codesOf(bitmap) else null
      } finally {
        bitmap.close()
      }
    }.getOrNull() ?: return false
  val cell = grid.cells[index]
  val row = region.width * TEXEL_BYTES
  for (y in 0 until region.height) {
    val to = ((cell.top + frame.region.top + y) * atlasWidth + cell.left + frame.region.left) * TEXEL_BYTES
    decoded.copyInto(this, to, y * row, (y + 1) * row)
  }
  return true
}

private fun ByteArray.unstaged(
  atlasWidth: Int,
  grid: AtlasGrid,
  index: Int,
  frame: StoredFrame,
): FrameSamples {
  val cell = grid.cells[index]
  val region = frame.region
  val row = region.size.width * TEXEL_BYTES
  val samples = ShortArray(row * region.size.height)
  for (y in 0 until region.size.height) {
    val from = ((cell.top + region.top + y) * atlasWidth + cell.left + region.left) * TEXEL_BYTES
    for (offset in 0 until row) {
      samples[y * row + offset] = ((this[from + offset].toInt() and CODE_MASK) * EIGHT_BIT_STEP).toShort()
    }
  }
  return FrameSamples(region.size.width, region.size.height, samples, sixteenBit = false)
}

// A sixteen-bit sample as the eight-bit code nearest it, which gives an eight-bit file's own code back exactly. A
// sixteen-bit APNG is drawn at eight bits on the browser because that is the browser's limit: the atlas is uploaded as
// RGBA8, and a sixteen-bit texture format needs a WebGL extension that is not on every browser.
internal fun codeOf(sample: Short): Byte = (((sample.toInt() and SAMPLE_MASK) + HALF_STEP) / EIGHT_BIT_STEP).toByte()

private const val TEXEL_BYTES = 4
private const val CODE_MASK = 0xFF
private const val SAMPLE_MASK = 0xFFFF
private const val HALF_STEP = 128
