package dev.jordond.filmstrip.media3.internal

import dev.jordond.filmstrip.capability.DeviceCapabilities
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlin.test.Test

class HdrDrawLimitTest {
  @Test
  fun `media3 draws no HDR below API 29`() {
    (26..28).forEach { hdrDrawLimit(it) { true }.shouldNotBeNull() }
  }

  @Test
  fun `API 29 and later draw HDR on a device that offers the YUV target`() {
    listOf(29, 30, 33, 36).forEach { hdrDrawLimit(it) { true }.shouldBeNull() }
  }

  @Test
  fun `a device with no YUV target extension draws no HDR at any level`() {
    listOf(29, 33, 36).forEach { hdrDrawLimit(it) { false }.shouldNotBeNull() shouldContain "EXT_YUV_target" }
  }

  @Test
  fun `a device that could not be asked about the YUV target is not refused on a guess`() {
    listOf(29, 33, 36).forEach { hdrDrawLimit(it) { null }.shouldBeNull() }
  }

  @Test
  fun `a device that could not be asked is asked again and its answer is kept once it has one`() {
    val answers = ArrayDeque(listOf<Boolean?>(null, false, true))
    val probe = YuvTargetProbe(attempts = 3) { answers.removeFirst() }

    probe.ask().shouldBeNull()
    probe.ask() shouldBe false
    probe.ask() shouldBe false
    answers.size shouldBe 1
  }

  @Test
  fun `a device that never answers is asked a bounded number of times`() {
    var asked = 0
    val probe = YuvTargetProbe(attempts = 3) { null.also { asked++ } }

    repeat(5) { probe.ask().shouldBeNull() }

    asked shouldBe 3
  }

  @Test
  fun `a device that answers is asked once`() {
    var asked = 0
    val probe = YuvTargetProbe(attempts = 3) { true.also { asked++ } }

    repeat(3) { probe.ask() shouldBe true }

    asked shouldBe 1
  }

  @Test
  fun `a device that draws no HDR keeps no grade through an encoder`() {
    val encodes =
      DeviceCapabilities(
        video = emptyList(),
        audio = emptyList(),
        supportsHdrEncoding = true,
        concurrentSessionBudget = null,
      )

    encodes.keepingGradeOnly(33) { true }.supportsHdrEncoding.shouldBeTrue()
    encodes.keepingGradeOnly(33) { false }.supportsHdrEncoding.shouldBeFalse()
    encodes.keepingGradeOnly(33) { null }.supportsHdrEncoding.shouldBeTrue()
    encodes.keepingGradeOnly(28) { true }.supportsHdrEncoding.shouldBeFalse()
  }
}
