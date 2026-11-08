package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * How many frames an AVFoundation export of a source at another rate writes, and when each is presented, read off the
 * written file.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleUnlikeRateMediaTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  // 25 fps into 30 shows one source frame on two slots in every six.
  @Test
  fun `a 25 fps source exported at 30 writes one frame on every output slot`() =
    runTest(timeout = TIMEOUT) { assertOnTheGrid(sourceRate = 25, name = "pal") }

  // 60 fps into 30 drops every other source frame.
  @Test
  fun `a 60 fps source exported at 30 writes one frame on every output slot`() =
    runTest(timeout = TIMEOUT) { assertOnTheGrid(sourceRate = 60, name = "high") }

  private suspend fun assertOnTheGrid(
    sourceRate: Int,
    name: String,
  ) {
    val source = NSTemporaryDirectory() + "filmstrip-apple-unlike-$name.mov"
    val length = 1.seconds * FRAMES / sourceRate
    withContext(Dispatchers.Default) {
      writeOffGridClip(source, frameRate = sourceRate, frames = FRAMES, endsAt = length)
    }
    val output = NSTemporaryDirectory() + "filmstrip-apple-unlike-$name.mp4"
    val composition = compositionOf { clip(MediaSource.of(source)) { trim(TimeRange.of(Duration.ZERO, length)) } }

    withContext(Dispatchers.Default) {
      val statuses = filmstrip.export(composition, ExportSpec(frameRate = RATE), MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
      }
    }

    val times = sampleTimesOf(output)
    withClue("$sourceRate fps at $RATE wrote ${times.size} frames: $times") {
      times.size.toLong() shouldBe outputSlotOf(length, RATE)
      times.forEachIndexed { index, time ->
        ((time - outputSlotTime(index.toLong(), RATE)).absoluteValue <= SAMPLE_TOLERANCE).shouldBeTrue()
      }
    }
    NSFileManager.defaultManager.removeItemAtPath(source, error = null)
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val RATE = 30
    const val FRAMES = 26
    val SAMPLE_TOLERANCE = 1.milliseconds
  }
}
