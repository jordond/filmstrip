package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ffmpeg.internal.ProgressParser
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds

class ProgressParserTest {
  @Test
  fun `reports nothing until a block closes`() {
    val parser = ProgressParser(totalMicros = 6_000_000)

    parser.accept("frame=180") shouldBe null
    parser.accept("out_time_us=3000000") shouldBe null
    parser.accept("speed=46.9x") shouldBe null

    val progress = parser.accept("progress=continue")
    progress shouldNotBe null
    progress!!.fraction shouldBe 0.5f
    progress.position shouldBe 3_000.milliseconds
  }

  // `speed` is a measured ratio of media time to wall time, so the estimate is a measurement
  // rather than an extrapolation from a percentage.
  @Test
  fun `estimates from the measured speed`() {
    val parser = ProgressParser(totalMicros = 6_000_000)
    parser.accept("out_time_us=3000000")
    parser.accept("speed=2.0x")

    parser.accept("progress=continue")!!.estimatedRemaining shouldBe 1_500.milliseconds
  }

  @Test
  fun `has no estimate before speed arrives`() {
    val parser = ProgressParser(totalMicros = 6_000_000)
    parser.accept("out_time_us=N/A")
    parser.accept("speed=N/A")

    val progress = parser.accept("progress=continue")!!
    progress.position shouldBe null
    progress.estimatedRemaining shouldBe null
  }

  // ffmpeg 6 closes a 12 second encode on out_time_us=11989333, which reads as 0.999 rather than
  // done. Later builds report the full duration, so the fraction has to come from the key that
  // means the encode ended rather than from the clock.
  @Test
  fun `reads the end block as finished however short out_time lands`() {
    val parser = ProgressParser(totalMicros = 12_000_000)
    parser.accept("out_time_us=11989333")

    parser.accept("progress=end")!!.fraction shouldBe 1f
  }

  @Test
  fun `never goes backwards`() {
    val parser = ProgressParser(totalMicros = 6_000_000)
    parser.accept("out_time_us=4000000")
    parser.accept("progress=continue")

    parser.accept("out_time_us=1000000")
    parser.accept("progress=continue")!!.fraction shouldBe 4f / 6f
  }
}
