package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.then
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.HDR_REFERENCE_WHITE_NITS
import dev.jordond.filmstrip.media.HLG_NOMINAL_PEAK_NITS
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.minutes

/**
 * What a colour matrix does to a frame that keeps its grade, measured in light.
 *
 * Core Image holds an HDR frame as display referred linear light, and the lowering moves that into
 * the SDR signal a display at reference white would have been fed, runs the matrix there, and moves
 * it back. The figure under test comes from [transformNits], the reading every backend is held to,
 * rather than from a measurement taken once.
 *
 * A case fails when its fixture is missing, as in [AppleExportTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrColorGradeTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // The bias arm. A contrast pivots on mid grey, and where mid grey sits in light is the one thing
  // the reading fixes, so this is the case that tells a right white from a wrong one. Flattening
  // rather than stretching, so no channel of the fixture goes looking for the ceiling.
  @Test
  fun `a contrast on a PQ grade pivots where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val lit = exportedLight(source) { contrast(1f) }
      val graded = exportedLight(source) { contrast(SOFTER) }
      val matrix = matrixOf(Contrast(SOFTER))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      // Running the matrix on light itself is the mistake this rules out. It would pivot on a
      // grey five times brighter and land nowhere near.
      assertGraded(matrix, expected, graded, CONTRAST_DRIFT, lit, HdrTransfer.Pq)
    }

  // The mix arm. Without a bias the choice of white cancels, so what this checks is that the mix
  // ran on the encoded signal rather than on light.
  @Test
  fun `a saturation on a PQ grade mixes what the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val lit = exportedLight(source) { saturation(1f) }
      val graded = exportedLight(source) { saturation(MUTED) }
      val matrix = matrixOf(Saturation(MUTED))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      assertGraded(matrix, expected, graded, SATURATION_DRIFT, lit, HdrTransfer.Pq)
    }

  // The HLG arm, with both halves of the matrix in one run. Core Image is display referred whatever
  // the transfer, and the probe decodes HLG the same way the effect saw it, so the reading holds to
  // the encode's drift here too. The matrix run on scene light is the mistake HLG adds.
  @Test
  fun `an HLG grade lands where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Hlg)
      val lit = exportedLight(source) { contrast(1f) }
      val graded = exportedLight(source) { contrast(SOFTER).saturation(MUTED) }
      val matrix = matrixOf(Contrast(SOFTER), Saturation(MUTED))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Hlg)

      assertGraded(matrix, expected, graded, HLG_GRADE_DRIFT, lit, HdrTransfer.Hlg)
    }

  // Above white there is headroom rather than a ceiling. Where the signal the matrix asks for is
  // past what PQ can carry it clips, and that is the format's limit rather than the effect's, so it
  // is asserted as one instead of pretending the two exports match there.
  @Test
  fun `a channel pushed past the peak lands on the format's ceiling`() =
    runTest(timeout = TIMEOUT) {
      val source = hdrFixture(HdrTransfer.Pq)
      val lit = exportedLight(source) { contrast(1f) }
      val graded = exportedLight(source) { contrast(HARSH) }
      val expected = matrixOf(Contrast(HARSH)).transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      val clipped = lit.litChannels().filter { expected[it] >= PQ_PEAK_NITS * HEADROOM }
      assertTrue(clipped.isNotEmpty(), "no channel of $lit reached the ceiling under a contrast of $HARSH")
      clipped.forEach { channel ->
        assertTrue(
          graded[channel] > lit[channel] && graded[channel] >= PQ_PEAK_NITS * HEADROOM,
          "channel $channel asked for ${expected[channel]} nits and read ${graded[channel]}, short of the " +
            "format's own ceiling",
        )
      }
    }

  /**
   * The centre pixel of a graded export, in cd/m2.
   *
   * The reference frame carries the same effect at its identity, so it went through the same
   * encoder as the frame under test rather than down the copy path.
   */
  private suspend fun exportedLight(
    source: String,
    grade: EffectsBuilder.() -> Unit,
  ): List<Float> {
    val composition =
      compositionOf {
        clip(MediaSource.of(source))
        effects(grade)
      }
    val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

    try {
      val frame = hdrFrameOf(path) ?: error("could not decode a frame of the export")
      return frame.nitsAt(CENTRE, CENTRE)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  /**
   * Holds every lit channel of [read] to [expected] within [drift], as a fraction of it.
   *
   * It also checks each wrong domain [matrix] could run in on [lit] lands more than twice [drift] off [expected] on at
   * least one lit channel, so a read held to [drift] fails on that mistake. Light is a wrong domain on either
   * [transfer], and HLG adds scene light.
   */
  private fun assertGraded(
    matrix: ColorMatrix,
    expected: FloatArray,
    read: List<Float>,
    drift: Float,
    lit: List<Float>,
    transfer: HdrTransfer,
  ) {
    val channels = lit.litChannels()
    val wrongs =
      when (transfer) {
        HdrTransfer.Pq -> mapOf("light" to matrix.onLight(lit))
        HdrTransfer.Hlg -> mapOf("light" to matrix.onLight(lit), "HLG scene light" to matrix.onHlgScene(lit))
      }
    wrongs.forEach { (domain, wrong) ->
      val miss = channels.maxOf { abs(wrong[it] - expected[it]) / expected[it] }
      assertTrue(
        miss > 2 * drift,
        "the matrix run on $domain lands $miss off on ${hdrFixtureName(transfer)}, inside twice the $drift bound",
      )
    }

    channels.forEach { channel ->
      assertTrue(
        abs(read[channel] - expected[channel]) <= expected[channel] * drift,
        "channel $channel read ${read[channel]} nits and the reading asks for ${expected[channel]}, off by " +
          "${read[channel] / expected[channel]}: read $read, expected ${expected.toList()}, from $lit at identity",
      )
    }
  }

  // A bias of one is reference white when the matrix is read as running on light.
  private fun ColorMatrix.onLight(lit: List<Float>): List<Float> {
    val (r, g, b) = lit
    val white = HDR_REFERENCE_WHITE_NITS

    return listOf(
      rr * r + rg * g + rb * b + rBias * white,
      gr * r + gg * g + gb * b + gBias * white,
      br * r + bg * g + bb * b + bBias * white,
    )
  }

  // The shared reading run on HLG scene light as if it were display light, which the system gamma raises once more on
  // the way back to the display.
  private fun ColorMatrix.onHlgScene(lit: List<Float>): List<Float> {
    val (r, g, b) = lit.map { hlgSceneFromDisplayNits(it) * HLG_NOMINAL_PEAK_NITS }

    return transformNits(r, g, b, HdrTransfer.Hlg).map { hlgDisplayNitsFromScene(it / HLG_NOMINAL_PEAK_NITS) }
  }

  /**
   * The channels of a read that carry enough light to say anything, as in [AppleHdrBrightnessTest].
   */
  private fun List<Float>.litChannels(): List<Int> {
    val floor = maxOf(FLOOR_NITS, (maxOrNull() ?: 0f) * FLOOR_FRACTION)

    return indices.filter { this[it] >= floor }.also {
      assertTrue(it.isNotEmpty(), "every channel of the reference frame read as black, so nothing was measured")
    }
  }

  private fun matrixOf(vararg specs: EffectSpec): ColorMatrix =
    specs.fold(ColorMatrix.Identity) { folded, spec ->
      folded.then(checkNotNull(colorMatrixOf(spec)) { "${spec.id} is not a colour matrix" })
    }

  private companion object {
    val TIMEOUT = 5.minutes

    const val CENTRE = 0.5f
    const val SOFTER = 0.7f
    const val HARSH = 2f
    const val MUTED = 0.5f

    // How far a PQ contrast's read strays from the shared reading, as a fraction of it. Measured 0.56% on macOS and
    // 1.97% on the iOS simulator. Run on light it misses by 39.8% on macOS and 39.7% on the iOS simulator.
    const val CONTRAST_DRIFT = 0.04f

    // The same for a PQ saturation. Measured 0.64% on macOS and 1.96% on the iOS simulator. Run on light it misses by
    // 40.4% on macOS and 40.2% on the iOS simulator.
    const val SATURATION_DRIFT = 0.04f

    // The same for an HLG contrast and saturation together. Measured 0.22% on macOS and 0.90% on the iOS simulator.
    // Run on scene light it misses by 13% and on light by 42%.
    const val HLG_GRADE_DRIFT = 0.02f

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f

    const val FLOOR_NITS = 20f
    const val FLOOR_FRACTION = 0.3f
  }
}
