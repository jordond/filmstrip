package dev.jordond.filmstrip.effects.overlay

import android.graphics.Bitmap
import android.os.Build
import android.util.Half
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import dev.jordond.filmstrip.media.srgbToLinear
import dev.jordond.filmstrip.test.ColourPictures
import dev.jordond.filmstrip.test.IccPictures
import java.nio.ShortBuffer
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * Colour described pictures decoded by Android and prepared for a frame, read back the way media3 reads an overlay.
 *
 * Each texel of an HDR raster, times its HDR luminance multiplier, is the light media3 draws. That light is held to
 * [media3WorkingLight] of what [pictureLight] gives the picture's colour, so the decode, the colour it reports and
 * [rasterFor] are checked together with no encoder in the way. An SDR raster is held to [pictureSrgbCodes]. Every
 * [IccPictures] and [ColourPictures] PNG is read, the sixteen-bit ones at their own depth.
 */
class TaggedPictureRasterTest {
  @Test
  fun everyTaggedPictureRastersOntoItsPictureLightOnEveryHdrTransfer() {
    val misses =
      pictures().flatMap { picture ->
        val decoded = picture.decoded
        HdrTransfer.entries.mapNotNull { transfer ->
          val raster = decoded.bitmap.rasterFor(transfer, decoded.colour)
          val expected = media3WorkingLight(picture.light.copyOf(RGB), transfer)
          val texel = raster.bitmap.getPixel(1, 1)
          val off =
            CHANNEL_SHIFTS.indices.maxOf { channel ->
              val code = (texel ushr CHANNEL_SHIFTS[channel]) and BYTE
              val read = raster.hdrLuminance * srgbToLinear(code / MAX_CODE)
              abs(read - expected[channel]) / (raster.hdrLuminance * halfCodeStep(code))
            }
          "${picture.name} on $transfer sits $off half code steps off its light".takeIf { off > 1f }
        }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  @Test
  fun everyTaggedPictureRastersOntoItsSrgbCodesOnAnSdrFrame() {
    val misses =
      pictures().mapNotNull { picture ->
        val raster = picture.decoded.bitmap.rasterFor(transfer = null, picture.decoded.colour)
        val read = raster.bitmap.getPixel(1, 1)
        "${picture.name} wrote ${read.hex()} where core gives ${picture.srgb.hex()}".takeIf { read != picture.srgb }
      }

    assertTrue(misses.isEmpty(), misses.joinToString("\n"))
  }

  // Every expected value comes from core's read of the file itself. A sixteen-bit picture decodes to half floats, so
  // its expected value is core's read of the samples the decode holds. HiddenColourDescriptionsTest holds those to the
  // file's own.
  private fun pictures(): List<Picture> =
    IccPictures.ALL.map { eightBit(it.name, it.texel, it.pngOf(SIDE)) } +
      ColourPictures.PNGS.map { eightBit(it.name, it.texel, it.pngOf(SIDE)) } +
      ColourPictures.HDR_PNGS.map { tagged ->
        val png = tagged.pngOf(SIDE)
        val decoded = decode(tagged.name, png)
        val colour = pictureColour(png)
        val samples = decoded.bitmap.firstSamples()
        Picture(tagged.name, decoded, pictureLight(colour, samples), pictureSrgbCodes(colour, samples)[0])
      }

  private fun eightBit(
    name: String,
    texel: Int,
    png: ByteArray,
  ): Picture {
    val colour = pictureColour(png)
    val texels = intArrayOf(texel)
    return Picture(name, decode(name, png), pictureLight(colour, texels), pictureSrgbCodes(colour, texels)[0])
  }

  private fun decode(
    name: String,
    png: ByteArray,
  ): DecodedPicture = assertNotNull(ImageSource.Bytes(png).decode(context = null), name)

  // The first texel's samples, at sixteen bits from a half float bitmap or eight bits times 257 from any other.
  private fun Bitmap.firstSamples(): ShortArray {
    if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O || config != Bitmap.Config.RGBA_F16) {
      val argb = getPixel(0, 0)
      return ShortArray(RGBA) { (((argb ushr SAMPLE_SHIFTS[it]) and BYTE) * EIGHT_TO_SIXTEEN).toShort() }
    }
    val halves = ShortArray(width * height * RGBA)
    copyPixelsToBuffer(ShortBuffer.wrap(halves))
    return ShortArray(RGBA) { (Half.toFloat(halves[it]).coerceIn(0f, 1f) * MAX_SAMPLE).roundToInt().toShort() }
  }

  private class Picture(
    val name: String,
    val decoded: DecodedPicture,
    val light: FloatArray,
    val srgb: Int,
  )

  // Half the distance to the neighbouring code on either side, in the linear light the code decodes to.
  private fun halfCodeStep(code: Int): Float {
    val below = srgbToLinear((code - 0.5f) / MAX_CODE)
    val above = srgbToLinear((code + 0.5f) / MAX_CODE)
    return (above - below) / 2f
  }

  private companion object {
    val SIDE = Size(4, 4)
    const val RGB = 3
    const val RGBA = 4
    const val MAX_SAMPLE = 65535f
    const val EIGHT_TO_SIXTEEN = 257
    val SAMPLE_SHIFTS = intArrayOf(16, 8, 0, 24)
    const val BYTE = 0xFF
    const val MAX_CODE = 255f
    val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)
  }
}
