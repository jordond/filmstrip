package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.toDuration
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreMedia.CMTimeGetSeconds
import platform.CoreMedia.CMTimeRangeGetEnd
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalForeignApi::class)
class CoreMediaTimeTest {
  @Test
  fun `round trips a duration through the media timescale`() {
    listOf(Duration.ZERO, 1.seconds, 3.seconds, 90.seconds, 1_500.milliseconds).forEach { duration ->
      duration.toCMTime().toDuration() shouldBe duration
    }
  }

  // 600 makes a frame boundary at any rate filmstrip encodes a whole tick. If that stops holding,
  // spans stop meeting and the failure is a render error with no clip named in it.
  @Test
  fun `lands a frame boundary at every encoded rate on a whole tick`() {
    listOf(24, 25, 30, 60).forEach { rate ->
      val frame = (1.seconds / rate)
      CMTimeGetSeconds(frame.toCMTime()) shouldBe 1.0 / rate
    }
  }

  @Test
  fun `reads an invalid time as zero`() {
    (-1).seconds.toCMTime().toDuration() shouldBe Duration.ZERO
  }

  @Test
  fun `builds a range that ends where its start plus its duration says`() {
    CMTimeRangeGetEnd(timeRangeOf(2.seconds, 3.seconds)).toDuration() shouldBe 5.seconds
  }
}
