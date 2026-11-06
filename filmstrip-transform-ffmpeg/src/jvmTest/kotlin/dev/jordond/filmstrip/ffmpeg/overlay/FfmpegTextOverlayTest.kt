package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.CODE_TOLERANCE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.frameTime
import dev.jordond.filmstrip.ffmpeg.support.regionAverage
import dev.jordond.filmstrip.ffmpeg.support.tenBitCodesAt
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.OverlayPixels.hdrSourceOver
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Text burned in by the ffmpeg backend, read back out of the file and the preview where its plate lands.
 *
 * The plate is translucent grey over a flat letterbox bar, so every texel of its padding is the plate drawn source over
 * the bar and nothing else. Each expected value comes from the shared blend and the shared sampler.
 */
class FfmpegTextOverlayTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a plate on an SDR export lands where the source over draw of its codes puts it, inside its window only`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val exported = exported(composition(TextOverlay(TEXT, STYLE, Anchor.TopStart, visibleDuring = WINDOW)))
      val rate = exported.second.output.frameRate

      withClue("inside the window") { assertPlate(exported.first, frameTime(rate, rate), 1f) }
      for (outside in listOf(frameTime(rate / QUARTERS, rate), frameTime(rate * LATE_QUARTERS / QUARTERS, rate))) {
        withClue("outside the window at $outside") { assertPlate(exported.first, outside, 0f) }
      }
      exported.first.delete()
    }

  @Test
  fun `a fading plate reads the sampler's opacity a quarter and three quarters up the ramp, in the preview too`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val spec = TextOverlay(TEXT, STYLE, Anchor.TopStart, animation = FADE)
      val composition = composition(spec)
      val (file, plan) = exported(composition)
      val rate = plan.output.frameRate
      val span = TimeRange.of(Duration.ZERO, plan.duration)

      for (index in listOf(rate / QUARTERS, rate * (QUARTERS - 1) / QUARTERS)) {
        val at = frameTime(index, rate)
        val opacity = spec.frameAt(at, span).shouldNotBeNull().opacity
        withClue("frame $index") { assertPlate(file, at, opacity) }
      }

      val late = frameTime(rate * (QUARTERS - 1) / QUARTERS, rate)
      val previewed = previewTexel(composition, late)
      val opacity = spec.frameAt(late, span).shouldNotBeNull().opacity
      withClue("the preview at $late") { assertCodes(previewed, expectedCodes(opacity)) }
      file.delete()
    }

  @Test
  fun `a plate on a kept PQ grade lands on the signal of the shared linear blend`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest
      val (file, _) = exported(composition(TextOverlay(TEXT, STYLE, Anchor.TopStart), fixture.hdr), HDR_SPEC)

      val read = tenBitCodesAt(file, PROBE_CENTRE to PROBE_CENTRE)
      val blend = hdrSourceOver(authoredHdrNits(FILL), PLATE_COLOR, PLATE_ALPHA / CODE_MAX)
      val expected = tenBitCodesFromSignal(HdrTransfer.Pq.signalFromNits(blend))
      withClue("read ${read.toList()}, expected ${expected.toList()}") {
        read.indices.all { abs(read[it] - expected[it]) <= CODE_TOLERANCE } shouldBe true
      }
      file.delete()
    }

  // Where the plate's grey lands over the bar at [opacity] of its own alpha, as eight-bit codes.
  private fun expectedCodes(opacity: Float): List<Int> {
    val fill = FILL and BYTE_MASK
    return List(RGB) { OverlayPixels.sourceOver(fill, PLATE_GREY, opacity * PLATE_ALPHA / CODE_MAX) }
  }

  private fun assertPlate(
    file: File,
    at: Duration,
    opacity: Float,
  ) {
    val read = regionAverage(file.absolutePath, PROBE_AT, PROBE_AT, PROBE_SIDE, at)
    assertCodes(listOf(read.first, read.second, read.third), expectedCodes(opacity))
  }

  private fun assertCodes(
    read: List<Double>,
    expected: List<Int>,
  ) {
    read.indices.forEach { channel ->
      withClue("channel $channel read $read, expected $expected") {
        read[channel] shouldBe (expected[channel].toDouble() plusOrMinus OverlayPixels.ENCODED_TOLERANCE.toDouble())
      }
    }
  }

  // The plate's texel the preview frame at [at] carries, read off the RGBA frame the stream hands over.
  private suspend fun previewTexel(
    composition: EditComposition,
    at: Duration,
  ): List<Double> {
    val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
    val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
    val stream =
      engine
        .openPreview(composition, SDR_SPEC, at = at)
        .shouldBeInstanceOf<PreviewStreamResult.Opened>()
        .stream
        .shouldBeInstanceOf<FfmpegPreviewStream>()
    val frame =
      try {
        stream.next().shouldNotBeNull()
      } finally {
        stream.close()
      }
    val width = stream.size.width
    val probe = PROBE_AT until PROBE_AT + PROBE_SIDE
    val texels = probe.flatMap { y -> probe.map { x -> y * width + x } }
    return List(RGB) { channel -> texels.map { frame[it * RGBA + channel].toInt() and BYTE_MASK }.average() }
  }

  private fun composition(
    text: TextOverlay,
    source: File = fixture.landscape,
  ): EditComposition =
    compositionOf {
      track { clip(MediaSource.of(source.absolutePath)) { trim(TimeRange.of(Duration.ZERO, LENGTH)) } }
      effects {
        crop(AspectRatio.Portrait, fit = Fit.Contain)
        add(text)
      }
      fill(Fill.Solid(FILL))
    }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec = SDR_SPEC,
  ): Pair<File, ExportPlan> {
    val output = File.createTempFile("filmstrip-text", ".mp4").also { it.delete() }
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, spec)) {
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
    finished.shouldBeInstanceOf<ExportStatus.Success>()
    return output to plan
  }

  private companion object {
    const val TEXT = "Caption"

    // A tenth of the frame's height, so the plate's padding is wide enough to read a probe inside it.
    const val FONT_SIZE = 0.1f
    const val PLATE_GREY = 0xC8
    const val PLATE_ALPHA = 0x40
    val PLATE_COLOR = (0xFF shl 24) or (PLATE_GREY * 0x010101)
    val STYLE = TextStyle(fontSize = FONT_SIZE, backgroundColor = (PLATE_ALPHA shl 24) or (PLATE_GREY * 0x010101))
    val FILL = 0xFF303030.toInt()

    val LENGTH = 2.seconds
    val WINDOW = TimeRange.of(500.milliseconds, 1_500.milliseconds)
    val FADE: OverlayAnimation = fade(inDuration = 1.seconds)
    val SDR_SPEC = ExportSpec(heightPx = 480)
    val HDR_SPEC = ExportSpec(heightPx = 480, hdr = HdrMode.KeepHdr, bitrate = COLOR_READ_BITRATE)

    // A square inside the plate's padding, clear of every glyph.
    const val PROBE_AT = 4
    const val PROBE_SIDE = 4
    const val PROBE_CENTRE = 8

    const val QUARTERS = 4

    // Seven quarters of a second, past the window's close.
    const val LATE_QUARTERS = 7
    const val CODE_MAX = 255f
    const val BYTE_MASK = 0xFF
    const val RGB = 3
    const val RGBA = 4
  }
}
