package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRef
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage

/**
 * An overlay's pixels, held as bare codes with no color space attached, and how the picture describes their color.
 *
 * An SDR frame draws a texel at the sRGB codes [pictureSrgbCodes] gives its color, tagged with the frame's own code
 * space so they come out of the blend where they were authored. An untagged picture keeps its stored codes there. An
 * HDR frame draws each texel at the light [pictureLight] puts its color at, encoded for the frame's transfer and
 * tagged with the space that transfer is written in, the way a fill hands Core Image its color. An opaque untagged
 * texel then lands where a fill of the same color does, and a tagged one lands at the color its description gives it.
 * Both read the stored samples at sixteen bits. An HDR frame keeps a sixteen-bit picture's full depth, an SDR frame
 * draws the whole eight-bit codes [pictureSrgbCodes] gives, and an untagged eight-bit picture lands exactly where its
 * codes do.
 *
 * Either image is made a band of rows at a time by [StoredBands], each band converted and written straight into the
 * premultiplied floats the image draws from, so the stored samples are never held whole. What a picture holds while
 * one is made, the codes [held] included, is counted by [drawnPeakBytes], and a draw that would make the
 * floats for a picture past [ceiling], or cannot allocate them, throws [PictureTooLargeToDraw] instead, on that frame
 * and every one after it that needs the same image. At 48 MP an eight-bit picture peaks at 1,010 MB by that count and
 * draws, and a sixteen-bit or evaluated one, eight bytes a texel of codes, counts 1,202 MB and is refused. An SDR
 * frame whose picture is untagged, or comes out at its own eight-bit codes in every band, draws [codes] and holds no
 * floats, so it draws at any size.
 *
 * On an Apple M4 Max under macOS 26.6.2 (25G83), drawing an 8000 by 6000 picture tagged Display P3 in a debug build,
 * an eight-bit picture's footprint peaks at 1,252 MB on an SDR frame and 1,223 MB on a PQ frame, its decode of 198 MB
 * included, and a sixteen-bit one at 1,437 MB and 1,419 MB with its decode of 390 MB. Each image takes 31 s to 35 s
 * to make.
 *
 * The stored codes are cut to [crop] and laid out the way [orientation] shows the picture once, here, so every image
 * made from them and [size] are of the picture as it is shown.
 *
 * @property held The pixels with no color space attached as they are stored, and the bytes held behind them.
 * @property codes The pixels with no color space attached, cut to [crop] and laid out the way [orientation] shows
 * them.
 * @property size The size the picture is shown at.
 * @property limits The ways Apple's decoder landed these pixels short of what the picture's file stores, empty when it
 * landed them as stored.
 * @param readColor Reads how the picture describes its color, or null when it is untagged. It runs the first time
 * something asks for [color].
 * @param reader Gives the context the stored samples are read back through, or null when none can be built.
 * @param bandTexels How many texels one band of [StoredBands] holds at most.
 * @param ceiling The most bytes [drawnPeakBytes] may count for a draw that makes floats, [PICTURE_PEAK_CEILING]
 * by default.
 * @param orientation The turn the picture's own tag asks for, which no Apple decoder applies.
 * @param crop The texels of the stored codes an AVIF or HEIC's `clap` keeps, or null to keep them all. No Apple decoder
 * is handed a `clap`, so [held] is the whole stored frame. It has to lie within it.
 */
internal class OverlayPixels(
  val held: HeldCodes,
  readColor: () -> PictureColor? = { null },
  private val reader: () -> CIContext? = { codeReader },
  val limits: List<Degradation> = emptyList(),
  private val bandTexels: Int = BAND_TEXELS,
  private val ceiling: Long = PICTURE_PEAK_CEILING,
  orientation: PictureOrientation = PictureOrientation.Normal,
  crop: PictureCrop? = null,
) {
  val codes: CIImage = held.image.cropped(crop).oriented(orientation)

  val size: Size = orientation.orientedSize(crop?.size ?: held.image.pixelSize())

  /**
   * How the picture describes the color of its stored samples, or null when it is untagged.
   */
  val color: PictureColor? by lazy(readColor)

  private val srgb: Lazy<Result<CIImage>> = lazy { refusable { color?.let { inSrgbCodes(it) } ?: codes } }

  private val graded: Map<HdrTransfer, Lazy<Result<CIImage?>>> =
    HdrTransfer.entries.associateWith { transfer -> lazy { refusable { inPictureLight(transfer) } } }

  /**
   * The image to draw onto a frame written in [colorSpace] with [transfer], or with none when the
   * frame is SDR.
   *
   * Each image is built the first time a frame asks for it. When the samples cannot be read back, a frame of either
   * kind draws the stored codes as they are. When only the transfer's space cannot be named, an HDR frame draws the
   * picture the way an SDR frame does.
   *
   * @throws PictureTooLargeToDraw When the image this frame needs makes floats for a picture past [ceiling].
   */
  fun enteringFrame(
    transfer: HdrTransfer?,
    colorSpace: ColorSpace,
  ): CIImage =
    transfer?.let { graded.getValue(it).value.getOrThrow() }
      ?: srgb.value.getOrThrow().taggedWithCodesOf(colorSpace)

  private inline fun <T> refusable(make: () -> T): Result<T> =
    try {
      Result.success(make())
    } catch (refused: PictureTooLargeToDraw) {
      Result.failure(refused)
    }

  // The floats a whole picture is drawn from, once the peak drawing it holds is known to fit under the ceiling.
  private fun heldFloats(): PremultipliedFloats {
    val width = size.width.toLong()
    val height = size.height.toLong()
    val peak = drawnPeakBytes(width, height, held.bytes, bandTexels)
    if (peak == null || peak > ceiling) throw PictureTooLargeToDraw(tooLargeToDraw(width, height, ceiling))
    return PremultipliedFloats(size, ceiling)
  }

  /**
   * These samples at the sRGB codes [pictureSrgbCodes] gives [color], premultiplied in floats, or [codes] when every
   * band comes out at its own eight-bit codes. The floats are made at the first band that does not, and the bands
   * before it are read again into them.
   */
  @OptIn(ExperimentalForeignApi::class)
  private fun inSrgbCodes(color: PictureColor): CIImage? {
    val bands = reader()?.let { StoredBands(codes, size, it, bandTexels) } ?: return null
    val premultiplied = FloatArray(bands.rows * size.width * TEXEL_FLOATS)
    var floats: PremultipliedFloats? = null
    var row = 0
    while (row < size.height) {
      val samples = bands.read(row)
      val drawn = pictureSrgbCodes(color, samples)
      val made = floats
      if (made == null && samples.isEightBit() && drawn contentEquals pictureSrgbCodes(null, samples)) {
        row += drawn.size / size.width
        continue
      }
      val into = made ?: heldFloats().also { floats = it }
      if (made == null && row > 0) {
        row = 0
        continue
      }
      drawn.premultipliedInto(premultiplied)
      into.write(row, premultiplied, drawn.size * TEXEL_FLOATS)
      row += drawn.size / size.width
    }
    return floats?.image(colorSpace = null) ?: codes
  }

  /**
   * These samples at the light [pictureLight] puts them at, as a float image carrying [transfer]'s signal.
   *
   * The mapping runs on each texel's straight color and the signal is premultiplied after, since Core Image takes
   * bitmap data as premultiplied and unpremultiplies before it matches a color out of the space.
   */
  @OptIn(ExperimentalForeignApi::class)
  private fun inPictureLight(transfer: HdrTransfer): CIImage? {
    val bands = reader()?.let { StoredBands(codes, size, it, bandTexels) } ?: return null
    val space = transfer.createSignalSpace() ?: return null
    try {
      val floats = heldFloats()
      try {
        bands.forEach { row, samples ->
          val texels = pictureLight(color, samples)
          texels.signalInPlace(transfer)
          floats.write(row, texels)
        }
      } catch (thrown: Throwable) {
        floats.release()
        throw thrown
      }
      return floats.image(space)
    } finally {
      CGColorSpaceRelease(space)
    }
  }
}

/**
 * Writes over this light, four floats a texel in cd/m2 and then alpha, with [transfer]'s signal premultiplied by the
 * texel's alpha.
 */
private fun FloatArray.signalInPlace(transfer: HdrTransfer) {
  val nits = FloatArray(ALPHA)
  var signal = FloatArray(ALPHA)
  for (offset in indices step TEXEL_FLOATS) {
    // A run of one color, the usual case in a picture, is encoded once.
    val changed = this[offset] != nits[0] || this[offset + 1] != nits[1] || this[offset + 2] != nits[2]
    if (offset == 0 || changed) {
      copyInto(nits, 0, offset, offset + ALPHA)
      signal = transfer.signalFromNits(nits)
    }
    val alpha = this[offset + ALPHA]
    repeat(ALPHA) { this[offset + it] = signal[it] * alpha }
  }
}

/**
 * Whether every sample is an eight-bit code times 257, the way an eight-bit picture reads back at sixteen bits.
 */
private fun ShortArray.isEightBit(): Boolean = all { (it.toInt() and SAMPLE_MASK) % EIGHT_BIT_STEP == 0 }

/**
 * Writes these straight ARGB codes into [floats] as premultiplied red, green, blue and alpha.
 *
 * Each texel's color is premultiplied here, in floats, so the image carries every code as it was rather than one
 * rounded back onto the eight-bit grid after the multiply.
 */
private fun IntArray.premultipliedInto(floats: FloatArray) {
  forEachIndexed { index, color ->
    val offset = index * TEXEL_FLOATS
    val alpha = (color ushr ALPHA_SHIFT).toFloat() / TOP_CODE
    floats[offset] = ((color shr RED_SHIFT) and CODE_MASK).toFloat() / TOP_CODE * alpha
    floats[offset + 1] = ((color shr GREEN_SHIFT) and CODE_MASK).toFloat() / TOP_CODE * alpha
    floats[offset + 2] = (color and CODE_MASK).toFloat() / TOP_CODE * alpha
    floats[offset + ALPHA] = alpha
  }
}

/**
 * Creates the color space a frame written with this transfer carries its signal in. The caller releases it.
 */
@OptIn(ExperimentalForeignApi::class)
private fun HdrTransfer.createSignalSpace(): CGColorSpaceRef? =
  CGColorSpaceCreateWithName(
    when (this) {
      HdrTransfer.Pq -> kCGColorSpaceITUR_2100_PQ
      HdrTransfer.Hlg -> kCGColorSpaceITUR_2100_HLG
    },
  )

private const val TEXEL_FLOATS = 4
private const val ALPHA = 3
private const val CODE_MASK = 0xFF
private const val ALPHA_SHIFT = 24
private const val RED_SHIFT = 16
private const val GREEN_SHIFT = 8

// Decoded through the same space inCodesOf encodes into, so carrying the result into those codes
// again hands back the values it started with.
@OptIn(ExperimentalForeignApi::class)
private fun CIImage.taggedWithCodesOf(colorSpace: ColorSpace): CIImage {
  val space = colorSpace.createCodeSpace()

  try {
    return imageByColorMatchingColorSpaceToWorkingSpace(space) ?: this
  } finally {
    CGColorSpaceRelease(space)
  }
}
