package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.picturePeakBytes

/**
 * What a backend holds of an animated picture once it is resolved, in bytes for every texel of the canvas.
 *
 * [frameBytes] is held once for every frame, and is what an 8-bit file costs. A 16-bit file holds [deepFrameBytes],
 * which is twice that unless a backend says otherwise. [cellBytes] is held once for the whole picture, whatever its
 * frame count. [animatedPeakBytes] turns one of these into a count.
 *
 * @property frameBytes The bytes held for each texel of each frame of an 8-bit file.
 * @property deepFrameBytes The bytes held for each texel of each frame of a 16-bit file.
 * @property cellBytes The bytes held for each texel of the canvas, once.
 */
@InternalFilmstripApi
public class AnimatedCopies(
  public val frameBytes: Int,
  public val cellBytes: Int = 0,
  public val deepFrameBytes: Int = 2 * frameBytes,
) {
  init {
    require(frameBytes >= 0 && cellBytes >= 0 && deepFrameBytes >= 0) {
      "Held bytes are not negative, not $frameBytes, $cellBytes and $deepFrameBytes."
    }
  }

  /**
   * The copies each backend holds.
   */
  @InternalFilmstripApi
  public companion object {
    /**
     * A bitmap of four bytes a texel for every frame.
     */
    public val Media3: AnimatedCopies = AnimatedCopies(frameBytes = BITMAP_BYTES)

    /**
     * Four bytes a texel of codes for every frame, and the float form of the two cells a draw keeps, sixteen bytes a
     * texel each.
     */
    public val Apple: AnimatedCopies =
      AnimatedCopies(
        frameBytes = BITMAP_BYTES,
        cellBytes =
          KEPT_FLOAT_CELLS * FLOAT_BYTES,
      )

    /**
     * One atlas buffer of four bytes a texel for every frame.
     */
    public val Browser: AnimatedCopies = AnimatedCopies(frameBytes = BITMAP_BYTES)

    /**
     * The atlas of four bytes a texel for every frame of a file that carries no color description, which is written as
     * it is, and its PNG at most four bytes a texel held twice while it is encoded, once as the chunks the writer fills
     * and once as the array they are copied into. A 16-bit atlas is eight bytes a texel, is written as it is, and its
     * PNG is at most eight.
     */
    public val Ffmpeg: AnimatedCopies =
      AnimatedCopies(
        frameBytes = BITMAP_BYTES + PNG_COPIES * BITMAP_BYTES,
        deepFrameBytes = DEEP_BYTES + PNG_COPIES * DEEP_BYTES,
      )

    /**
     * What [Ffmpeg] holds for a file that carries a color description, whose 8-bit atlas is converted to the sRGB codes
     * it is written from, four bytes a texel more.
     */
    public val FfmpegTagged: AnimatedCopies =
      AnimatedCopies(
        frameBytes = 2 * BITMAP_BYTES + PNG_COPIES * BITMAP_BYTES,
        deepFrameBytes = DEEP_BYTES + PNG_COPIES * DEEP_BYTES,
      )

    /**
     * The atlas of four bytes a texel for every frame, the samples of the signal PNG written from it at eight bytes a
     * texel, and that PNG at most eight bytes a texel held twice while it is encoded, once as the chunks the writer
     * fills and once as the array they are copied into. A 16-bit atlas is eight bytes a texel.
     */
    public val FfmpegHdr: AnimatedCopies =
      AnimatedCopies(
        frameBytes = BITMAP_BYTES + DEEP_BYTES + PNG_COPIES * DEEP_BYTES,
        deepFrameBytes = DEEP_BYTES + DEEP_BYTES + PNG_COPIES * DEEP_BYTES,
      )

    private const val BITMAP_BYTES = 4
    private const val FLOAT_BYTES = 16
    private const val KEPT_FLOAT_CELLS = 2
    private const val DEEP_BYTES = 8
    private const val PNG_COPIES = 2
  }
}

/**
 * The bytes a backend holds at its peak while it resolves an animated picture of [frameCount] frames on [canvas], or
 * null when that count does not fit in a [Long].
 *
 * It is the two canvases the compositor works on, eight bytes a texel each at either depth, and [source], beside what
 * [held] says the backend keeps. A 16-bit file holds [AnimatedCopies.deepFrameBytes]. [source] is what the reader holds
 * while the walk runs, which [animatedSourceBytes] counts. A [turned] picture has a third canvas the same size, the one
 * each frame is turned into before it is handed on.
 */
@InternalFilmstripApi
public fun animatedPeakBytes(
  canvas: Size,
  frameCount: Int,
  held: AnimatedCopies,
  sixteenBit: Boolean = false,
  source: Long = 0L,
  turned: Boolean = false,
): Long? {
  if (canvas.width < 0 || canvas.height < 0 || frameCount < 0) return null
  val texels = canvas.width.toLong() * canvas.height
  val frameBytes = (if (sixteenBit) held.deepFrameBytes else held.frameBytes).toLong()
  return picturePeakBytes(
    held = sum(product(product(texels, frameCount.toLong()), frameBytes), product(texels, held.cellBytes.toLong())),
    band = sum(product(texels, (if (turned) 3L else 2L) * SAMPLE_TEXEL_BYTES), source.takeIf { it >= 0 }),
    made = 0L,
  )
}

/**
 * The sentence an animated picture is refused with when [animatedPeakBytes] passes [ceiling], or null when it does not.
 *
 * A count that does not fit in a [Long] is refused with the sentence for [Long.MAX_VALUE] bytes.
 */
@InternalFilmstripApi
public fun animatedPeakRefusal(
  canvas: Size,
  frameCount: Int,
  held: AnimatedCopies,
  sixteenBit: Boolean = false,
  source: Long = 0L,
  ceiling: Long = PICTURE_PEAK_CEILING,
  turned: Boolean = false,
): String? {
  val peak = animatedPeakBytes(canvas, frameCount, held, sixteenBit, source, turned)
  return if (peak != null && peak <= ceiling) null else animatedPictureTooLarge(peak ?: Long.MAX_VALUE, ceiling)
}

/**
 * The sentence [picture] is refused with when [animatedPeakBytes] of it passes [ceiling], or null when it does not.
 *
 * The frame count, the depth, the turn and the source bytes are read from [picture] by [animatedSourceBytes], with the
 * [encodedBytes] of its file, so the count is the one every backend makes.
 */
@InternalFilmstripApi
public fun animatedPeakRefusal(
  picture: AnimatedPicture,
  held: AnimatedCopies,
  ceiling: Long = PICTURE_PEAK_CEILING,
  encodedBytes: Long = 0L,
): String? {
  val deep = picture.frames.any { (it.pixels as? FramePixels.Samples)?.sixteenBit == true }
  val source = animatedSourceBytes(picture, encodedBytes) ?: Long.MAX_VALUE
  val turned = picture.orientation != PictureOrientation.Normal
  return animatedPeakRefusal(picture.canvas, picture.frames.size, held, deep, source, ceiling, turned)
}

/**
 * The bytes [picture] holds while it is composited, or null when that count does not fit in a [Long].
 *
 * A frame of decoded samples counts eight bytes a texel of its region and a frame of packed texels four. A frame stored
 * as a still WebP counts its encoded bytes, and one decoded frame at a time is counted on top, eight bytes a texel of
 * the largest region. The [encodedBytes] of the file the picture was read from are held beside them.
 */
@InternalFilmstripApi
public fun animatedSourceBytes(
  picture: AnimatedPicture,
  encodedBytes: Long = 0L,
): Long? {
  var total: Long? = encodedBytes
  var widest = 0L
  for (frame in picture.frames) {
    val area =
      frame.region.size.width
        .toLong() * frame.region.size.height
    when (val pixels = frame.pixels) {
      is FramePixels.Samples -> {
        total = sum(total, product(area, SAMPLE_TEXEL_BYTES))
      }
      is FramePixels.Texels -> {
        total = sum(total, product(area, PACKED_TEXEL_BYTES))
      }
      is FramePixels.WebpStill -> {
        total = sum(total, pixels.bytes.size.toLong())
        widest = maxOf(widest, area)
      }
    }
  }
  return sum(total, product(widest, SAMPLE_TEXEL_BYTES))
}

private const val SAMPLE_TEXEL_BYTES = 8L
private const val PACKED_TEXEL_BYTES = 4L

private fun product(
  a: Long?,
  b: Long,
): Long? = if (a == null || a < 0 || b < 0 || (b != 0L && a > Long.MAX_VALUE / b)) null else a * b

private fun sum(
  a: Long?,
  b: Long?,
): Long? = if (a == null || b == null || a > Long.MAX_VALUE - b) null else a + b
