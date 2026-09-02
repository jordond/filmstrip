package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.CapabilitiesResult
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
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PQ_PEAK_NITS
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
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
 * Skipped when the fixtures are absent, as in [AppleHdrTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleHdrColorGradeTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  // The bias arm. A contrast pivots on mid grey, and where mid grey sits in light is the one thing
  // the reading fixes, so this is the case that tells a right white from a wrong one. Flattening
  // rather than stretching, so no channel of the fixture goes looking for the ceiling.
  @Test
  fun `a contrast on a PQ grade pivots where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val lit = exportedLight(source) { contrast(1f) }
      val graded = exportedLight(source) { contrast(SOFTER) }
      val expected = matrixOf(Contrast(SOFTER)).transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      lit.litChannels().forEach { channel ->
        assertNear(expected, graded, LIGHT_DRIFT, channel, lit)
        // Running the matrix on light itself is the mistake this rules out. It would pivot on a
        // grey five times brighter and land nowhere near.
        val linear = SOFTER * lit[channel] + (1f - SOFTER) * MID_GREY * HDR_REFERENCE_WHITE_NITS
        assertTrue(
          abs(graded[channel] - expected[channel]) < abs(graded[channel] - linear),
          "channel $channel read ${graded[channel]} nits, as close to a linear reading of $linear as to $expected",
        )
      }
    }

  // The mix arm. Without a bias the choice of white cancels, so what this checks is that the mix
  // ran on the encoded signal rather than on light.
  @Test
  fun `a saturation on a PQ grade mixes what the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

      val lit = exportedLight(source) { saturation(1f) }
      val graded = exportedLight(source) { saturation(MUTED) }
      val expected = matrixOf(Saturation(MUTED)).transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

      lit.litChannels().forEach { channel ->
        assertNear(expected, graded, LIGHT_DRIFT, channel, lit)
      }
    }

  // The HLG arm, with both halves of the matrix in one run. Core Image is display referred whatever
  // the transfer, and the probe decodes HLG the same way the effect saw it, so the reading holds to
  // the encode's drift here too: the fixture measured 0.2% off it on the brightest channel.
  @Test
  fun `an HLG grade lands where the shared reading says`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(HLG_CLIP) ?: return@runTest
      if (!encodesHdr()) return@runTest

      val lit = exportedLight(source) { contrast(1f) }
      val graded = exportedLight(source) { contrast(SOFTER).saturation(MUTED) }
      val matrix = matrixOf(Contrast(SOFTER), Saturation(MUTED))
      val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Hlg)

      lit.litChannels().forEach { channel ->
        assertNear(expected, graded, LIGHT_DRIFT, channel, lit)
      }
    }

  // Above white there is headroom rather than a ceiling. Where the signal the matrix asks for is
  // past what PQ can carry it clips, and that is the format's limit rather than the effect's, so it
  // is asserted as one instead of pretending the two exports match there.
  @Test
  fun `a channel pushed past the peak lands on the format's ceiling`() =
    runTest(timeout = TIMEOUT) {
      val source = fixture() ?: return@runTest
      if (!encodesHdr()) return@runTest

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

  private fun assertNear(
    expected: FloatArray,
    read: List<Float>,
    drift: Float,
    channel: Int,
    lit: List<Float>,
  ) {
    assertTrue(
      abs(read[channel] - expected[channel]) <= expected[channel] * drift,
      "channel $channel read ${read[channel]} nits and the reading asks for ${expected[channel]}, off by " +
        "${read[channel] / expected[channel]}: read $read, expected ${expected.toList()}, from $lit at identity",
    )
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

  private suspend fun encodesHdr(): Boolean =
    withContext(Dispatchers.Default) {
      (filmstrip.capabilities() as? CapabilitiesResult.Success)?.capabilities?.supportsHdrEncoding == true
    }

  private fun fixture(clip: String = CLIP): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$clip"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP = "apple_export_hdr.mp4"
    const val HLG_CLIP = "apple_export_hdr_hlg.mp4"
    const val CENTRE = 0.5f
    const val SOFTER = 0.7f
    const val HARSH = 2f
    const val MUTED = 0.5f
    const val MID_GREY = 0.5f

    // What a real HEVC encode and a 4:2:0 chroma round trip leave on a reading, as a fraction of it.
    const val LIGHT_DRIFT = 0.12f

    // How close to PQ's peak a reading has to get to count as having hit the format's ceiling
    // rather than the effect's answer.
    const val HEADROOM = 0.9f

    const val FLOOR_NITS = 20f
    const val FLOOR_FRACTION = 0.3f
  }
}
