package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.os.Build
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.srgbToLinear
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.media3WorkingLight
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.media3HdrTexels
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.HDR_CLIP
import dev.jordond.filmstrip.media3.Media3ExportProbe.Companion.TIMEOUT
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.IccPictures
import dev.jordond.filmstrip.test.OverlayPixels.ENCODED_TOLERANCE
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import dev.jordond.filmstrip.test.OverlayPixels.pngOf
import dev.jordond.filmstrip.test.assumeEncodesHdr
import dev.jordond.filmstrip.test.firstTenBitFrame
import dev.jordond.filmstrip.test.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import org.junit.Assume.assumeTrue
import java.io.ByteArrayOutputStream
import kotlin.math.abs
import kotlin.test.Test

/**
 * Color described overlay pictures on real media3, each landing in the color its description gives it.
 *
 * Every [IccPictures] and [ColorPictures] PNG is read, the sixteen-bit PQ and HLG ones included. Every expected color
 * comes from [pictureSrgbCodes] on SDR and [pictureLight] on HDR, given core's read of the file, never from what
 * Android's own decoder makes of it. Every ICC picture is also carried in a JPEG and a WebP, the other containers an
 * overlay picture arrives in, and every [ColorPictures] HEIC with an `nclx` lands on the color the box gives it.
 *
 * An SDR reading is held against a reference overlay in the opposite bar, untagged and painted the codes
 * [pictureSrgbCodes] gives, so the encoder's handling of the color cancels out. The PQ readings are held to the
 * ten-bit codes of the light itself, and are skipped on a device that writes no HDR, as in
 * [AndroidOverlayHdrMediaTest].
 */
class AndroidOverlayIccMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun everyTaggedTexelReadTheWayMedia3ReadsItLandsOnItsPictureLight() {
    val misses =
      IccPictures.ALL.flatMap { picture ->
        val color = pictureColor(picture.pngOf(BADGE))
        HdrTransfer.entries.mapNotNull { transfer ->
          val texels = media3HdrTexels(intArrayOf(picture.texel), transfer, color)
          val light = pictureLight(color, intArrayOf(picture.texel))
          val expected = media3WorkingLight(light.copyOf(RGB), transfer)
          val off =
            CHANNEL_SHIFTS.indices.maxOf { channel ->
              val code = (texels.argb[0] ushr CHANNEL_SHIFTS[channel]) and BYTE
              val read = texels.hdrLuminance * srgbToLinear(code / MAX_CODE)
              abs(read - expected[channel]) / (texels.hdrLuminance * halfCodeStep(code))
            }
          "$picture on $transfer sits $off half code steps off its light".takeIf { off > 1f }
        }
      }

    withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
  }

  @Test
  fun everyTaggedPngLandsOnItsSrgbCodesOnAnSdrExport() =
    runTest(timeout = TIMEOUT) {
      val misses = taggedPngs().mapNotNull { sdrMiss(it.name, it.png, it.srgb) }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }

  @Test
  fun everyTaggedJpegLandsOnItsSrgbCodesOnAnSdrExport() =
    runTest(timeout = TIMEOUT) {
      val misses =
        IccPictures.ALL.mapNotNull { picture ->
          val untagged = compressed(picture.texel, Bitmap.CompressFormat.JPEG)
          val tagged = untagged.withJpegProfile(picture.profile)
          val expected = pictureSrgbCodes(pictureColor(tagged), intArrayOf(storedTexel(untagged)))[0]
          sdrMiss("$picture JPEG", tagged, expected)
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }

  @Test
  fun everyTaggedWebpLandsOnItsSrgbCodesOnAnSdrExport() =
    runTest(timeout = TIMEOUT) {
      val misses =
        IccPictures.ALL.mapNotNull { picture ->
          val tagged = compressed(picture.texel, losslessWebp()).withWebpProfile(picture.profile, BADGE)
          val expected = pictureSrgbCodes(pictureColor(tagged), intArrayOf(picture.texel))[0]
          sdrMiss("$picture WebP", tagged, expected)
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }

  // Android's HEIC decoder reads the matrix and range from the codestream rather than the nclx, so each lands on the
  // color its nclx gives only through the codestream the overlay decode rewrites before handing it over. A matrix the
  // decoder on this API level cannot read is reported by the plan, naming the matrix it lands the picture through at
  // its own range.
  @Test
  fun everyNclxHeicLandsOnTheColorItsNclxGivesOrItsReportedLimitOnAnSdrExport() {
    assumeTrue("Android decodes HEIC from API 28", Build.VERSION.SDK_INT >= Build.VERSION_CODES.P)
    val first = ColorPictures.HEICS.first().bytes()
    assumeTrue(HEIC_UNDECODED, BitmapFactory.decodeByteArray(first, 0, first.size) != null)

    runTest(timeout = TIMEOUT) {
      val misses =
        ColorPictures.HEICS_WITH_SIGNAL_VARIANTS.mapNotNull { picture ->
          val nclx = picture.nclx ?: return@mapNotNull null
          val heic = picture.bytes()
          val verdict = probe.filmstrip.plan(letterboxed(probe.fixture(), listOf(barMark(heic, Corner.TopStart))), SDR)
          val adjustments = (verdict as? Verdict.Degraded)?.adjustments.orEmpty()
          val landed = adjustments.firstNotNullOfOrNull { LANDED_THROUGH.find(it.message)?.groupValues?.get(1) }
          val (y, cb, cr) = picture.ycbcr
          val matrix = landed?.toInt() ?: nclx.matrix
          val rgb = ycbcrToRgb(y, cb, cr, matrix, nclx.primaries, nclx.transfer, nclx.fullRange)
          val stored = rgb.fold(OPAQUE_ALPHA) { argb, code -> (argb shl 8) or code }
          sdrMiss("$picture", heic, pictureSrgbCodes(pictureColor(heic), intArrayOf(stored))[0])
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }
  }

  // The top bar holds the picture opaque, the bottom bar the same picture at half opacity, so one export reads both
  // the light itself and its blend with the fill in display light.
  @Test
  fun everyTaggedPngLandsOnItsPictureLightOnAKeptPqGrade() =
    runTest(timeout = TIMEOUT) {
      val source = probe.fixture(HDR_CLIP)
      probe.filmstrip.assumeEncodesHdr()

      val fill = authoredHdrNits(FILL_ARGB)
      val misses =
        taggedPngs().flatMap { picture ->
          val opaque = barMark(picture.png, Corner.TopStart)
          val half = barMark(picture.png, Corner.BottomStart, HALF)
          val frame = firstTenBitFrame(probe.export(letterboxed(source, listOf(opaque, half)), HDR_SPEC))
          val light = picture.light.copyOf(RGB)

          listOf(opaque to 1f, half to HALF).mapNotNull { (mark, alpha) ->
            val (fx, fy) = mark.centreOn(frame.size)
            val expected = tenBitCodesFromSignal(HdrTransfer.Pq.signalFromNits(hdrSourceOver(fill, light, alpha)))
            val read = frame.codesAt(fx, fy)
            val off = expected.indices.maxOf { abs(expected[it] - read[it]) }
            "$picture at $alpha wrote ${read.toList()}, expected ${expected.toList()}, $off codes off"
              .takeIf { off > PATCH_NOISE }
          }
        }

      withClue(misses.joinToString("\n")) { misses.isEmpty() shouldBe true }
    }

  // Every expected value comes from core's read of the PNG itself, a sixteen-bit one at its own depth.
  private fun taggedPngs(): List<TaggedPng> =
    IccPictures.ALL.map { eightBit(it.name, it.texel, it.pngOf(BADGE)) } +
      ColorPictures.PNGS.map { eightBit(it.name, it.texel, it.pngOf(BADGE)) } +
      ColorPictures.HDR_PNGS.map { tagged ->
        val png = tagged.pngOf(BADGE)
        val color = pictureColor(png)
        val samples = ShortArray(RGBA) { tagged.texel[it].toShort() }
        TaggedPng(tagged.name, png, pictureLight(color, samples), pictureSrgbCodes(color, samples)[0])
      }

  private fun eightBit(
    name: String,
    texel: Int,
    png: ByteArray,
  ): TaggedPng {
    val color = pictureColor(png)
    val texels = intArrayOf(texel)
    return TaggedPng(name, png, pictureLight(color, texels), pictureSrgbCodes(color, texels)[0])
  }

  private class TaggedPng(
    val name: String,
    val png: ByteArray,
    val light: FloatArray,
    val srgb: Int,
  )

  // The tagged picture sits in the top bar and an untagged reference painted the expected codes in the bottom one,
  // both opaque over the same fill.
  private suspend fun sdrMiss(
    name: String,
    tagged: ByteArray,
    expected: Int,
  ): String? {
    val mark = barMark(tagged, Corner.TopStart)
    val reference = barMark(pngOf(BADGE, expected), Corner.BottomStart)
    val frame = probe.frame(probe.export(letterboxed(probe.fixture(), listOf(mark, reference))))

    val read = frame.averageOver(mark)
    val want = frame.averageOver(reference)
    val off = read.indices.maxOf { abs(read[it] - want[it]) }
    return "$name read $read where its reference ${expected.hex()} reads $want, $off codes off"
      .takeIf { off > ENCODED_TOLERANCE }
  }

  private fun Bitmap.averageOver(mark: ImageOverlay): List<Int> {
    val frame = frameSize()
    val core = mark.placedOn(frame, BADGE).core(frame)
    return averageAt((core.left + core.right) / 2f, (core.top + core.bottom) / 2f).toList()
  }

  private fun ImageOverlay.centreOn(frame: Size): Pair<Float, Float> {
    val core = placedOn(frame, BADGE).core(frame)
    return (core.left + core.right) / 2f to (core.top + core.bottom) / 2f
  }

  private fun barMark(
    picture: ByteArray,
    corner: Corner,
    opacity: Float = 1f,
  ) = ImageOverlay(ImageSource.Bytes(picture), corner, BAR_MARGIN, BAR_SCALE, opacity)

  private fun letterboxed(
    source: MediaSource,
    effects: List<EffectSpec>,
  ) = EditComposition(
    listOf(Track(listOf(Clip(source)))),
    listOf(Crop(AspectRatio.Square, fit = Fit.Contain)) + effects,
    fill = Fill.Solid(FILL_ARGB),
  )

  private fun compressed(
    argb: Int,
    format: Bitmap.CompressFormat,
  ): ByteArray {
    val bitmap = Bitmap.createBitmap(BADGE.width, BADGE.height, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(argb)
    val out = ByteArrayOutputStream()
    bitmap.compress(format, FULL_QUALITY, out)
    bitmap.recycle()
    return out.toByteArray()
  }

  @Suppress("DEPRECATION")
  private fun losslessWebp(): Bitmap.CompressFormat {
    val named = Build.VERSION.SDK_INT >= Build.VERSION_CODES.R
    return if (named) Bitmap.CompressFormat.WEBP_LOSSLESS else Bitmap.CompressFormat.WEBP
  }

  // The middle texel of an untagged picture as Android decodes it, the code a lossy encoder left there.
  private fun storedTexel(picture: ByteArray): Int {
    val bitmap = BitmapFactory.decodeByteArray(picture, 0, picture.size)
    return bitmap.getPixel(bitmap.width / 2, bitmap.height / 2).also { bitmap.recycle() }
  }

  // Half the distance to the neighbouring code on either side, in the linear light the code decodes to.
  private fun halfCodeStep(code: Int): Float {
    val below = srgbToLinear((code - 0.5f) / MAX_CODE)
    val above = srgbToLinear((code + 0.5f) / MAX_CODE)
    return (above - below) / 2f
  }

  private companion object {
    const val FILL_ARGB = AuthoredColors.MID_GREY
    val HDR_SPEC = ExportSpec(heightPx = 360, hdr = HdrMode.KeepHdr)

    const val HALF = 0.5f
    const val HEIC_UNDECODED = "Android's HEIC decoder hands back no frame for the ColorPictures HEIC stills"
    val LANDED_THROUGH = Regex("Android's HEIC decoder .* through matrix (\\d+) instead")
    val SDR = ExportSpec()
    const val FULL_QUALITY = 100
    const val RGB = 3
    const val RGBA = 4
    const val BYTE = 0xFF
    const val MAX_CODE = 255f
    val CHANNEL_SHIFTS = intArrayOf(16, 8, 0)

    // Small enough that, inset from a corner, it fits inside the bar a square crop leaves.
    const val BAR_MARGIN = 0.02f
    const val BAR_SCALE = 0.12f
  }
}

private fun Int.hex(): String = toUInt().toString(16)

private const val OPAQUE_ALPHA = 0xFF

/**
 * This JPEG with [profile] embedded as one `APP2` segment straight after its start marker, in place of any profile
 * the encoder wrote.
 */
private fun ByteArray.withJpegProfile(profile: ByteArray): ByteArray {
  val signature = "ICC_PROFILE\u0000".encodeToByteArray()
  val payload = signature + byteArrayOf(1, 1) + profile
  val length = payload.size + 2
  var kept = byteArrayOf(0xFF.toByte(), 0xD8.toByte(), 0xFF.toByte(), 0xE2.toByte()) + be16(length) + payload
  var at = 2
  while (u8(at + 1) != SOS) {
    val end = at + 2 + (u8(at + 2) shl 8 or u8(at + 3))
    val encoderProfile = u8(at + 1) == APP2 && copyOfRange(at + 4, at + 4 + signature.size).contentEquals(signature)
    if (!encoderProfile) kept += copyOfRange(at, end)
    at = end
  }
  return kept + copyOfRange(at, size)
}

/**
 * This lossless WebP rewritten with a `VP8X` header flagging [profile] and an `ICCP` chunk holding it ahead of its
 * image chunk, in place of any header or profile the encoder wrote.
 */
private fun ByteArray.withWebpProfile(
  profile: ByteArray,
  canvas: Size,
): ByteArray {
  var at = WEBP_HEADER
  while (String(this, at, 4, Charsets.ISO_8859_1) != "VP8L") at += 8 + (u32le(at + 4) + 1) / 2 * 2
  val image = copyOfRange(at, at + 8 + (u32le(at + 4) + 1) / 2 * 2)
  val header =
    byteArrayOf((WEBP_ICC_FLAG or WEBP_ALPHA_FLAG).toByte(), 0, 0, 0) + le24(canvas.width - 1) + le24(canvas.height - 1)
  val padded = if (profile.size % 2 == 0) profile else profile + 0.toByte()
  val chunks = riffChunk("VP8X", header) + riffChunk("ICCP", profile, padded) + image
  return "RIFF".encodeToByteArray() + le32(4 + chunks.size) + "WEBP".encodeToByteArray() + chunks
}

private fun riffChunk(
  fourcc: String,
  payload: ByteArray,
  padded: ByteArray = payload,
): ByteArray = fourcc.encodeToByteArray() + le32(payload.size) + padded

private fun ByteArray.u8(at: Int): Int = this[at].toInt() and 0xFF

private fun ByteArray.u32le(at: Int): Int = u8(at) or (u8(at + 1) shl 8) or (u8(at + 2) shl 16) or (u8(at + 3) shl 24)

private fun be16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

private fun le24(value: Int): ByteArray = byteArrayOf(value.toByte(), (value shr 8).toByte(), (value shr 16).toByte())

private fun le32(value: Int): ByteArray = le24(value) + (value shr 24).toByte()

private const val SOS = 0xDA
private const val APP2 = 0xE2
private const val WEBP_HEADER = 12
private const val WEBP_ICC_FLAG = 0x20
private const val WEBP_ALPHA_FLAG = 0x10
