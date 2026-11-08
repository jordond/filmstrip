package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.ColorLanding
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.assertHdrLandings
import dev.jordond.filmstrip.ffmpeg.support.assertNear
import dev.jordond.filmstrip.ffmpeg.support.assertSdrLandings
import dev.jordond.filmstrip.ffmpeg.support.centreOf
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.drawnRect
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HdrPngPicture
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.PngPicture
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Overlay pictures that describe their color some way other than an ICC profile alone, drawn by this ffmpeg in the
 * color core gives them.
 *
 * Every [ColorPictures] PNG, the sixteen-bit PQ and HLG ones among them, is exported over a flat fill on an SDR, a PQ
 * and an HLG grade and held against [pictureSrgbCodes] and [pictureLight] through the nits blend. ffmpeg's own SDR
 * picture branch is pinned to ignore every color chunk, since the codes core gives a tagged picture reach it as an
 * untagged PNG. One preview frame checks the preview draws a `cICP` picture the way the export does.
 */
class FfmpegOverlayColorTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `every color-described picture lands on an SDR export where core's sRGB codes put it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      fixture.assertSdrLandings(landings(), "filmstrip-color")
    }

  @Test
  fun `every color-described picture lands on a kept PQ grade where core's light puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      fixture.assertHdrLandings(landings(), HdrTransfer.Pq, "filmstrip-color")
    }

  @Test
  fun `every color-described picture lands on a kept HLG grade where core's light puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      fixture.assertHdrLandings(landings(), HdrTransfer.Hlg, "filmstrip-color")
    }

  // ffmpeg's PNG decoder tags a frame from cICP, gAMA, cHRM and sRGB but no stage of the branch converts on a tag, so
  // the merged frame is byte for byte the untagged picture's. A build that starts converting on one fails here first.
  @Test
  fun `the SDR picture branch draws every color chunk the way it draws an untagged picture`() {
    if (!fixture.available()) return
    val untagged = OverlayPixels.pngOf(OVERLAY_SIZE, ColorPictures.TEXEL)
    for (base in BASES) {
      val plain = branchOutput(untagged, base)
      for (picture in CHUNK_KINDS) {
        withClue("${picture.name} over $base") { branchOutput(picture.pngOf(OVERLAY_SIZE), base) shouldBe plain }
      }
    }
  }

  @Test
  fun `a cICP picture in a preview frame lands where core's sRGB codes put it`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val picture = ColorPictures.CICP_DISPLAY_P3
      val png = picture.pngOf(OVERLAY_SIZE)
      val overlay = ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)
      val composition =
        EditComposition(
          tracks = listOf(Track(listOf(Clip(MediaSource.of(fixture.landscape.absolutePath))))),
          effects = listOf(overlay),
        )
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
      val opened = engine.openPreview(composition, ExportSpec()).shouldBeInstanceOf<PreviewStreamResult.Opened>()
      val stream = opened.stream.shouldBeInstanceOf<FfmpegPreviewStream>()
      val frame =
        try {
          withClue("the preview delivered no frame") { stream.next().shouldNotBeNull() }
        } finally {
          stream.close()
        }

      val size = stream.size
      val (x, y) = centreOf(drawnRect(overlay, size, TimeRange.of(Duration.ZERO, 1.seconds), Duration.ZERO), size)
      val at = (y * size.width + x) * RGBA
      val read = Triple(frame[at].toInt() and 0xFF, frame[at + 1].toInt() and 0xFF, frame[at + 2].toInt() and 0xFF)
      val expected = channelsOf(pictureSrgbCodes(pictureColor(png), intArrayOf(picture.texel)).single())
      withClue("Display P3 leaves the texel where it was") { (channelsOf(picture.texel) != expected).shouldBeTrue() }
      assertNear(read, expected, "${picture.name} in a preview frame")
    }

  // Every eight-bit and sixteen-bit PNG, with where core puts its texel.
  private fun landings(): List<ColorLanding> =
    ColorPictures.PNGS.map(::landingOf) + ColorPictures.HDR_PNGS.map(::landingOf)

  private fun landingOf(picture: PngPicture): ColorLanding {
    val png = picture.pngOf(OVERLAY_SIZE)
    val color = pictureColor(png)
    val texel = intArrayOf(picture.texel)
    return ColorLanding(picture.name, png, pictureSrgbCodes(color, texel).single(), pictureLight(color, texel))
  }

  private fun landingOf(picture: HdrPngPicture): ColorLanding {
    val png = picture.pngOf(OVERLAY_SIZE)
    val color = pictureColor(png)
    val texel = ShortArray(picture.texel.size) { picture.texel[it].toShort() }
    return ColorLanding(picture.name, png, pictureSrgbCodes(color, texel).single(), pictureLight(color, texel))
  }

  // The frame this ffmpeg merges [png] onto through the SDR picture branch the catalogue lowers, over a black [base],
  // as the base format's raw planes. The branch is the one the catalogue lowers for the untagged picture.
  private fun branchOutput(
    png: ByteArray,
    base: String,
  ): ByteArray {
    val untagged = OverlayPixels.pngOf(OVERLAY_SIZE, ColorPictures.TEXEL)
    val spec = ImageOverlay(ImageSource.ofBytes(untagged), Corner.TopStart)
    val resolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, SDR)
    val fragment = resolution.shouldBeInstanceOf<EffectResolution.Resolved>().effect.fragment
    val chain =
      fragment.auxInputs
        .single()
        .chain
        .joinToString(",") { it.rendered() }
    val merge = checkNotNull(fragment.merge).rendered()
    val picture = File.createTempFile("filmstrip-branch", ".png").apply { writeBytes(png) }
    try {
      val format = base.substringAfter("format=").substringBefore(",")
      val command =
        listOf(
          hostFfmpeg,
          "-hide_banner",
          "-loglevel",
          "error",
          "-f",
          "lavfi",
          "-i",
          base,
          "-i",
          picture.absolutePath,
        ) +
          listOf("-filter_complex", "[1:v]$chain[o];[0:v][o]$merge,format=$format") +
          listOf("-frames:v", "1", "-f", "rawvideo", "-")
      val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
      val bytes = process.inputStream.readAllBytes()
      withClue("this ffmpeg merged nothing over $base") {
        (process.waitFor() == 0 && bytes.isNotEmpty()).shouldBeTrue()
      }
      return bytes
    } finally {
      picture.delete()
    }
  }

  private fun FilterNode.rendered(): String =
    if (arguments.isEmpty()) name else "$name=" + arguments.joinToString(":") { "${it.key}=${it.value}" }

  private companion object {
    const val RGBA = 4

    // One picture per color chunk kind: cICP, gAMA alone, gAMA with cHRM, and an sRGB chunk.
    val CHUNK_KINDS =
      listOf(
        ColorPictures.CICP_DISPLAY_P3,
        ColorPictures.GAMMA_LINEAR,
        ColorPictures.GAMMA_DISPLAY_P3,
        ColorPictures.SRGB_CHUNK,
      )

    // An eight-bit BT.709 base and a ten-bit PQ BT.2020 one, as lavfi sources the size of the frame [SDR] lays out.
    val BASES =
      listOf(
        "color=c=black:s=640x360:r=25,format=yuv420p",
        "color=c=black:s=640x360:r=25,format=yuv420p10le," +
          "setparams=color_primaries=bt2020:color_trc=smpte2084:colorspace=bt2020nc",
      )

    val FRAME = Size(640, 360)
    val SDR =
      Attributes(
        inputSize = FRAME,
        outputSize = FRAME,
        layoutSize = FRAME,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 25f,
        span = TimeRange.of(Duration.ZERO, 2.seconds),
      )
  }
}
