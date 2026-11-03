package dev.jordond.filmstrip.media3.internal.encoder

import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.MimeTypes
import com.google.common.util.concurrent.Uninterruptibles
import dev.jordond.filmstrip.color.ycbcrReading
import dev.jordond.filmstrip.color.ycbcrToRgb
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.util.concurrent.CountDownLatch
import java.util.concurrent.TimeUnit
import java.util.concurrent.atomic.AtomicBoolean
import java.util.concurrent.atomic.AtomicInteger
import java.util.concurrent.atomic.AtomicReferenceArray
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * How [nearestColor] names the color an encoder wrote from the codes it gave full green, which [SizeTier] a frame
 * size falls in, and how [MeasuredEncoderColor] keeps, times out and shares its measurements.
 *
 * The green codes are the eight-bit ones H.273's equations give full green, and each case reads them with a code or
 * two of encoder noise as well, the middle of the tolerance and not only its edge.
 */
class EncoderColorProbeTest {
  @Test
  fun `each matrix and range is named from its own green`() {
    GREENS.forEach { (candidate, green) ->
      listOf(0, 1, -2).forEach { noise ->
        val named = nearestColor(green.shifted(noise), ColorInfo.SDR_BT709_LIMITED)

        withClue("$candidate noise $noise") { named?.colorSpace shouldBe candidate.colorSpace }
        withClue("$candidate noise $noise") { named?.colorRange shouldBe candidate.colorRange }
        named?.colorTransfer shouldBe C.COLOR_TRANSFER_SDR
      }
    }
  }

  @Test
  fun `bt709 at limited range reads back as the request itself`() {
    nearestColor(BT709_LIMITED_GREEN, ColorInfo.SDR_BT709_LIMITED) shouldBe ColorInfo.SDR_BT709_LIMITED
  }

  @Test
  fun `codes near none of them name nothing`() {
    nearestColor(Triple(128, 128, 128), ColorInfo.SDR_BT709_LIMITED).shouldBeNull()
    nearestColor(BT601_LIMITED_GREEN.shifted(4), ColorInfo.SDR_BT709_LIMITED).shouldBeNull()
  }

  @Test
  fun `each green read through another matrix or range lands past twice the tolerance`() {
    GREENS.forEach { (own, green) ->
      CANDIDATES.filter { it != own }.forEach { other ->
        val distance = fromGreen(green, other)
        withClue("$own green read as $other lands $distance away") { (distance > 2 * GREEN_TOLERANCE) shouldBe true }
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
        withClue("$candidate green $green decodes to ${rgb.toList()}") { (abs(code - expected) <= 1) shouldBe true }
      }
    }
  }

  @Test
  fun `a frame size belongs to the first tier it fits within`() {
    SizeTier.of(640, 360) shouldBe SizeTier.SD
    SizeTier.of(720, 576) shouldBe SizeTier.SD
    SizeTier.of(721, 400) shouldBe SizeTier.HD
    SizeTier.of(1280, 720) shouldBe SizeTier.HD
    SizeTier.of(1920, 1080) shouldBe SizeTier.HD
    SizeTier.of(1920, 1088) shouldBe SizeTier.UHD
    SizeTier.of(2560, 1440) shouldBe SizeTier.UHD
    SizeTier.of(7680, 4320) shouldBe SizeTier.UHD
  }

  @Test
  fun `a portrait frame belongs to the tier of its landscape turn`() {
    SizeTier.of(576, 720) shouldBe SizeTier.SD
    SizeTier.of(720, 1280) shouldBe SizeTier.HD
    SizeTier.of(1080, 1920) shouldBe SizeTier.HD
    SizeTier.of(1088, 1920) shouldBe SizeTier.UHD
    SizeTier.of(2160, 3840) shouldBe SizeTier.UHD
  }

  @Test
  fun `a measured color is kept and asked for once`() {
    val runs = AtomicInteger()
    val probe = MeasuredEncoderColor(TIMEOUT) { _, _ -> measured(runs) }

    repeat(3) { probe.written(REQUEST) shouldBe WrittenColor.Measured(BT601_LIMITED) }

    runs.get() shouldBe 1
  }

  @Test
  fun `another size tier is measured on its own`() {
    val runs = AtomicInteger()
    val probe = MeasuredEncoderColor(TIMEOUT) { _, _ -> measured(runs) }

    probe.written(REQUEST)
    probe.written(REQUEST.copy(tier = SizeTier.HD))

    runs.get() shouldBe 2
  }

  @Test
  fun `an unclear answer is kept and a throwing measurement is not`() {
    val runs = AtomicInteger()
    val probe =
      MeasuredEncoderColor(TIMEOUT) { request, _ ->
        runs.incrementAndGet()
        when (request.tier) {
          SizeTier.SD -> WrittenColor.Unclear("no frame")
          else -> if (runs.get() == 2) error("no codec") else WrittenColor.Measured(BT601_LIMITED)
        }
      }

    repeat(2) { probe.written(REQUEST) shouldBe WrittenColor.Unclear("no frame") }
    val thrown = probe.written(REQUEST.copy(tier = SizeTier.HD)).shouldBeInstanceOf<WrittenColor.Unclear>()
    withClue(thrown.reason) { thrown.reason.contains("no codec") shouldBe true }
    probe.written(REQUEST.copy(tier = SizeTier.HD)) shouldBe WrittenColor.Measured(BT601_LIMITED)
    runs.get() shouldBe 3
  }

  @Test
  fun `a measurement past its deadline is interrupted waited for and not kept`() {
    val runs = AtomicInteger()
    val released = AtomicBoolean()
    val probe =
      MeasuredEncoderColor(SHORT_TIMEOUT, GRACE) { _, deadline ->
        runs.incrementAndGet()
        (deadline - System.nanoTime() <= SHORT_TIMEOUT) shouldBe true
        try {
          Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TIMEOUT))
          WrittenColor.Measured(ColorInfo.SDR_BT709_LIMITED)
        } catch (stopped: InterruptedException) {
          Thread.sleep(RELEASE_MILLIS)
          released.set(true)
          throw stopped
        }
      }

    val answer = probe.written(REQUEST).shouldBeInstanceOf<WrittenColor.Unclear>()

    withClue("the answer came back before the measurement released what it holds") { released.get() shouldBe true }
    answer.kept shouldBe false
    probe.written(REQUEST) shouldBe answer
    runs.get() shouldBe 2
  }

  @Test
  fun `a caller interrupted while it waits throws and the measurement goes on`() {
    val runs = AtomicInteger()
    val release = CountDownLatch(1)
    val probe =
      MeasuredEncoderColor(TIMEOUT) { _, _ ->
        release.await()
        measured(runs)
      }

    Thread.currentThread().interrupt()
    assertFailsWith<InterruptedException> { probe.written(REQUEST) }
    release.countDown()

    probe.written(REQUEST) shouldBe WrittenColor.Measured(BT601_LIMITED)
    runs.get() shouldBe 1
  }

  @Test
  fun `a caller interrupted while it waits out the grace throws and the next caller measures again`() {
    val caller = Thread.currentThread()
    val hold = CountDownLatch(1)
    val runs = AtomicInteger()
    val probe =
      MeasuredEncoderColor(SHORT_TIMEOUT, TIMEOUT) { _, _ ->
        if (runs.get() > 0) return@MeasuredEncoderColor measured(runs)
        runs.incrementAndGet()
        try {
          Thread.sleep(TimeUnit.NANOSECONDS.toMillis(TIMEOUT))
        } catch (_: InterruptedException) {
          caller.interrupt()
          Uninterruptibles.awaitUninterruptibly(hold)
        }
        WrittenColor.Measured(BT601_LIMITED)
      }

    try {
      assertFailsWith<InterruptedException> { probe.written(REQUEST) }
    } finally {
      hold.countDown()
    }

    probe.written(REQUEST) shouldBe WrittenColor.Measured(BT601_LIMITED)
    runs.get() shouldBe 2
  }

  @Test
  fun `callers asking at once share one measurement`() {
    val runs = AtomicInteger()
    val answers = AtomicReferenceArray<WrittenColor>(CALLERS)
    lateinit var callers: List<Thread>
    val probe =
      MeasuredEncoderColor(TIMEOUT) { _, deadline ->
        while (callers.any { it.state != Thread.State.TIMED_WAITING } && System.nanoTime() < deadline) {
          Thread.sleep(1)
        }
        measured(runs)
      }
    callers = List(CALLERS) { index -> Thread { answers.set(index, probe.written(REQUEST)) } }

    callers.forEach(Thread::start)
    callers.forEach { it.join(TimeUnit.NANOSECONDS.toMillis(TIMEOUT)) }

    repeat(CALLERS) { answers.get(it) shouldBe WrittenColor.Measured(BT601_LIMITED) }
    runs.get() shouldBe 1
  }

  private fun measured(runs: AtomicInteger): WrittenColor {
    runs.incrementAndGet()
    return WrittenColor.Measured(BT601_LIMITED)
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
      EncoderColorRequest("c2.android.avc.encoder", MimeTypes.VIDEO_H264, SizeTier.SD, ColorInfo.SDR_BT709_LIMITED)

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
