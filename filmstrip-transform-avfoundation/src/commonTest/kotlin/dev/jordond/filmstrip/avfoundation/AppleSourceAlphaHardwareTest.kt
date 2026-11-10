package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.color.blurPlane
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.blendOverFill
import dev.jordond.filmstrip.transform.internal.geometry.blurPlan
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.ints.shouldBeInRange
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.io.encoding.Base64
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * A ProRes 4444 clip with a half transparent picture exported over a solid and a blurred fill, read against
 * [blendOverFill].
 *
 * The picture fills the frame, so the fill is reached only through the picture's own alpha. The source and the clear
 * fill are read off exports of the same picture at full and no alpha, so the codes carry the encoder's own rounding.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSourceAlphaHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a half transparent clip over a solid fill reads the shared blend`() =
    runTest(timeout = TIMEOUT) {
      val fill = Fill.Solid(SLATE)
      val opaque = exported(AlphaClips.OPAQUE, fill).average(LEFT_AT, MID_ROW)
      val clear = exported(AlphaClips.CLEAR, fill).average(LEFT_AT, MID_ROW)
      val half = exported(AlphaClips.HALF, fill).average(LEFT_AT, MID_ROW)

      withClue("the fill has to differ from the source or this cannot tell a blend from no blend") {
        distance(clear, opaque) shouldBeGreaterThan MIN_COVER_GAP
      }
      assertBlend(half, opaque, clear)
    }

  @Test
  fun `a half transparent clip over a blurred fill reads the shared blend of its own cover`() =
    runTest(timeout = TIMEOUT) {
      val fill = Fill.Blurred(radius = RADIUS, dim = 0f)
      val opaque = exported(AlphaClips.OPAQUE, fill)
      val half = exported(AlphaClips.HALF, fill)
      val size = Size(opaque.width, opaque.height)
      val sigma = fill.blurPlan(size).let { it.sigmaPx / it.width }
      val left = opaque.average(LEFT_AT, MID_ROW)
      val right = opaque.average(RIGHT_AT, MID_ROW)

      for (at in listOf(LEFT_AT, EDGE_AT, RIGHT_AT)) {
        val cover =
          listOf(left.first to right.first, left.second to right.second, left.third to right.third).map { (l, r) ->
            val plane =
              DoubleArray(size.width * size.height) {
                (if (it % size.width < size.width / 2) l else r) /
                  MAX_CODE
              }
            val blurred = blurPlane(plane, DoubleArray(plane.size) { 1.0 }, size.width, size.height, sigma)
            (blurred[(MID_ROW * size.height).toInt() * size.width + (at * size.width).toInt()] * MAX_CODE).roundToInt()
          }
        val source = opaque.average(at, MID_ROW)
        withClue("the cover has to differ from the source or this cannot tell a blend from no blend") {
          abs(cover[0] - source.first) shouldBeGreaterThan MIN_COVER_GAP
        }
        withClue("at $at, left $left, right $right") {
          assertBlend(half.average(at, MID_ROW), source, Triple(cover[0], cover[1], cover[2]))
        }
      }
    }

  private fun assertBlend(
    read: Triple<Int, Int, Int>,
    source: Triple<Int, Int, Int>,
    fill: Triple<Int, Int, Int>,
  ) {
    val expected =
      Triple(
        blendOverFill(source.first, HALF_ALPHA, fill.first),
        blendOverFill(source.second, HALF_ALPHA, fill.second),
        blendOverFill(source.third, HALF_ALPHA, fill.third),
      )
    withClue("read $read, expected $expected, source $source, fill $fill") {
      read.first shouldBeInRange (expected.first - TOLERANCE)..(expected.first + TOLERANCE)
      read.second shouldBeInRange (expected.second - TOLERANCE)..(expected.second + TOLERANCE)
      read.third shouldBeInRange (expected.third - TOLERANCE)..(expected.third + TOLERANCE)
    }
  }

  private suspend fun exported(
    clip: String,
    fill: Fill,
  ): FrameProbe {
    val composition: Composition =
      compositionOf {
        clip(MediaSource.Bytes(Base64.decode(clip)))
        fill(fill)
      }
    val output = NSTemporaryDirectory() + "filmstrip-apple-source-alpha-${clip.hashCode()}-${fill.hashCode()}.mp4"
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    withContext(Dispatchers.Default) {
      when (val finished = filmstrip.export(composition, ExportSpec(), MediaSink.of(output)).toList().last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }
    return codesOf(output).also { NSFileManager.defaultManager.removeItemAtPath(output, error = null) }
  }

  private companion object {
    val TIMEOUT = 2.seconds * 60
    const val SLATE = 0xFF2F4F6F.toInt()
    const val HALF_ALPHA = 128
    const val RADIUS = 0.5f
    const val LEFT_AT = 0.25f
    const val EDGE_AT = 0.4f
    const val RIGHT_AT = 0.75f
    const val MID_ROW = 0.5f
    const val MIN_COVER_GAP = 12
    const val MAX_CODE = 255.0

    // What a hardware encode moves a read by.
    const val TOLERANCE = 8
  }
}
