package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.picture.PictureOrientation

/**
 * How [compositeFrames] ended.
 */
@InternalFilmstripApi
public sealed interface CompositeResult {
  /**
   * Every frame was composited and handed on.
   */
  @InternalFilmstripApi
  public data object Done : CompositeResult

  /**
   * The walk stopped at a frame it cannot draw, for the reason [message] gives in one sentence that is printed as it
   * is.
   */
  @InternalFilmstripApi
  public class Refused(
    public val message: String,
  ) : CompositeResult
}

/**
 * Composites every frame of [picture] onto one canvas, in order, and hands the canvas to [each] after each frame.
 *
 * The canvas starts transparent. Before a frame is drawn the one ahead of it is disposed. A [FrameDisposal.Background]
 * clears the frame's region to transparent whatever background colour or transparency flag the file carries, and a
 * [FrameDisposal.Previous] puts the region back as it was before the frame drew, which on the first frame is
 * transparent. [FrameBlend.Source] replaces the texels under a frame, and [FrameBlend.Over] lays straight alpha source
 * over them, rounded to the nearest sample of the file's depth.
 *
 * [each] receives the same [FrameSamples] every time and the canvas is drawn over once it returns, so it copies what it
 * needs. Nothing else is kept, so the walk holds two canvases however many frames the picture has. When the picture's
 * [AnimatedPicture.orientation] turns it, each frame is turned into a third canvas of its own before it is handed on,
 * so [each] always receives a canvas of [AnimatedPicture.shown] in size.
 * A [FramePixels.Texels] frame is opaque or zero. A frame stored as a [FramePixels.WebpStill] is decoded by
 * [decodeStill], which returns its samples or null.
 *
 * A frame that does not decode to the size of its region, or whose region leaves the canvas, ends the walk with a
 * [CompositeResult.Refused] naming it. Frames already handed on stay handed on.
 */
@InternalFilmstripApi
public fun compositeFrames(
  picture: AnimatedPicture,
  decodeStill: (ByteArray) -> FrameSamples?,
  each: (index: Int, canvas: FrameSamples) -> Unit,
): CompositeResult {
  val size = picture.canvas
  if (size.width <= 0 || size.height <= 0) return CompositeResult.Refused(animatedCanvasEmpty())
  val deep = picture.frames.any { (it.pixels as? FramePixels.Samples)?.sixteenBit == true }
  val canvas =
    FrameSamples(size.width, size.height, ShortArray(size.width * size.height * FRAME_SAMPLES_PER_TEXEL), deep)
  val turn = picture.orientation
  val turned =
    if (turn == PictureOrientation.Normal) {
      null
    } else {
      val shown = picture.shown
      FrameSamples(shown.width, shown.height, ShortArray(shown.width * shown.height * FRAME_SAMPLES_PER_TEXEL), deep)
    }
  var saved = ShortArray(0)
  var ahead: StoredFrame? = null
  for ((index, frame) in picture.frames.withIndex()) {
    val region = frame.region
    if (!region.liesWithin(size)) return CompositeResult.Refused(animatedFrameOutside(index))
    val argb = (frame.pixels as? FramePixels.Texels)?.argb
    val source = if (argb == null) frame.rgbaOrNull(decodeStill) else null
    val area = region.size.width.toLong() * region.size.height
    if (if (argb != null) argb.size.toLong() != area else source == null) {
      return CompositeResult.Refused(animatedFrameUndecoded(index))
    }
    ahead?.let { canvas.dispose(it, saved) }
    if (frame.disposal == FrameDisposal.Previous) {
      val needed = region.size.width * region.size.height * FRAME_SAMPLES_PER_TEXEL
      if (saved.size < needed) saved = ShortArray(needed)
      canvas.copyRegion(frame, saved, toCanvas = false)
    }
    if (argb != null) canvas.drawTexels(frame, argb) else canvas.draw(frame, source!!)
    each(index, if (turned == null) canvas else canvas.turnedInto(turned, turn))
    ahead = frame
  }
  return CompositeResult.Done
}

/**
 * The sentence an animated picture is refused with when frame [index] cannot be decoded.
 */
@InternalFilmstripApi
public fun animatedFrameUndecoded(index: Int): String =
  "Frame $index of this animated picture cannot be decoded, so it cannot be drawn."

private fun StoredFrame.rgbaOrNull(decodeStill: (ByteArray) -> FrameSamples?): ShortArray? {
  val wanted = region.size
  return when (val pixels = pixels) {
    is FramePixels.Samples -> {
      pixels.rgba.takeIf {
        it.size.toLong() ==
          wanted.width.toLong() * wanted.height * FRAME_SAMPLES_PER_TEXEL
      }
    }
    is FramePixels.Texels -> {
      null
    }
    is FramePixels.WebpStill -> {
      decodeStill(pixels.bytes)?.takeIf { it.width == wanted.width && it.height == wanted.height }?.rgba
    }
  }
}

// Writes every texel of the turned picture from the stored one it reads, and hands back [into].
private fun FrameSamples.turnedInto(
  into: FrameSamples,
  orientation: PictureOrientation,
): FrameSamples {
  var at = 0
  for (y in 0 until into.height) {
    for (x in 0 until into.width) {
      val sourceX = orientation.sourceX(x, y, into.width, into.height)
      val sourceY = orientation.sourceY(x, y, into.width, into.height)
      val from = (sourceY * width + sourceX) * FRAME_SAMPLES_PER_TEXEL
      rgba.copyInto(into.rgba, at, from, from + FRAME_SAMPLES_PER_TEXEL)
      at += FRAME_SAMPLES_PER_TEXEL
    }
  }
  return into
}

private fun FrameSamples.dispose(
  frame: StoredFrame,
  saved: ShortArray,
) {
  when (frame.disposal) {
    FrameDisposal.None -> Unit
    FrameDisposal.Background -> forEachRow(frame) { from, to, _ -> rgba.fill(0, from, to) }
    FrameDisposal.Previous -> copyRegion(frame, saved, toCanvas = true)
  }
}

private fun FrameSamples.copyRegion(
  frame: StoredFrame,
  region: ShortArray,
  toCanvas: Boolean,
) = forEachRow(frame) { from, to, row ->
  val at = row * (to - from)
  if (toCanvas) region.copyInto(rgba, from, at, at + to - from) else rgba.copyInto(region, at, from, to)
}

private fun FrameSamples.draw(
  frame: StoredFrame,
  source: ShortArray,
) = forEachRow(frame) { from, to, row ->
  val at = row * (to - from)
  if (frame.blend == FrameBlend.Source) {
    source.copyInto(rgba, from, at, at + to - from)
  } else {
    for (offset in 0 until to - from step FRAME_SAMPLES_PER_TEXEL) layOver(source, at + offset, from + offset)
  }
}

private fun FrameSamples.drawTexels(
  frame: StoredFrame,
  argb: IntArray,
) = forEachRow(frame) { from, to, row ->
  val at = row * (to - from) / FRAME_SAMPLES_PER_TEXEL
  for (column in 0 until (to - from) / FRAME_SAMPLES_PER_TEXEL) {
    val texel = argb[at + column]
    if (texel ushr ALPHA_SHIFT == 0 && frame.blend == FrameBlend.Over) continue
    val d = from + column * FRAME_SAMPLES_PER_TEXEL
    rgba[d] = ((texel ushr RED_SHIFT and BYTE) * EIGHT_BIT_UNIT).toShort()
    rgba[d + 1] = ((texel ushr GREEN_SHIFT and BYTE) * EIGHT_BIT_UNIT).toShort()
    rgba[d + 2] = ((texel and BYTE) * EIGHT_BIT_UNIT).toShort()
    rgba[d + ALPHA] = ((texel ushr ALPHA_SHIFT) * EIGHT_BIT_UNIT).toShort()
  }
}

private inline fun FrameSamples.forEachRow(
  frame: StoredFrame,
  visit: (from: Int, to: Int, row: Int) -> Unit,
) {
  val region = frame.region
  for (row in 0 until region.size.height) {
    val from = ((region.top + row) * width + region.left) * FRAME_SAMPLES_PER_TEXEL
    visit(from, from + region.size.width * FRAME_SAMPLES_PER_TEXEL, row)
  }
}

private fun FrameSamples.layOver(
  source: ShortArray,
  s: Int,
  d: Int,
) {
  val raw = source[s + ALPHA].toInt() and FULL
  if (raw == 0) return
  val under = rgba[d + ALPHA].toInt() and FULL
  if (raw == FULL || under == 0) {
    source.copyInto(rgba, d, s, s + FRAME_SAMPLES_PER_TEXEL)
    return
  }
  val unit = if (sixteenBit) 1 else EIGHT_BIT_UNIT
  val max = (FULL / unit).toLong()
  val sa = (raw / unit).toLong()
  val ua = (under / unit).toLong()
  val weight = sa * max + ua * (max - sa)
  for (channel in 0 until ALPHA) {
    val numerator =
      (source[s + channel].toInt() and FULL) / unit * sa * max +
        (rgba[d + channel].toInt() and FULL) / unit * ua * (max - sa)
    rgba[d + channel] = (((2 * numerator + weight) / (2 * weight)).toInt() * unit).toShort()
  }
  rgba[d + ALPHA] = (((2 * weight + max) / (2 * max)).toInt() * unit).toShort()
}

private const val ALPHA = 3
private const val FULL = 0xFFFF
private const val EIGHT_BIT_UNIT = 257
private const val BYTE = 0xFF
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8
private const val ALPHA_SHIFT = 24
