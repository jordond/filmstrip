package dev.jordond.filmstrip.media3.internal

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import com.google.common.util.concurrent.Uninterruptibles
import dev.jordond.filmstrip.media.ycbcrReading
import dev.jordond.filmstrip.media.ycbcrToRgb
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue

/**
 * How [nearestColour] names the colour an encoder wrote from the codes it gave full green, which [SizeTier] a frame
 * size falls in, and how [MeasuredEncoderColour] keeps, times out and shares its measurements.
 *
 * The green codes are the eight-bit ones H.273's equations give full green, and each case reads them with a code or
 * two of encoder noise as well, the middle of the tolerance and not only its edge.
 */
class EncoderColourProbeTest {
  @Test
  fun `each matrix and range is named from its own green`() {
    GREENS.forEach { (candidate, green) ->
      listOf(0, 1, -2).forEach { noise ->
        val named = nearestColour(green.shifted(noise), ColorInfo.SDR_BT709_LIMITED)

        assertEquals(candidate.colorSpace, named?.colorSpace, "$candidate noise $noise")
        assertEquals(candidate.colorRange, named?.colorRange, "$candidate noise $noise")
        assertEquals(C.COLOR_TRANSFER_SDR, named?.colorTransfer)
      }
    }
  }

  @Test
  fun `bt709 at limited range reads back as the request itself`() {
    assertEquals(ColorInfo.SDR_BT709_LIMITED, nearestColour(BT709_LIMITED_GREEN, ColorInfo.SDR_BT709_LIMITED))
  }

  @Test
  fun `codes near none of them name nothing`() {
    assertNull(nearestColour(Triple(128, 128, 128), ColorInfo.SDR_BT709_LIMITED))
    assertNull(nearestColour(BT601_LIMITED_GREEN.shifted(4), ColorInfo.SDR_BT709_LIMITED))
  }

  @Test
  fun `each green read through another matrix or range lands past twice the tolerance`() {
    GREENS.forEach { (own, green) ->
      CANDIDATES.filter { it != own }.forEach { other ->
        val distance = fromGreen(green, other)
        assertTrue(distance > 2 * GREEN_TOLERANCE, "$own green read as $other lands $distance away")
      }
    }
  }

  @Test
  fun `each pinned green decodes to full green within one code`() {
    GREENS.forEach { (candidate, green) ->
      val matrix = if (candidate.colorSpace == C.COLOR_SPACE_BT601) BT601_MATRIX else BT709_MATRIX
      val reading = ycbcrReading(matrix, UNSPECIFIED, UNSPECIFIED)
      val rgb =
        ycbcrToRgb(reading, green.first, green.second, green.third, 8, candidate.colorRange == C.COLOR_RANGE_FULL)

      listOf(0, MAX_CODE, 0).zip(rgb.toList()).forEach { (expected, actual) ->
        val code = (actual * MAX_CODE).roundToInt()
        assertTrue(abs(code - expected) <= 1, "$candidate green $green decodes to ${rgb.toList()}")
      }
    }
  }

  @Test
  fun `a frame size belongs to the first tier it fits within`() {
    assertEquals(SizeTier.SD, SizeTier.of(640, 360))
    assertEquals(SizeTier.SD, SizeTier.of(720, 576))
    assertEquals(SizeTier.HD, SizeTier.of(721, 400))
    assertEquals(SizeTier.HD, SizeTier.of(1280, 720))
    assertEquals(SizeTier.HD, SizeTier.of(1920, 1080))
    assertEquals(SizeTier.UHD, SizeTier.of(1920, 1088))
    assertEquals(SizeTier.UHD, SizeTier.of(2560, 1440))
    assertEquals(SizeTier.UHD, SizeTier.of(7680, 4320))
  }

  @Test
  fun `a portrait frame belongs to the tier of its landscape turn`() {
    assertEquals(SizeTier.SD, SizeTier.of(576, 720))
    assertEquals(SizeTier.HD, SizeTier.of(720, 1280))
    assertEquals(SizeTier.HD, SizeTier.of(1080, 1920))
    assertEquals(SizeTier.UHD, SizeTier.of(1088, 1920))
    assertEquals(SizeTier.UHD, SizeTier.of(2160, 3840))
  }

  @Test
  fun `a measured colour is kept and asked for once`() {
    val runs = AtomicInteger()
    val probe = MeasuredEncoderColour(TIMEOUT) { _, _ -> measured(runs) }

    repeat(3) { assertEquals(WrittenColour.Measured(BT601_LIMITED), probe.written(REQUEST)) }

    assertEquals(1, runs.get())
  }

  @Test
  fun `another size tier is measured on its own`() {
    val runs = AtomicInteger()
    val probe = MeasuredEncoderColour(TIMEOUT) { _, _ -> measured(runs) }

    probe.written(REQUEST)
    probe.written(REQUEST.copy(tier = SizeTier.HD))

    assertEquals(2, runs.get())
  }

  @Test
  fun `an unclear answer is kept and a throwing measurement is not`() {
    val runs = AtomicInteger()
    val probe =
      MeasuredEncoderColour(TIMEOUT) { request, _ ->
        runs.incrementAndGet()
        when (request.tier) {
          SizeTier.SD -> WrittenColour.Unclear("no frame")
          else -> if (runs.get() == 2) error("no codec") else WrittenColour.Measured(BT601_LIMITED)
        }
      }

    repeat(2) { assertEquals(WrittenColour.Unclear("no frame"), probe.written(REQUEST)) }
    val thrown = assertIs<WrittenColour.Unclear>(probe.written(REQUEST.copy(tier = SizeTier.HD)))
    assertTrue(thrown.reason.contains("no codec"), thrown.reason)
    assertEquals(WrittenColour.Measured(BT601_LIMITED), probe.written(REQUEST.copy(tier = SizeTier.HD)))
    assertEquals(3, runs.get())
  }

  @Test
  fun `an unclear answer cut short by the deadline is not kept`() {
    val runs = AtomicInteger()
    val probe =
      MeasuredEncoderColour(TIMEOUT) { _, _ ->
        if (runs.incrementAndGet() > 1) {
          WrittenColour.Measured(BT601_LIMITED)
        } else {
          WrittenColour.Unclear.outOfTime("no frame yet")
        }
      }

    assertEquals(WrittenColour.Unclear("no frame yet", kept = false), probe.written(REQUEST))
    repeat(2) { assertEquals(WrittenColour.Measured(BT601_LIMITED), probe.written(REQUEST)) }
    assertEquals(2, runs.get())
  }

  @Test
  fun `a measurement past its deadline is interrupted waited for and not kept`() {
    val runs = AtomicInteger()
    val released = AtomicBoolean()
    val probe =
      MeasuredEncoderColour(SHORT_TIMEOUT, GRACE) { _, deadline ->
        runs.incrementAndGet()
        assertTrue(deadline - System.nanoTime() <= SHORT_TIMEOUT)
        try {
          Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TIMEOUT))
          WrittenColour.Measured(ColorInfo.SDR_BT709_LIMITED)
        } catch (stopped: InterruptedException) {
          Thread.sleep(RELEASE_MILLIS)
          released.set(true)
          throw stopped
        }
      }

    val answer = assertIs<WrittenColour.Unclear>(probe.written(REQUEST))

    assertTrue(released.get(), "the answer came back before the measurement released what it holds")
    assertFalse(answer.kept)
    assertEquals(answer, probe.written(REQUEST))
    assertEquals(2, runs.get())
  }

  @Test
  fun `a caller interrupted while it waits throws and the measurement goes on`() {
    val runs = AtomicInteger()
    val release = CountDownLatch(1)
    val probe =
      MeasuredEncoderColour(TIMEOUT) { _, _ ->
        release.await()
        measured(runs)
      }

    Thread.currentThread().interrupt()
    assertFailsWith<InterruptedException> { probe.written(REQUEST) }
    release.countDown()

    assertEquals(WrittenColour.Measured(BT601_LIMITED), probe.written(REQUEST))
    assertEquals(1, runs.get())
  }

  @Test
  fun `a caller interrupted while it waits out the grace throws and the next caller measures again`() {
    val caller = Thread.currentThread()
    val hold = CountDownLatch(1)
    val runs = AtomicInteger()
    val probe =
      MeasuredEncoderColour(SHORT_TIMEOUT, TIMEOUT) { _, _ ->
        if (runs.get() > 0) return@MeasuredEncoderColour measured(runs)
        runs.incrementAndGet()
        try {
          Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TIMEOUT))
        } catch (_: InterruptedException) {
          caller.interrupt()
          Uninterruptibles.awaitUninterruptibly(hold)
        }
        WrittenColour.Measured(BT601_LIMITED)
      }

    try {
      assertFailsWith<InterruptedException> { probe.written(REQUEST) }
    } finally {
      hold.countDown()
    }

    assertEquals(WrittenColour.Measured(BT601_LIMITED), probe.written(REQUEST))
    assertEquals(2, runs.get())
  }

  @Test
  fun `callers asking at once share one measurement`() {
    val runs = AtomicInteger()
    val answers = AtomicReferenceArray<WrittenColour>(CALLERS)
    lateinit var callers: List<Thread>
    val probe =
      MeasuredEncoderColour(TIMEOUT) { _, deadline ->
        while (callers.any { it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) {
          Thread.sleep(1)
        }
        measured(runs)
      }
    callers = List(CALLERS) { index -> Thread { answers.set(index, probe.written(REQUEST)) } }

    callers.forEach(Thread::start)
    callers.forEach { it.join(TimeUnit.NANOSECONDS.toMillis(TIMEOUT)) }

    repeat(CALLERS) { assertEquals(WrittenColour.Measured(BT601_LIMITED), answers.get(it)) }
    assertEquals(1, runs.get())
  }

  private fun measured(runs: AtomicInteger): WrittenColour {
    runs.incrementAndGet()
    return WrittenColour.Measured(BT601_LIMITED)
  }

  private fun Triple<Int, Int, Int>.shifted(noise: Int) = Triple(first + noise, second - noise, third + noise)

  private companion object {
    val BT601_LIMITED_GREEN = Triple(145, 54, 34)
    val BT709_LIMITED_GREEN = Triple(173, 42, 26)

    // Full green through H.273 matrices 6 and 1 at limited range, and the same at full range.
    val GREENS =
      listOf(
        GreenCandidate(C.COLOR_SPACE_BT601, C.COLOR_RANGE_LIMITED) to BT601_LIMITED_GREEN,
        GreenCandidate(C.COLOR_SPACE_BT601, C.COLOR_RANGE_FULL) to Triple(150, 44, 21),
        GreenCandidate(C.COLOR_SPACE_BT709, C.COLOR_RANGE_LIMITED) to BT709_LIMITED_GREEN,
        GreenCandidate(C.COLOR_SPACE_BT709, C.COLOR_RANGE_FULL) to Triple(182, 30, 12),
      )

    val BT601_LIMITED: ColorInfo =
      ColorInfo.SDR_BT709_LIMITED
        .buildUpon()
        .setColorSpace(C.COLOR_SPACE_BT601)
        .build()

    val REQUEST =
      EncoderColourRequest("c2.android.avc.encoder", MimeTypes.VIDEO_H264, SizeTier.SD, ColorInfo.SDR_BT709_LIMITED)

    const val CALLERS = 4
    const val BT709_MATRIX = 1
    const val BT601_MATRIX = 6
    const val UNSPECIFIED = 2
    const val MAX_CODE = 255
    const val RELEASE_MILLIS = 50L
    val TIMEOUT = TimeUnit.SECONDS.toNanos(5)
    val SHORT_TIMEOUT = TimeUnit.MILLISECONDS.toNanos(100)
    val GRACE = TimeUnit.SECONDS.toNanos(1)
  }
}
