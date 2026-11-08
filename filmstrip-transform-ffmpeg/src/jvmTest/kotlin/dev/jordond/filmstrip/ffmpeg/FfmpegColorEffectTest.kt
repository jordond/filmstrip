package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.FULL_SCALE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.SIGNAL_DRIFT
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.isNear
import dev.jordond.filmstrip.ffmpeg.support.luminance
import dev.jordond.filmstrip.ffmpeg.support.pixelAt
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Color effects on an SDR export, read back from the written file against the shared color matrix.
 */
class FfmpegColorEffectTest {
  private val fixture = FfmpegExportFixture()

  // Halving reads as half the signal against the same frame exported untouched. An additive
  // brightness of the same size, or one that multiplied linear light, would both land well outside
  // the band.
  @Test
  fun `halving brightness halves the signal`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val lit = exportedBrightness(1f) ?: return@runTest
      val dimmed = exportedBrightness(0.5f) ?: return@runTest

      val ratio = luminance(dimmed).toFloat() / luminance(lit).toFloat()
      withClue("half brightness read $dimmed against $lit at full, a ratio of $ratio") {
        (ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT).shouldBeTrue()
      }
    }

  // colorchannelmixer caps its gains at 2 and fails the whole graph above that rather than clamping, so a factor past
  // it has to lower to something else or no file is written. The fixture's bars already sit on the ends of the range
  // and cannot brighten, so this reads a dim flat clip with room left for the whole factor, where a factor dropped or
  // held at the cap lands well outside the drift.
  @Test
  fun `exports a factor past what a channel mixer would take`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val dim = channelsOf(DIM_COLOR)
      val answer = graded(dim, Brightness(PAST_MIXER_CAP))
      withClue("the cap's answer lands within the drift of $answer") {
        graded(dim, Brightness(MIXER_CAP)).isNear(answer).shouldBeFalse()
      }
      val clip = dimClip()

      assertGraded(Brightness(PAST_MIXER_CAP), "brightness", clip)
      clip.delete()
    }

  // A brightness is one more grade, so it goes out the same way. A factor of one carries no effect
  // at all, which is the reference every other factor is read against.
  private suspend fun exportedBrightness(factor: Float): Triple<Int, Int, Int>? =
    exportedGrade("brightness-$factor", Brightness(factor).takeIf { factor != 1f })

  // A contrast is three independent lines, so it reaches the file through the per-channel table.
  // Each channel is read back against what the shared matrix says it should be, which a contrast
  // pivoting on black would miss by tens of code values.
  //
  // A factor that flattens rather than one that stretches, because the fixture is color bars and
  // every channel of it already sits on an end of the range. Stretching those only reads back the
  // clamp, whatever matrix produced it.
  @Test
  fun `exports a contrast through the per-channel table`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertGraded(Contrast(0.5f), "contrast")
    }

  // A saturation mixes the channels, so it reaches the file through the lookup table written into
  // the scratch directory. Reading the pixel back is what proves the file was written, named and
  // found again.
  @Test
  fun `exports a saturation through the table written beside the graph`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      assertGraded(Saturation(0.5f), "saturation")
    }

  private suspend fun assertGraded(
    spec: EffectSpec,
    name: String,
    source: File = fixture.landscape,
  ) {
    val plain = exportedGrade(name, null, source) ?: return
    val measured = exportedGrade(name, spec, source) ?: return
    val expected = graded(plain, spec)

    withClue("the graded centre read $measured against $expected from the shared matrix, on a plain $plain") {
      measured.isNear(expected).shouldBeTrue()
    }
  }

  // What the shared matrix says the same pixel comes out as, in the code values the readback
  // reports, so a backend that lowered a different matrix fails here rather than drifting.
  private fun graded(
    plain: Triple<Int, Int, Int>,
    spec: EffectSpec,
  ): Triple<Int, Int, Int> {
    val matrix = checkNotNull(colorMatrixOf(spec))
    val (red, green, blue) =
      matrix.transform(plain.first / FULL_SCALE, plain.second / FULL_SCALE, plain.third / FULL_SCALE)

    return Triple(
      (red * FULL_SCALE).roundToInt(),
      (green * FULL_SCALE).roundToInt(),
      (blue * FULL_SCALE).roundToInt(),
    )
  }

  private suspend fun exportedGrade(
    name: String,
    spec: EffectSpec?,
    source: File = fixture.landscape,
  ): Triple<Int, Int, Int>? {
    val stage = if (spec == null) "plain" else "graded"
    val output = File.createTempFile("filmstrip-$name-$stage", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(source.absolutePath))
        if (spec != null) effects { add(spec) }
      }

    val verdict = fixture.filmstrip.plan(composition, ExportSpec())
    val plan =
      when (verdict) {
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

    val size = plan.output.size
    return pixelAt(output.absolutePath, size.width / 2, size.height / 2).also { output.delete() }
  }

  // A second of [DIM_COLOR], stored losslessly so the plain export is the first encode it goes through.
  private fun dimClip(): File {
    val clip = File.createTempFile("filmstrip-dim", ".mkv").also { it.delete() }
    val color = "%06X".format(DIM_COLOR and 0xFFFFFF)
    val command =
      listOf(hostFfmpeg, "-y", "-hide_banner", "-loglevel", "error", "-f", "lavfi") +
        listOf("-i", "color=c=0x$color:s=320x180:r=30:d=1,format=yuv420p", "-c:v", "ffv1", clip.absolutePath)
    val process = ProcessBuilder(command).redirectErrorStream(true).start()
    val log = process.inputStream.readAllBytes().decodeToString()
    withClue("ffmpeg could not write the dim clip: $log") { (process.waitFor() == 0 && clip.isFile).shouldBeTrue() }
    return clip
  }

  private companion object {
    // Every channel in the lower half of the range, so tripling it stays inside the range and off its ends.
    const val DIM_COLOR = 0xFF204030.toInt()
    const val MIXER_CAP = 2f
    const val PAST_MIXER_CAP = 3f
  }
}
