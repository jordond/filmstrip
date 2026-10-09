package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.HdrRender.attributes
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPictureDecoder
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.media.tenBitCodesFromSignal
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColours.MID_GREY
import dev.jordond.filmstrip.test.HdrOverlayCells.WHITE
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import java.io.ByteArrayOutputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay pictures the JDK cannot decode, drawn on a kept HDR grade.
 *
 * ffmpeg reads these itself on an SDR grade, so a grade has to draw every one this ffmpeg decodes. Each picture is
 * encoded by the ffmpeg under test, and a format its build cannot write is skipped. The colour a picture is expected to
 * land at is the one ffmpeg decodes out of it, read back independently of the code under test, and the landing comes
 * from [authoredHdrNits], [hdrSourceOver], [signalFromNits] and [tenBitCodesFromSignal].
 */
class FfmpegOverlayHdrFormatTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a lossless WebP still on a kept PQ grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertLands("webp", listOf("-c:v", "libwebp", "-lossless", "1")) }

  @Test
  fun `a lossy WebP still on a kept PQ grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertLands("webp", listOf("-c:v", "libwebp", "-quality", "90")) }

  @Test
  fun `an AVIF still on a kept PQ grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertLands("avif", listOf("-c:v", "libaom-av1", "-still-picture", "1")) }

  @Test
  fun `a JPEG XL still on a kept PQ grade lands where the shared light puts its colour`() =
    runTest(timeout = TIMEOUT) { assertLands("jxl", listOf("-c:v", "libjxl")) }

  // A HEIC is read by the mov demuxer only from ffmpeg 7.1 on, so this checks the grade refuses it exactly where the
  // ffmpeg under test cannot decode it, and draws it where it can.
  @Test
  fun `a HEIC still on a kept PQ grade is drawn exactly when this ffmpeg decodes it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val heic = File(fixture.fixtures, HEIC_FIXTURE)
      assertTrue(heic.isFile, "no $HEIC_FIXTURE among the downloaded fixtures")
      assertNull(ImageIO.read(heic), "the JDK decodes HEIC here, so this does not reach ffmpeg's decode")
      val overlay = ImageOverlay(ImageSource.of(heic.absolutePath), Corner.TopStart)
      val drawn = decodedCentreOrNull(heic)
      val resolution = resolve(overlay, HdrTransfer.Pq)
      if (drawn == null) {
        assertIs<EffectResolution.Unsupported>(resolution, "this ffmpeg cannot decode HEIC, so SDR fails on it too")
        return@runTest
      }
      assertIs<EffectResolution.Resolved>(resolution, "this ffmpeg decodes HEIC, so a grade draws it")
      if (fixture.encodesHdr()) assertExportLands("heic", overlay, drawn)
    }

  // The same texels through both decodes, one in the JDK and one in ffmpeg, so neither route colour manages what the
  // other does not. Every texel keeps some alpha, since a lossless WebP may rewrite the colour under a clear one.
  @Test
  fun `an sRGB PNG and the same picture as a lossless WebP carry the same texels onto the grade`() {
    if (!fixture.available()) return
    val texels = intArrayOf(WHITE, MID_GREY, MID_GREEN, DODGER_BLUE, HALF_ORANGE, QUARTER_WHITE)
    val image = BufferedImage(3, 2, BufferedImage.TYPE_INT_ARGB).apply { setRGB(0, 0, 3, 2, texels, 0, 3) }
    val png = ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray()
    val webp = encoded(png, "webp", listOf("-c:v", "libwebp", "-lossless", "1")) ?: return

    for (transfer in HdrTransfer.entries) {
      val fromJdk = mappedRaster(ImageSource.ofBytes(png), transfer)
      val fromFfmpeg = mappedRaster(ImageSource.of(webp.absolutePath), transfer)
      assertEquals(fromJdk.width to fromJdk.height, fromFfmpeg.width to fromFfmpeg.height)
      texels.indices.forEach { index ->
        val jdk = fromJdk.getPixel(index % 3, index / 3, IntArray(4))
        val ffmpeg = fromFfmpeg.getPixel(index % 3, index / 3, IntArray(4))
        assertTrue(
          jdk.indices.all { abs(jdk[it] - ffmpeg[it]) <= 1 },
          "$transfer texel $index read ${ffmpeg.toList()} through ffmpeg and ${jdk.toList()} through the JDK",
        )
      }
    }
    webp.delete()
  }

  @Test
  fun `a picture neither the JDK nor this ffmpeg decodes is refused on a kept grade and says so`() {
    if (!fixture.available()) return
    val garbage = ImageOverlay(ImageSource.ofBytes("not a picture".encodeToByteArray()), Corner.TopStart)

    for (transfer in HdrTransfer.entries) {
      val refused = assertIs<EffectResolution.Unsupported>(resolve(garbage, transfer), "$transfer draws garbage")
      assertTrue("ffmpeg" in refused.message, "$transfer: ${refused.message}")
    }
  }

  private suspend fun assertLands(
    extension: String,
    encode: List<String>,
  ) {
    if (!fixture.encodesHdr()) return
    val picture = encoded(pngOf(MID_GREEN, OVERLAY_SIZE), extension, encode) ?: return
    assertNull(ImageIO.read(picture), "the JDK decodes $extension here, so this does not reach ffmpeg's decode")
    assertExportLands(extension, ImageOverlay(ImageSource.ofBytes(picture.readBytes()), Corner.TopStart))
    picture.delete()
  }

  private suspend fun assertExportLands(
    what: String,
    overlay: ImageOverlay,
    drawn: Int = decodedCentreOf(overlay.image),
  ) {
    val exported = exportedOverFill(HdrTransfer.Pq, MID_GREY, overlay)
    val read = tenBitCodesAt(exported.file, probeOf(exported.plan.output.size, exported.plan.duration))
    val expected = expectedCodes(HdrTransfer.Pq, MID_GREY, drawn)
    assertTrue(
      read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE },
      "$what read ${read.toList()}, expected ${expected.toList()} for ${drawn.toUInt().toString(16)}",
    )
    exported.file.delete()
  }

  private fun decodedCentreOf(image: ImageSource): Int {
    val bytes = assertIs<ImageSource.Bytes>(image).bytes
    val file = File.createTempFile("filmstrip-format", ".bin").apply { writeBytes(bytes) }
    return decodedCentre(file).also { file.delete() }
  }

  private fun mappedRaster(
    image: ImageSource,
    transfer: HdrTransfer,
  ): Raster {
    val resolved = assertIs<EffectResolution.Resolved>(resolve(ImageOverlay(image, Corner.TopStart), transfer))
    val mapped =
      assertIs<ImageSource.Bytes>(
        resolved.effect.fragment.auxInputs
          .first()
          .image,
      ).bytes
    return ImageIO.read(ByteArrayInputStream(mapped)).raster
  }

  // The catalogue as this backend hands it to the planner, decoding through the ffmpeg on PATH that the fixtures and
  // every read-back here run.
  private fun resolve(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): EffectResolution? =
    BuiltInEffectResolver(FfmpegPictureDecoder("ffmpeg", "ffprobe"))
      .resolve(spec, CAPABILITIES, attributes(transfer, 2.seconds))

  private fun expectedCodes(
    transfer: HdrTransfer,
    background: Int,
    colour: Int,
  ): IntArray = tenBitCodesFromSignal(transfer.signalFromNits(hdrSourceOver(authoredHdrNits(background), colour, 1f)))

  // The overlay's centre, which sits inside the top letterbox bar on the fixture's portrait frame.
  private fun probeOf(
    output: Size,
    duration: Duration,
  ): Pair<Int, Int> {
    val spec = ImageOverlay(ImageSource.ofBytes(pngOf(MID_GREEN, OVERLAY_SIZE)), Corner.TopStart)
    val rect = drawnRect(spec, output, TimeRange.of(Duration.ZERO, duration), Duration.ZERO)
    return centreOf(rect, output)
  }

  private suspend fun exportedOverFill(
    transfer: HdrTransfer,
    fill: Int,
    overlay: ImageOverlay,
  ): ExportedOverlay {
    val source = if (transfer == HdrTransfer.Pq) fixture.hdr else fixture.hdrHlg
    val output = File.createTempFile("filmstrip-hdr-format", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        track { clip(MediaSource.of(source.absolutePath)) }
        effects {
          crop(AspectRatio.Portrait, fit = Fit.Contain)
          add(overlay)
        }
        fill(Fill.Solid(fill))
      }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(hdr = HdrMode.KeepHdr))) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
      }
    val finished =
      fixture.filmstrip
        .export(plan, MediaSink.of(output.absolutePath))
        .toList()
        .last()
    if (finished is ExportStatus.Failure) error(finished.error.message)
    assertIs<ExportStatus.Success>(finished)
    return ExportedOverlay(output, plan)
  }

  private companion object {
    const val DODGER_BLUE = 0xFF1E90FF.toInt()
    const val HALF_ORANGE = 0x80FF8020.toInt()
    const val QUARTER_WHITE = 0x40FFFFFF
    const val HEIC_FIXTURE = "overlay_mid_green.heic"
  }
}

// [png] re-encoded by ffmpeg with [encode], or null when this build has no encoder for it.
internal fun encoded(
  png: ByteArray,
  extension: String,
  encode: List<String>,
): File? {
  val source = File.createTempFile("filmstrip-format", ".png").apply { writeBytes(png) }
  val target = File.createTempFile("filmstrip-format", ".$extension").also { it.delete() }
  val process =
    ProcessBuilder(
      listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-i", source.absolutePath) + encode +
        listOf("-frames:v", "1", target.absolutePath),
    ).redirectErrorStream(true).start()
  process.inputStream.readAllBytes()
  val code = process.waitFor()
  source.delete()
  return target.takeIf { code == 0 && it.length() > 0 }
}

// The straight ARGB ffmpeg decodes at the centre of [picture], or null when it cannot decode it.
private fun decodedCentreOrNull(picture: File): Int? {
  val process =
    ProcessBuilder(
      "ffmpeg",
      "-hide_banner",
      "-loglevel",
      "error",
      "-i",
      picture.absolutePath,
      "-frames:v",
      "1",
      "-vf",
      "format=rgba,crop=1:1:iw/2:ih/2",
      "-f",
      "rawvideo",
      "-pix_fmt",
      "rgba",
      "-",
    ).start()
  val texel = process.inputStream.readAllBytes()
  process.errorStream.readAllBytes()
  if (process.waitFor() != 0 || texel.size != 4) return null
  val (red, green, blue, alpha) = texel.map { it.toInt() and 0xFF }
  return (alpha shl 24) or (red shl 16) or (green shl 8) or blue
}

private fun decodedCentre(picture: File): Int =
  checkNotNull(decodedCentreOrNull(picture)) { "ffmpeg decoded nothing from $picture" }
