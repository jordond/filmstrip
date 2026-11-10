package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.filters.FilterEffectResolver
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegPlanner
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ChromaGrid
import dev.jordond.filmstrip.ffmpeg.internal.lowering.drawnAt
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.test.OrientedPictures
import dev.jordond.filmstrip.transform.internal.geometry.inWholePixelsOf
import dev.jordond.filmstrip.transform.internal.geometry.placementOf
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Where a clip that leaves a 16:9 output's bars lands, read off the graph against the shared placement.
 *
 * The clip's 21:9 canvas around a frame of odd height leaves the content an odd row down the output. Its tail pads it
 * there exactly when the chain is still in the 4:4:4 its canvas was built in, and on the even row above when nothing
 * moved the chain off 4:2:0.
 */
class CanvasChromaGridTest {
  private val landscape = MediaSource.of("/clips/landscape.mp4")
  private val odd = MediaSource.of("/clips/odd.mkv")

  private val infos = mapOf(landscape to info(Size(1920, 1080)), odd to info(ODD_SOURCE))

  // The overlay reads the canvas, so the canvas is built in 4:4:4 ahead of it and the chain is still in it at the tail.
  @Test
  fun `a clip tail after an odd canvas pads on the shared row`() {
    val effects = listOf(WIDE_CANVAS, logo())
    val (graph, output) = lowered(effects)
    val rect = placementOf(ODD_SOURCE, listOf(effects), output).drawnRect.inWholePixelsOf(output)
    withClue("the shared rect has to start on an odd row for this to tell, it starts on ${rect.top}") {
      (rect.top % 2) shouldBe 1
    }

    graph shouldContain "pad=w=${output.width}:h=${output.height}:x=${rect.left}:y=${rect.top}:color="
  }

  // With nothing reading the canvas none is built, so the chain stays in the 4:2:0 it was decoded in and pad's own
  // grid is the one the offset lands on.
  @Test
  fun `a clip tail with no canvas pads on the even row at or above the shared one`() {
    val effects = listOf(WIDE_CANVAS)
    val (graph, output) = lowered(effects)
    val rect = placementOf(ODD_SOURCE, listOf(effects), output).drawnRect.inWholePixelsOf(output)
    withClue("the shared rect has to start on an odd row for this to tell, it starts on ${rect.top}") {
      (rect.top % 2) shouldBe 1
    }

    val (x, y) = rect.drawnAt(ChromaGrid.Subsampled)
    graph shouldContain "pad=w=${output.width}:h=${output.height}:x=$x:y=$y:color="
  }

  // A Vhs leaves the frame in 4:2:0 whatever it was handed, so the tail after it lands on the even row above.
  @Test
  fun `a clip tail after a vhs pads on the even row at or above the shared one`() {
    val effects = listOf(WIDE_CANVAS, logo(), Vhs())
    val (graph, output) = lowered(effects)
    val rect = placementOf(ODD_SOURCE, listOf(effects), output).drawnRect.inWholePixelsOf(output)
    withClue("the shared rect has to start on an odd row for this to tell, it starts on ${rect.top}") {
      (rect.top % 2) shouldBe 1
    }

    val (x, y) = rect.drawnAt(ChromaGrid.Subsampled)
    graph shouldContain "pad=w=${output.width}:h=${output.height}:x=$x:y=$y:color="
  }

  // A Film ends in 4:2:0 on either of its paths, so the tail after it lands on the even row above too.
  @Test
  fun `a clip tail after a film pads on the even row at or above the shared one`() {
    listOf(Film(), Film(grain = 0.5f, halation = 0f), Film(grain = 0f, halation = 0.5f)).forEach { film ->
      val effects = listOf(WIDE_CANVAS, logo(), film)
      val (graph, output) = lowered(effects)
      val rect = placementOf(ODD_SOURCE, listOf(effects), output).drawnRect.inWholePixelsOf(output)
      withClue("the shared rect has to start on an odd row for this to tell, it starts on ${rect.top}") {
        (rect.top % 2) shouldBe 1
      }

      val (x, y) = rect.drawnAt(ChromaGrid.Subsampled)
      graph shouldContain "pad=w=${output.width}:h=${output.height}:x=$x:y=$y:color="
    }
  }

  // A Crt ends in 4:2:0 too, off its gbrap round trip.
  @Test
  fun `a clip tail after a crt pads on the even row at or above the shared one`() {
    val effects = listOf(WIDE_CANVAS, logo(), Crt())
    val (graph, output) = lowered(effects)
    val rect = placementOf(ODD_SOURCE, listOf(effects), output).drawnRect.inWholePixelsOf(output)
    withClue("the shared rect has to start on an odd row for this to tell, it starts on ${rect.top}") {
      (rect.top % 2) shouldBe 1
    }

    val (x, y) = rect.drawnAt(ChromaGrid.Subsampled)
    graph shouldContain "pad=w=${output.width}:h=${output.height}:x=$x:y=$y:color="
  }

  private fun lowered(effects: List<EffectSpec>): Pair<String, Size> {
    val composition = Composition(listOf(Track(listOf(Clip(landscape), Clip(odd, effects = effects)))))
    val lowering =
      FfmpegPlanner(TOOLCHAIN, listOf(BuiltInEffectResolver(), FilterEffectResolver()))
        .lower(composition, Ask(ExportSpec(), DEVICE, infos))
    val graph = withClue("the edit was refused: ${lowering.verdict}") { lowering.graph.shouldNotBeNull() }
    val output = withClue("the edit was refused: ${lowering.verdict}") { lowering.export.composition.shouldNotBeNull() }
    return graph.filterGraph to output.output.size
  }

  private fun logo(): ImageOverlay = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart)

  private fun info(size: Size): MediaInfo =
    MediaInfo(
      duration = 2.seconds,
      video =
        VideoTrackInfo(
          codedSize = size,
          displaySize = size,
          rotationDegrees = 0,
          pixelAspectRatio = 1f,
          frameRate = 30f,
          codec = trackCodecOf("avc1"),
          bitDepth = 8,
          colorSpace = ColorSpace.Bt709,
          hdrTransfer = null,
          bitrate = null,
          hasAlpha = false,
        ),
      audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
      isExportable = true,
    )

  private companion object {
    val ODD_SOURCE = Size(1366, 767)
    val WIDE_CANVAS = Crop(AspectRatio(21, 9), Fit.Contain)

    val DEVICE =
      DeviceCapabilities(
        video =
          listOf(
            VideoEncoderCapability(
              codec = VideoCodec.H264,
              encoderName = "libx264",
              maxSize = Size(7680, 4320),
              maxFrameRate = null,
              maxBitrate = null,
              isHardwareAccelerated = false,
              sizeAlignment = 2,
            ),
          ),
        audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(44_100, 48_000), 8)),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      )

    val TOOLCHAIN =
      Toolchain(
        ffmpeg = "ffmpeg",
        ffprobe = "ffprobe",
        version = FfmpegVersion("ffmpeg version 9.0.1", 9, 0),
        filters = CATALOGUE_FILTERS + VHS_FILTERS + FILM_FILTERS + CRT_FILTERS,
        encoders = setOf("libx264", "aac"),
      )
  }
}
