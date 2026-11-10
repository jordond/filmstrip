package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Color
import dev.jordond.filmstrip.test.CODE_PATCH
import dev.jordond.filmstrip.test.eightBitFrameAt
import dev.jordond.filmstrip.test.videoSampleTimesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThanOrEqual
import kotlinx.coroutines.test.runTest
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * `eightBitFrameAt` hands back the colours a clip was drawn in, on every API level.
 *
 * A clip of colour bars is held bar by bar against the colour each bar was drawn in. Below API 30 the bitmap comes from
 * media3's `FrameExtractor`, from API 30 from `MediaMetadataRetriever`, so the same test holds whichever reader the
 * level uses.
 */
class AndroidFrameReaderMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun everyBarReadsTheColourItWasDrawnIn() =
    runTest(timeout = Media3ExportProbe.TIMEOUT) {
      val file = File(probe.context.cacheDir, "frame_reader_bars.mp4")
      probe.writeBarsClip(file, WIDTH, HEIGHT, BARS)
      val times = videoSampleTimesOf(file)

      listOf(times.first(), times[times.size / 4], times[times.size / 2], times[times.size * 3 / 4]).forEach { at ->
        val read = eightBitFrameAt(file, at)
        BARS.forEachIndexed { index, bar ->
          val fx = (index + HALF) / BARS.size
          ROWS.forEach { fy ->
            val patch = patchAt(read, fx, fy)
            withClue("the frame at $at, bar $index row $fy read ${patch.toList()} where ${bar.toList()} was drawn") {
              patch.indices.maxOf { abs(patch[it] / LEVELS - bar[it]) } shouldBeLessThanOrEqual LEVEL_TOLERANCE
            }
          }
        }
      }
    }

  private fun patchAt(
    frame: Bitmap,
    fx: Float,
    fy: Float,
  ): IntArray {
    val cx = (fx * frame.width).roundToInt()
    val cy = (fy * frame.height).roundToInt()
    val sums = LongArray(CHANNELS)
    for (y in cy - CODE_PATCH / 2 until cy + CODE_PATCH / 2) {
      for (x in cx - CODE_PATCH / 2 until cx + CODE_PATCH / 2) {
        val pixel = frame.getPixel(x, y)
        sums[0] += Color.red(pixel)
        sums[1] += Color.green(pixel)
        sums[2] += Color.blue(pixel)
      }
    }
    return IntArray(CHANNELS) { (sums[it].toFloat() / (CODE_PATCH * CODE_PATCH)).roundToInt() }
  }

  private companion object {
    const val LEVEL_TOLERANCE = 0.05
    const val LEVELS = 255.0
    const val CHANNELS = 3
    const val WIDTH = 640
    const val HEIGHT = 360
    const val HALF = 0.5f
    val ROWS = listOf(0.2f, 0.5f, 0.8f)

    // Saturated and mid colours, so a matrix or range read wrong moves at least one bar past the tolerance.
    val BARS =
      listOf(
        floatArrayOf(0.78f, 0.55f, 0.43f),
        floatArrayOf(0.42f, 0.72f, 0.46f),
        floatArrayOf(0.9f, 0.1f, 0.1f),
        floatArrayOf(0.1f, 0.2f, 0.85f),
        floatArrayOf(0.5f, 0.5f, 0.5f),
      )
  }
}
