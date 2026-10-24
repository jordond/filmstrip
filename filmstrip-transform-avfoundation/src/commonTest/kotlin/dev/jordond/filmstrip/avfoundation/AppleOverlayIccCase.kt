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
import dev.jordond.filmstrip.media.YcbcrReading
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.test.AppleDisplayP3
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColours
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
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The pictures and fixtures the colour described overlay export cases share, each case drawing them into an export and
 * reading them back out of the written file.
 *
 * Every picture in [IccPictures.ALL], a Display P3 red, a Display P3 HEIC and JPEG written by ImageIO, every
 * [ColourPictures] PNG, AVIF and HEIC, a HEIC grid and an untagged picture are drawn as badges over the letterbox fill.
 * So are two HEICs under the SMPTE 240M matrix Apple's decoder does not read, a 4:4:4 one drawn from its stored
 * samples through core's evaluator and a 4:2:0 one restated to the nearest matrix the decoder reads. A described badge
 * is read through the description core reads out of the picture's bytes, and an AVIF or HEIC is held to what the
 * shared functions make of one of the texels Apple's decoder may hand back for it, each its own answer.
 *
 * The export cases print why and return with nothing asserted when `FILMSTRIP_FIXTURES` is unset. A clip missing from a
 * set `FILMSTRIP_FIXTURES` fails.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
abstract class AppleOverlayIccCase {
  protected val fixtures: String? = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  protected val filmstrip: Filmstrip = Filmstrip { avFoundationBackend() }

  protected fun fixture(name: String): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$name"
    assertTrue(NSFileManager.defaultManager.fileExistsAtPath(path), "$FIXTURES is set but $path is missing")
    return path
  }

  protected fun clipOf(transfer: HdrTransfer): String =
    when (transfer) {
      HdrTransfer.Pq -> HDR_CLIP
      HdrTransfer.Hlg -> HLG_CLIP
    }

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

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val PORTRAIT = "apple_export_portrait.mp4"
    const val HDR_CLIP = "apple_export_hdr.mp4"
    const val HLG_CLIP = "apple_export_hdr_hlg.mp4"

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
    const val P3_TEXEL = 0xFFC08040.toInt()

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
    val RASTER = Size(4, 2)

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

    // Display P3 pictures in formats that keep their profile outside a PNG, left out where ImageIO cannot write one.
    val ENCODED =
      listOf("public.heic", "public.jpeg").mapNotNull { type ->
        val bytes = AppleDisplayP3.picture(type, RASTER, P3_TEXEL)
        if (bytes == null) println("skipping the $type badge, ImageIO cannot write it here")
        bytes?.let { painted("Display P3 $type", P3_TEXEL, it, colour = AppleDisplayP3.colour()) }
      }

    val ICC_TAGGED =
      IccPictures.ALL.map { picture ->
        val texel = if (picture == IccPictures.GREY) DARK_GREY else picture.texel
        painted(
          picture.name,
          texel,
          OverlayPixels.pngOf(RASTER, texel, picture.profile),
          fromFile = picture == IccPictures.DISPLAY_P3,
        )
      } + painted(P3_RED_NAME, RED, OverlayPixels.pngOf(RASTER, RED, IccPictures.DISPLAY_P3.profile)) + ENCODED

    val HEIC_STILLS =
      ColourPictures.HEICS_WITH_SIGNAL_VARIANTS + AppleStillSamples.BT709_MATRIX_OTHER_PRIMARIES +
        ColourPictures.HEIC_NCLX_BT709_FULL_EDGE_CHROMA

    // Every HEIC decodes through the raster's rewrite, and the grid's rewrite reaches the parameter sets its tiles
    // share.
    val DESCRIBED =
      ColourPictures.PNGS.map { painted(it.name, it.texel, it.pngOf(RASTER)) } +
        ColourPictures.HDR_PNGS.map { picture ->
          val bytes = picture.pngOf(RASTER)
          val samples = ShortArray(picture.texel.size) { picture.texel[it].toShort() }
          Picture(picture.name, bytes, RASTER, pictureColour(bytes), { listOf(samples) })
        } +
        (ColourPictures.AVIFS + ColourPictures.NCLX_BT709_FULL_EDGE_CHROMA).map { still ->
          val bytes = still.bytes()
          Picture("AVIF ${still.name}", bytes, STILL, pictureColour(bytes), { AppleStillSamples.of(still) })
        } +
        HEIC_STILLS.map { still ->
          val bytes = still.bytes()
          Picture(still.name, bytes, STILL, pictureColour(bytes), { AppleStillSamples.of(still) })
        } +
        ColourPictures.HEIC_NCLX_BT709_FULL.let { still ->
          val bytes = still.gridBytes()
          Picture("${still.name} grid", bytes, STILL, pictureColour(bytes), { AppleStillSamples.of(still) })
        }

    val SMPTE_240M_NCLX = CicpTag(BT709_PRIMARIES, SRGB_TRANSFER, SMPTE_240M, true)

    // A 4:4:4 HEIC whose stored samples ImageIO hands out and core's evaluator draws, and a 4:2:0 one landed through
    // the nearest matrix Apple's decoder reads, which resolves degraded.
    val LANDED =
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

    val PICTURES =
      listOf(
        painted("untagged", AuthoredColours.MID_GREEN, OverlayPixels.pngOf(RASTER, AuthoredColours.MID_GREEN, null)),
      ) +
        ICC_TAGGED + DESCRIBED + LANDED
  }
}
