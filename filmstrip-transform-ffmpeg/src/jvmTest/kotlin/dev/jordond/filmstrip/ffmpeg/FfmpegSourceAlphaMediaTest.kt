package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.COLOR_READ_BITRATE
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.pixelAt
import dev.jordond.filmstrip.ffmpeg.support.proRes4444Clip
import dev.jordond.filmstrip.ffmpeg.support.proRes4444HalfClear
import dev.jordond.filmstrip.ffmpeg.support.proRes4444TwoTone
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.blendOverFill
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Exports a source with alpha over a solid and a blurred fill and reads the middle of the range, not only its ends.
 *
 * Every expectation comes from [blendOverFill], with the source and the fill read back from exports of the same clip
 * at full and no alpha so the codes already carry the encoder's own rounding.
 */
class FfmpegSourceAlphaMediaTest {
  private val fixture = FfmpegExportFixture()

  @Test
  fun `a half transparent clip over a solid fill reads the shared blend`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val fill = Fill.Solid(SOLID)
      val opaque = centre(exported(proRes4444Clip(SIDE, SIDE, 200, 40, 40, alpha = 255), fill))
      val clear = centre(exported(proRes4444Clip(SIDE, SIDE, 200, 40, 40, alpha = 0), fill))
      val half = centre(exported(proRes4444Clip(SIDE, SIDE, 200, 40, 40, alpha = 128), fill))

      assertBlend(half, opaque, 128, clear)
    }

  @Test
  fun `a half transparent clip over a blurred fill reads the shared blend of its own cover`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val fill = Fill.Blurred(radius = 0.5f, dim = 0f)
      val left = Triple(220, 40, 40)
      val right = Triple(40, 40, 220)
      val width = SIDE * 2
      val at = width / 4

      suspend fun frame(alpha: Int) = exported(proRes4444TwoTone(width, SIDE, left, right, alpha), fill)
      val opaque = frame(255)
      val source = pixelAt(opaque, at, SIDE / 2)
      val leftCodes = source
      val rightCodes = pixelAt(opaque, width - at, SIDE / 2)
      val plan = fill.blurPlan(Size(width, SIDE))
      val sigma = plan.sigmaPx / plan.width
      val cover =
        listOf(
          leftCodes.first to rightCodes.first,
          leftCodes.second to rightCodes.second,
          leftCodes.third to rightCodes.third,
        ).map { (l, r) ->
          val plane = DoubleArray(width * SIDE) { (if (it % width < width / 2) l else r).toDouble() }
          blurPlane(plane, DoubleArray(plane.size) { 1.0 }, width, SIDE, sigma)[(SIDE / 2) * width + at].roundToInt()
        }.let { Triple(it[0], it[1], it[2]) }
      val half = pixelAt(frame(128), at, SIDE / 2)

      withClue("the cover has to differ from the source or this cannot tell a blend from no blend") {
        cover.first shouldNotBe source.first
      }
      assertBlend(half, source, 128, cover)
    }

  @Test
  fun `a clip with a clear half is covered by a blur weighted by its alpha`() =
    runTest(timeout = TIMEOUT) {
      if (!fixture.available()) return@runTest

      val fill = Fill.Blurred(radius = RADIUS_CLEAR, dim = 0f)
      val colour = Triple(220, 60, 40)
      val width = SIDE * 2
      val drawn = exported(proRes4444HalfClear(width, SIDE, colour), fill)
      val whole = exported(proRes4444Clip(width, SIDE, colour.first, colour.second, colour.third, alpha = 255), fill)
      val source = pixelAt(whole, SIDE / 2, SIDE / 2)
      val plan = fill.blurPlan(Size(width, SIDE))
      val sigma = plan.sigmaPx / plan.width
      val alpha = DoubleArray(width * SIDE) { if (it % width < width / 2) 1.0 else 0.0 }

      for (at in listOf(CLEAR_AT, NEAR_EDGE_AT)) {
        val cover =
          listOf(source.first, source.second, source.third).map { level ->
            val plane = DoubleArray(alpha.size) { if (alpha[it] > 0.0) level.toDouble() else 0.0 }
            blurPlane(plane, alpha, width, SIDE, sigma)[(SIDE / 2) * width + at].roundToInt()
          }
        val read = pixelAt(drawn, at, SIDE / 2)
        withClue("at $at read $read, expected $cover") {
          read.first shouldBeInRange (cover[0] - TOLERANCE)..(cover[0] + TOLERANCE)
          read.second shouldBeInRange (cover[1] - TOLERANCE)..(cover[1] + TOLERANCE)
          read.third shouldBeInRange (cover[2] - TOLERANCE)..(cover[2] + TOLERANCE)
        }
      }
    }

  private fun assertBlend(
    read: Triple<Int, Int, Int>,
    source: Triple<Int, Int, Int>,
    alpha: Int,
    fill: Triple<Int, Int, Int>,
  ) {
    val expected =
      Triple(
        blendOverFill(source.first, alpha, fill.first),
        blendOverFill(source.second, alpha, fill.second),
        blendOverFill(source.third, alpha, fill.third),
      )
    withClue("read $read, expected $expected") {
      read.first shouldBeInRange (expected.first - TOLERANCE)..(expected.first + TOLERANCE)
      read.second shouldBeInRange (expected.second - TOLERANCE)..(expected.second + TOLERANCE)
      read.third shouldBeInRange (expected.third - TOLERANCE)..(expected.third + TOLERANCE)
    }
  }

  private fun centre(path: String) = pixelAt(path, SIDE / 2, SIDE / 2)

  private suspend fun exported(
    clip: File,
    fill: Fill,
  ): String {
    val composition: Composition =
      compositionOf {
        clip(MediaSource.of(clip.absolutePath))
        fill(fill)
      }
    val output = File.createTempFile("filmstrip-source-alpha", ".mp4").also { it.deleteOnExit() }
    output.delete()
    val plan =
      when (val verdict = fixture.filmstrip.plan(composition, ExportSpec(bitrate = COLOR_READ_BITRATE))) {
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
    return output.absolutePath
  }

  private companion object {
    const val SIDE = 64
    const val SOLID = 0xFF3060A0.toInt()
    const val TOLERANCE = 2
    const val RADIUS_CLEAR = 0.25f
    const val CLEAR_AT = 96
    const val NEAR_EDGE_AT = 76
  }
}
