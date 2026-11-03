package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.time.MEDIA_TIMESCALE
import dev.jordond.filmstrip.avfoundation.internal.time.timeRangeOf
import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
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

  // The timescale makes a frame boundary at every rate filmstrip is likely to encode a whole tick, 48, 90 and 144 fps
  // among them. If that stops holding, spans stop meeting and the failure is a render error with no clip named in it.
  @Test
  fun `lands a frame boundary at every encoded rate on a whole tick`() {
    listOf(24, 25, 30, 48, 60, 90, 144).forEach { rate ->
      val frame = (1.seconds / rate)
      CMTimeGetSeconds(frame.toCMTime()) shouldBe 1.0 / rate
    }
  }

  // A slot at 48 fps is 75 ticks, so every slot lands on its own tick rather than being rounded onto one.
  @Test
  fun `lands every slot at 48 fps on its own tick`() {
    outputSlotTime(1, WHOLE_TICK_RATE).toCMTime().useContents { value } shouldBe 75L
    (0L..WHOLE_TICK_RATE).forEach { slot ->
      outputSlotTime(slot, WHOLE_TICK_RATE).toCMTime().useContents { value } shouldBe
        slot * MEDIA_TIMESCALE / WHOLE_TICK_RATE
    }
  }

  // 32 does not divide the timescale, so a slot at 32 fps lands on its nearest tick rather than on the frame itself:
  // 113 ticks for the first where the frame falls at 112.5. The next slot rounds on its own, which is the tick a clip
  // ending there and one opening there both land on.
  @Test
  fun `lands a slot at a rate the timescale does not divide on its nearest tick`() {
    outputSlotTime(1, UNEVEN_RATE).toCMTime().useContents { value } shouldBe 113L
    outputSlotTime(2, UNEVEN_RATE).toCMTime().useContents { value } shouldBe 225L
  }

  @Test
  fun `reads an invalid time as zero`() {
    (-1).seconds.toCMTime().toDuration() shouldBe Duration.ZERO
  }

  @Test
  fun `builds a range that ends where its start plus its duration says`() {
    CMTimeRangeGetEnd(timeRangeOf(2.seconds, 3.seconds)).toDuration() shouldBe 5.seconds
  }

  private companion object {
    const val WHOLE_TICK_RATE = 48
    const val UNEVEN_RATE = 32
  }
}
