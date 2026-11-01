package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.contrast
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
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.math.abs
import kotlin.test.assertIs
import kotlin.test.assertTrue

/**
 * The centre pixel of [source] exported keeping its grade under [grade], in cd/m2.
 *
 * A reference frame carries a colour effect at its identity, so it goes through the same encoder as the frame under
 * test rather than down the copy path. Fails the calling test when the plan is refused, the export fails or no frame
 * decodes, naming the grade by [at] when one is given.
 */
@OptIn(ExperimentalForeignApi::class)
internal suspend fun Filmstrip.exportedGradeLight(
  source: String,
  at: String? = null,
  grade: EffectsBuilder.() -> Unit,
): List<Float> {
  val composition =
    compositionOf {
      clip(MediaSource.of(source))
      effects(grade)
    }
  val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
  val plan =
    when (val verdict = plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error("refused: ${verdict.reasons.joinToString { it.message }}")
    }

  val finished = withContext(Dispatchers.Default) { export(plan, MediaSink.Temporary).toList() }.last()
  if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
  val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

  try {
    val frame = hdrFrameOf(path) ?: error("could not decode a frame of the export${at?.let { " at $it" }.orEmpty()}")
    return frame.nitsAt(GRADE_CENTRE, GRADE_CENTRE)
  } finally {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }
}

/**
 * Exports the PQ fixture under a softening contrast and holds it to [transformNits] of [lit], the same fixture's
 * centre at an identity grade.
 *
 * The bias arm of the matrix. A contrast pivots on mid grey, and where mid grey sits in light is the one thing the
 * reading fixes, so this is the case that tells a right white from a wrong one. Running the matrix on light itself
 * would pivot on a grey five times brighter and land nowhere near.
 */
internal suspend fun Filmstrip.assertSofterContrastPivots(lit: List<Float>) {
  val graded = exportedGradeLight(hdrFixture(HdrTransfer.Pq)) { contrast(SOFTER_CONTRAST) }
  val matrix = matrixOf(Contrast(SOFTER_CONTRAST))
  val expected = matrix.transformNits(lit[0], lit[1], lit[2], HdrTransfer.Pq)

  assertGraded(matrix, expected, graded, CONTRAST_DRIFT, lit, HdrTransfer.Pq)
}

/**
 * Holds every lit channel of [read] to [expected] within [drift], as a fraction of it.
 *
 * It also checks each wrong domain [matrix] could run in on [lit] lands more than twice [drift] off [expected] on at
 * least one lit channel, so a read held to [drift] fails on that mistake. Light is a wrong domain on either
 * [transfer], and HLG adds scene light.
 */
internal fun assertGraded(
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

/**
 * The channels of a read that carry enough light to say anything.
 *
 * Measured against the brightest channel as well as against an absolute floor. A saturated colour carries its dim
 * channel through 4:2:0 chroma, where the slop is a fraction of the bright channels rather than of the dim one, so a
 * channel far below them is moved more by the encode than by the effect. Fails the calling test when every channel
 * reads as black.
 */
internal fun List<Float>.litChannels(): List<Int> {
  val floor = maxOf(FLOOR_NITS, (maxOrNull() ?: 0f) * FLOOR_FRACTION)

  return indices.filter { this[it] >= floor }.also {
    assertTrue(it.isNotEmpty(), "every channel of the reference frame read as black, so nothing was measured")
  }
}

/**
 * The matrix [specs] fold to, applied in order.
 *
 * Fails the calling test when a spec is not a colour matrix.
 */
internal fun matrixOf(vararg specs: EffectSpec): ColorMatrix =
  specs.fold(ColorMatrix.Identity) { folded, spec ->
    folded.then(checkNotNull(colorMatrixOf(spec)) { "${spec.id} is not a colour matrix" })
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

private const val GRADE_CENTRE = 0.5f

// A contrast that flattens rather than stretches, so no channel of the fixture goes looking for the ceiling.
internal const val SOFTER_CONTRAST = 0.7f

// How far a PQ contrast's read strays from the shared reading, as a fraction of it. Measured 0.56% on macOS and 1.97%
// on the iOS simulator. Run on light it misses by 39.8% on macOS and 39.7% on the iOS simulator.
private const val CONTRAST_DRIFT = 0.04f

// The least light a channel carries before it says anything about a grade.
internal const val FLOOR_NITS = 20f
private const val FLOOR_FRACTION = 0.3f
