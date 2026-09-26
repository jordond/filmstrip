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
import dev.jordond.filmstrip.ffmpeg.ExportConstants.HALF
import dev.jordond.filmstrip.ffmpeg.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.FULL_SCALE
import dev.jordond.filmstrip.ffmpeg.OverlayConstants.SIGNAL_DRIFT
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * Colour effects on an SDR export, read back from the written file against the shared colour matrix.
 */
class FfmpegColourEffectTest {
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
      assertTrue(
        ratio in HALF - SIGNAL_DRIFT..HALF + SIGNAL_DRIFT,
        "half brightness read $dimmed against $lit at full, a ratio of $ratio",
      )
    }

  // colorchannelmixer caps its gains at 2 and fails the whole graph above that rather than
  // clamping, so a factor past it has to lower to something else or no file is written.
  @Test
  fun `exports a factor past what a channel mixer would take`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val lit = exportedBrightness(1f) ?: return@runTest
      val brightened = exportedBrightness(3f) ?: return@runTest

      assertTrue(
        luminance(brightened) > luminance(lit),
        "a 3x brightness read $brightened against $lit at full",
      )
    }

  // A brightness is one more grade, so it goes out the same way. A factor of one carries no effect
  // at all, which is the reference every other factor is read against.
  private suspend fun exportedBrightness(factor: Float): Triple<Int, Int, Int>? =
    exportedGrade("brightness-$factor", Brightness(factor).takeIf { factor != 1f })

  // A contrast is three independent lines, so it reaches the file through the per-channel table.
  // Each channel is read back against what the shared matrix says it should be, which a contrast
  // pivoting on black would miss by tens of code values.
  //
  // A factor that flattens rather than one that stretches, because the fixture is colour bars and
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
  ) {
    val plain = exportedGrade(name, null) ?: return
    val measured = exportedGrade(name, spec) ?: return
    val expected = graded(plain, spec)

    assertTrue(
      measured.isNear(expected),
      "the graded centre read $measured against $expected from the shared matrix, on a plain $plain",
    )
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
  ): Triple<Int, Int, Int>? {
    val stage = if (spec == null) "plain" else "graded"
    val output = File.createTempFile("filmstrip-$name-$stage", ".mp4").also { it.delete() }
    val composition =
      compositionOf {
        clip(MediaSource.of(fixture.landscape.absolutePath))
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
    assertIs<ExportStatus.Success>(finished)

    val size = plan.output.size
    return pixelAt(output.absolutePath, size.width / 2, size.height / 2).also { output.delete() }
  }
}
