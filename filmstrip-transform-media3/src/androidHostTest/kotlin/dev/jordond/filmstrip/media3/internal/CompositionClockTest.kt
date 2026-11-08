package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MediaItem
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.media3.internal.preview.Media3Readback
import dev.jordond.filmstrip.media3.internal.preview.Media3Span
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.mockk
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The link at the head of a reader's chain that moves each frame onto the composition's clock.
 */
class CompositionClockTest {
  @Test
  fun `a frame is restamped by where its clip starts`() {
    val forwarded = forwardedThrough(CompositionClock(1_500.milliseconds), 40_000L)

    forwarded.single().second shouldBe 1_540_000L
  }

  @Test
  fun `every frame of a clip moves by the same offset`() {
    val forwarded = forwardedThrough(CompositionClock(2.seconds), 0L, 33_333L, 66_666L)

    forwarded.map { it.second } shouldBe listOf(2_000_000L, 2_033_333L, 2_066_666L)
  }

  @Test
  fun `a clip at the start of the timeline keeps its own timestamps`() {
    val forwarded = forwardedThrough(CompositionClock(Duration.ZERO), 250_000L)

    forwarded.single().second shouldBe 250_000L
  }

  @Test
  fun `the texture is handed on as it arrived`() {
    val forwarded = forwardedThrough(CompositionClock(1.seconds), 0L)

    forwarded.single().first shouldBeSameInstanceAs TEXTURE
  }

  @Test
  fun `an hdr chain is restamped the same way`() {
    val program = CompositionClock(500.milliseconds).toGlShaderProgram(mockk<Context>(), useHdr = true)

    forwardedBy(program, 10_000L).single().second shouldBe 510_000L
  }

  @Test
  fun `the item clock hands a reader back the time its frame was decoded at`() {
    for (speed in listOf(0.7, 1.5)) {
      val clock = CompositionClock(SPAN_START, speed)
      val back = roundTrip(clock, FRAME_TIMES_US)

      withClue("at $speed") {
        back.first() shouldBe 0L
        FRAME_TIMES_US.zip(back).forEach { (decoded, returned) ->
          (decoded - returned) shouldBeInRange 0L..ceil(speed).toLong()
        }
      }
    }
  }

  // A reader answers a read at the clip's start in milliseconds with the first frame when that frame carries it.
  @Test
  fun `no frame but the first comes back on the time of the first`() {
    val clock = CompositionClock(FRAME_TIMES_US[2].microseconds, 1.5)

    val back = roundTrip(clock, FRAME_TIMES_US)

    back.drop(1).map { it / 1_000 } shouldNotContain 0L
    back.map { it / 1_000 }.indexOf(FRAME_TIMES_US[2] / 1_000) shouldBe 2
  }

  @Test
  fun `the head stamps a frame at the composition time the clock reports for it`() {
    val clock = CompositionClock(SPAN_START, 1.5)

    val stamped = forwardedThrough(clock, *FRAME_TIMES_US.toLongArray()).map { it.second }

    stamped shouldBe FRAME_TIMES_US.map { clock.compositionTimeOf(it.microseconds).inWholeMicroseconds }
  }

  // At 1/8 each source frame holds about eight slots, and the export writes it on the first of them, well before where
  // the clip's speed alone puts it.
  @Test
  fun `a shown frame is stamped where its span writes it`() {
    val speed = 1.0 / 8
    val readback = readbackShowing(speed, FRAME_TIMES_US.map { it to writtenAt(it, speed) })
    val clock = readback.span.clock

    val stamped = forwardedThrough(clock, *FRAME_TIMES_US.toLongArray()).map { it.second }

    val written = FRAME_TIMES_US.map { (SPAN_START + writtenAt(it, speed).microseconds).inWholeMicroseconds }
    stamped shouldBe written
    val linear = CompositionClock(SPAN_START, speed)
    stamped.drop(1).zip(FRAME_TIMES_US.drop(1)).forEach { (at, position) ->
      at shouldNotBe linear.compositionTimeOf(position.microseconds).inWholeMicroseconds
    }
    FRAME_TIMES_US.map { readback.compositionTimeOf(it / 1_000).inWholeMicroseconds } shouldBe written
  }

  @Test
  fun `the item clock hands a reader back every shown frame on its own position`() {
    for (speed in listOf(1.0 / 8, 0.7)) {
      val clock = readbackShowing(speed, FRAME_TIMES_US.map { it to writtenAt(it, speed) }).span.clock

      withClue("at $speed") { roundTrip(clock, FRAME_TIMES_US) shouldBe FRAME_TIMES_US }
    }
  }

  // A frame between two shown ones, which a seek to a sync sample decodes on the way, lands between them and comes
  // back where it was decoded.
  @Test
  fun `a frame between two shown ones lands between where they are written`() {
    val speed = 1.5
    val shown = listOf(FRAME_TIMES_US[0], FRAME_TIMES_US[2], FRAME_TIMES_US[4])
    val clock = readbackShowing(speed, shown.map { it to writtenAt(it, speed) }).span.clock

    val stamped = forwardedThrough(clock, FRAME_TIMES_US[1]).single().second - SPAN_START.inWholeMicroseconds

    stamped shouldBeInRange writtenAt(shown[0], speed)..writtenAt(shown[1], speed)
    roundTrip(clock, listOf(FRAME_TIMES_US[1])).single() shouldBeInRange FRAME_TIMES_US[1] - 1..FRAME_TIMES_US[1] + 1
  }

  @Test
  fun `a frame the span never shows is taken at the millisecond the reader answers`() {
    val speed = 1.5
    val readback = readbackShowing(speed, FRAME_TIMES_US.take(2).map { it to writtenAt(it, speed) })

    readback.compositionTimeOf(500) shouldBe readback.span.clock.compositionTimeOf(500.milliseconds)
  }

  // The first slot of a 30 fps grid whose source time, at [speed], reads nearer [positionUs] than the frame before it.
  private fun writtenAt(
    positionUs: Long,
    speed: Double,
  ): Long {
    if (positionUs == 0L) return 0L
    val halfWay = positionUs - FRAME_US / 2
    val slot = ceil(halfWay / speed / FRAME_US).toLong()
    return slot * FRAME_US
  }

  private fun roundTrip(
    clock: CompositionClock,
    decodedUs: List<Long>,
  ): List<Long> {
    val stamped = forwardedThrough(clock, *decodedUs.toLongArray()).map { it.second }
    val itemClock = clock.itemClock.toGlShaderProgram(mockk<Context>(), false)
    return forwardedBy(itemClock, *stamped.toLongArray()).map { it.second }
  }

  // A span showing each position on the time paired with it, in microseconds into the span.
  private fun readbackShowing(
    speed: Double,
    shownUs: List<Pair<Long, Long>>,
  ): Media3Readback =
    Media3Readback(
      Media3Span(
        start = SPAN_START,
        end = SPAN_START + 10.seconds,
        item = MediaItem.EMPTY,
        effects = emptyList(),
        still = false,
        shown = shownUs.map { (position, written) -> written.microseconds to position.microseconds },
        speed = speed,
      ),
      effects = emptyList(),
    )

  private fun forwardedThrough(
    clock: CompositionClock,
    vararg presentationTimeUs: Long,
  ): List<Pair<GlTextureInfo, Long>> =
    forwardedBy(clock.toGlShaderProgram(mockk<Context>(), useHdr = false), *presentationTimeUs)

  private fun forwardedBy(
    program: GlShaderProgram,
    vararg presentationTimeUs: Long,
  ): List<Pair<GlTextureInfo, Long>> {
    val forwarded = mutableListOf<Pair<GlTextureInfo, Long>>()
    program.setOutputListener(
      object : GlShaderProgram.OutputListener {
        override fun onOutputFrameAvailable(
          outputTexture: GlTextureInfo,
          presentationTimeUs: Long,
        ) {
          forwarded += outputTexture to presentationTimeUs
        }
      },
    )
    presentationTimeUs.forEach { program.queueInputFrame(mockk<GlObjectsProvider>(), TEXTURE, it) }
    return forwarded
  }

  private companion object {
    val TEXTURE = GlTextureInfo(7, -1, -1, 640, 360)
    val SPAN_START = 500.milliseconds

    // A 30 fps source's first frames, the fourth sitting exactly on a millisecond.
    val FRAME_TIMES_US = listOf(0L, 33_367L, 66_733L, 100_000L, 133_367L)

    const val FRAME_US = 33_333L
  }
}
