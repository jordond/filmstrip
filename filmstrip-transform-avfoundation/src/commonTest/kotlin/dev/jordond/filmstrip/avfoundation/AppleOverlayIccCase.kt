package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.unreadMatrix
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.UnturnedHeif
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.heifWithExplicitCodestreamColour
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.AvifPicture
import dev.jordond.filmstrip.test.CicpTag
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.HeicIdentityPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.flow.toList
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.assertIs
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The pictures and fixtures the colour described overlay export cases share, each case drawing them into an export and
 * reading them back out of the written file.
 *
 * Every picture in [IccPictures.ALL], a Display P3 red, a Display P3 HEIC and JPEG where ImageIO writes them, three
 * [ColourPictures] PNGs, both HDR PNGs, every still in [ColourPictures.EXPORT_REPRESENTATIVES], a HEIC grid and an
 * untagged picture are drawn as badges over the letterbox fill.
 * So are two HEICs under the SMPTE 240M matrix Apple's decoder does not read, a 4:4:4 one drawn from its stored
 * samples through core's evaluator and a 4:2:0 one restated to the nearest matrix the decoder reads. A described badge
 * is read through the description core reads out of the picture's bytes, and an AVIF or HEIC is held to what the
 * shared functions make of one of the texels Apple's decoder may hand back for it, each its own answer.
 *
 * An export case fails when `FILMSTRIP_FIXTURES` is unset or names a directory missing its clip. Gradle sets it for
 * both Apple test tasks, and the iPhone runner sets it to the fixtures copied into the app bundle.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
abstract class AppleOverlayIccCase {
  protected val filmstrip: Filmstrip = Filmstrip { avFoundationBackend() }

  /**
   * A badge picture, its encoded [bytes] and pixel [size], the [colour] its texels are read through and the
   * [samples] those texels may decode to, one texel each as sixteen-bit red, green, blue and alpha, worked out the
   * first time a case asks.
   *
   * [texel] is the eight-bit colour a picture painted in one is stored as, and null for a still whose decoder hands
   * back one of several [samples]. With [fromFile] set the picture is read from a file rather than from its bytes, so
   * a path source carries its description too. [degradedBy] is the one cause its overlay resolves degraded with, and
   * null when it resolves exact.
   */
  protected class Picture(
    val name: String,
    val bytes: ByteArray,
    val size: Size,
    val colour: PictureColour?,
    samples: () -> List<ShortArray>,
    val texel: Int? = null,
    val fromFile: Boolean = false,
    val degradedBy: String? = null,
  ) {
    val samples: List<ShortArray> by lazy(samples)

    private val path = NSTemporaryDirectory() + "filmstrip-apple-icc-${name.replace(' ', '-')}.png"

    @OptIn(BetaInteropApi::class)
    fun badgeAt(
      corner: Corner,
      scale: Float = ImageOverlay.DEFAULT_SCALE,
    ): ImageOverlay {
      if (!fromFile) return ImageOverlay(ImageSource.ofBytes(bytes), corner, scale = scale)
      bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, true)
      return ImageOverlay(ImageSource.of(path), corner, scale = scale)
    }

    /**
     * Removes the file [badgeAt] wrote, once the export that read it is done.
     */
    fun removeFile() {
      if (fromFile) NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }

    /**
     * The sRGB codes this badge draws at for each texel its decoder may hand back, unrounded for an untagged picture.
     */
    fun srgbCodes(): List<List<Float>> =
      samples.map { texel ->
        if (colour == null) {
          List(3) { (texel[it].toInt() and SAMPLE_MASK) / EIGHT_BIT_STEP }
        } else {
          AuthoredColours.channelsOf(pictureSrgbCodes(colour, texel).single()).map { it.toFloat() }
        }
      }

    /**
     * The ten-bit codes this badge carries over [BAR] on a [transfer] grade for each texel its decoder may hand back.
     */
    fun hdrCodes(transfer: HdrTransfer): List<List<Int>> {
      if (colour == null && texel != null) return listOf(HdrOverlayCells.Cell(texel, BAR, 1f).codes(transfer).toList())
      return samples.map { texel ->
        val light = pictureLight(colour, texel)
        val nits = OverlayPixels.hdrSourceOver(authoredHdrNits(BAR), light.copyOf(3), light[3])
        tenBitCodesFromSignal(transfer.signalFromNits(nits)).toList()
      }
    }

    override fun toString(): String = name
  }

  // The BT.709 blue and red differences of these red, green and blue codes, in the same codes.
  protected fun chroma(rgb: List<Float>): List<Float> {
    val red = BT709.kr.toFloat()
    val blue = BT709.kb.toFloat()
    val luma = red * rgb[0] + (1 - red - blue) * rgb[1] + blue * rgb[2]
    return listOf((rgb[2] - luma) / (2 * (1 - blue)), (rgb[0] - luma) / (2 * (1 - red)))
  }

  protected fun List<Int>.within(
    read: List<Int>,
    tolerance: Int,
  ): Boolean = indices.all { abs(this[it] - read[it]) <= tolerance }

  protected companion object {
    val TIMEOUT = 5.minutes

    const val PORTRAIT = "apple_export_portrait.mp4"

    val CLIP = 1.seconds
    val SDR = ExportSpec(targetHeight = 180)
    val HDR = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)

    // What a real encode, a 4:2:0 round trip and the probe's colour match leave on one channel of a flat colour, as
    // AppleEffectTest holds its overlays to.
    const val CHANNEL_DRIFT = 6
    const val FULL_SCALE = 255f
    const val SAMPLE_MASK = 0xFFFF
    const val EIGHT_BIT_STEP = 257f
    const val ALPHA_SHIFT = 24

    const val BAR = AuthoredColours.MID_GREY
    const val RED = 0xFFFF0000.toInt()
    const val P3_RED_NAME = "Display P3 red"

    // Dark enough that the grey profile's gamma moves it past the drift, where a mid grey moves under a code.
    const val DARK_GREY = 0xFF101010.toInt()
    val SRGB_BY_DESIGN = setOf(IccPictures.SRGB.name, IccPictures.LUT_SRGB.name)

    // A quarter of the default badge, small enough that the encode's ringing reaches its centre.
    const val SMALL_SCALE = 0.05f

    // Core Image hands over unrounded codes, and a badge is drawn at whole ones.
    const val DRAWN_TOLERANCE = 0.1f

    // VideoToolbox's limit, not filmstrip's. Its lossy encode rings into a small badge's centre by a share of the
    // chroma step between the badge and the fill, past a code of rounding, the 4:2:0 round trip and the probe's colour
    // match. That share measured up to 4.8 percent on macOS and on the simulator.
    const val RINGING_SHARE = 0.055f
    const val ENCODE_FLOOR = 1f
    const val BT709_MATRIX = 1
    const val BT709_PRIMARIES = 1
    const val BT709_TRANSFER = 1
    const val SRGB_TRANSFER = 13
    const val SMPTE_240M = 7
    const val EIGHT_BITS = 8
    val BT709 = ycbcrReading(BT709_MATRIX, BT709_PRIMARIES, BT709_TRANSFER) as YcbcrReading.KrKb

    // A badge at the default scale and margin sits inside the bars at each corner, two to a bar.
    val CORNERS = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart, Corner.BottomEnd)
    val RASTER = ICC_RASTER

    // A still is square, and only its aspect sets a badge's height.
    val STILL = Size(1, 1)

    /**
     * A picture painted [texel] all over, read through [colour], the description core reads out of [bytes] unless one
     * is given.
     */
    fun painted(
      name: String,
      texel: Int,
      bytes: ByteArray,
      fromFile: Boolean = false,
      colour: PictureColour? = pictureColour(bytes),
    ): Picture {
      val codes = AuthoredColours.channelsOf(texel) + (texel ushr ALPHA_SHIFT)
      val samples = ShortArray(codes.size) { (codes[it] * EIGHT_BIT_STEP.toInt()).toShort() }
      return Picture(name, bytes, RASTER, colour, { listOf(samples) }, texel, fromFile)
    }

    // Formats that keep a Display P3 profile outside a PNG, for ImageIO to write.
    val ENCODED_TYPES = listOf("public.heic", "public.jpeg")

    // The Display P3 pictures ImageIO wrote. A type it cannot write on this platform is left out, which
    // [imageIoWrites] pins per target as ImageIO's limit.
    val ENCODED: List<Picture> by lazy {
      ENCODED_TYPES.mapNotNull { type ->
        encodedDisplayP3(type)?.let { bytes ->
          painted("Display P3 $type", P3_TEXEL, bytes, colour = AppleDisplayP3.colour())
        }
      }
    }

    val ICC_TAGGED: List<Picture> by lazy {
      IccPictures.ALL.map { picture ->
        val texel = if (picture == IccPictures.GREY) DARK_GREY else picture.texel
        painted(
          picture.name,
          texel,
          OverlayPixels.pngOf(RASTER, texel, picture.profile),
          fromFile = picture == IccPictures.DISPLAY_P3,
        )
      } + painted(P3_RED_NAME, RED, OverlayPixels.pngOf(RASTER, RED, IccPictures.DISPLAY_P3.profile)) + ENCODED
    }

    // A cICP wide gamut PNG, a cICP linear light one and a wide gamut one described by gAMA and cHRM.
    val PNGS = listOf(ColourPictures.CICP_DISPLAY_P3, ColourPictures.CICP_LINEAR, ColourPictures.GAMMA_DISPLAY_P3)

    // The export representatives and a grid. Their HEICs decode through the raster's rewrite, and the grid's rewrite
    // reaches the parameter sets its tiles share. Each is held to the decode ImageIO hands back for that rewrite.
    val DESCRIBED: List<Picture> by lazy {
      PNGS.map { painted(it.name, it.texel, it.pngOf(RASTER)) } +
        ColourPictures.HDR_PNGS.map { picture ->
          val bytes = picture.pngOf(RASTER)
          val samples = ShortArray(picture.texel.size) { picture.texel[it].toShort() }
          Picture(picture.name, bytes, RASTER, pictureColour(bytes), { listOf(samples) })
        } +
        ColourPictures.EXPORT_REPRESENTATIVES.map { still ->
          val bytes = still.bytes()
          val name = if (still is AvifPicture) "AVIF ${still.name}" else still.name
          Picture(name, bytes, STILL, pictureColour(bytes), { AppleStillSamples.of(still, imageIoRead(bytes)) })
        } +
        ColourPictures.HEIC_NCLX_BT709_FULL.let { still ->
          val bytes = still.gridBytes()
          val samples = { AppleStillSamples.of(still, imageIoRead(bytes)) }
          Picture("${still.name} grid", bytes, STILL, pictureColour(bytes), samples)
        }
    }

    val SMPTE_240M_NCLX = CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, SMPTE_240M, true)

    // A 4:4:4 HEIC whose stored samples ImageIO hands out and core's evaluator draws, and a 4:2:0 one landed through
    // the nearest matrix Apple's decoder reads, which resolves degraded.
    val LANDED: List<Picture> by lazy {
      listOf(
        HeicIdentityPictures.bytes(SMPTE_240M_NCLX).let { bytes ->
          val samples = AppleStillSamples.evaluated(SMPTE_240M_NCLX, EIGHT_BITS, HeicIdentityPictures.YCBCR)
          Picture("4:4:4 HEIC nclx SMPTE 240M matrix", bytes, STILL, pictureColour(bytes), { listOf(samples) })
        },
        AppleStillSamples.UNREAD_MATRIX.let { still ->
          val bytes = still.bytes()
          val landed = AppleStillSamples.landing(checkNotNull(still.nclx), unsubsampled = false, EIGHT_BITS)
          val samples = { AppleStillSamples.landed(still) }
          val degradedBy = unreadMatrix(checkNotNull(landed)).message
          Picture(still.name, bytes, STILL, pictureColour(bytes), samples, degradedBy = degradedBy)
        },
      )
    }

    val PICTURES: List<Picture> by lazy {
      listOf(
        painted("untagged", AuthoredColours.MID_GREEN, OverlayPixels.pngOf(RASTER, AuthoredColours.MID_GREEN, null)),
      ) +
        ICC_TAGGED + DESCRIBED + LANDED
    }
  }
}

/**
 * The Display P3 picture the ICC overlay cases draw, painted [P3_TEXEL] and written by ImageIO as [type], or null when
 * ImageIO cannot write that type on this platform.
 */
internal fun encodedDisplayP3(type: String): ByteArray? = AppleDisplayP3.picture(type, ICC_RASTER, P3_TEXEL)

/**
 * Whether ImageIO writes [encodedDisplayP3] as [type] on this platform.
 *
 * It opens an ImageIO destination for [type] and reports whether finalizing it succeeds, the same calls the ICC overlay
 * cases write their encoded pictures with.
 */
internal fun imageIoWrites(type: String): Boolean = encodedDisplayP3(type) != null

internal const val P3_TEXEL = 0xFFC08040.toInt()

private val ICC_RASTER = Size(4, 2)

// The texel ImageIO decodes a HEIF's bytes to once the overlay raster's rewrite states its colour in the codestream.
@OptIn(InternalFilmstripApi::class)
internal fun imageIoRead(bytes: ByteArray): ShortArray =
  AppleStillSamples.imageIoRead(assertIs<UnturnedHeif.Handed>(heifWithExplicitCodestreamColour(bytes)).bytes)
