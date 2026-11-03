package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class WrapGuardTest {
  private val long = wrapOf(TimeRange.of(1.seconds, 7.seconds))
  private val middle = wrapOf(TimeRange.of(3.seconds, 5.seconds))
  private val atStart = wrapOf(TimeRange.of(1.seconds, 3.seconds))

  @Test
  fun `a wrap may happen while the transport runs with no seek in flight`() {
    val guard = WrapGuard()

    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `no wrap may happen with the transport stopped`() {
    val guard = WrapGuard()

    guard.mayWrap(running = false) shouldBe false
  }

  @Test
  fun `no wrap may happen while a seek that is not a wrap is in flight`() {
    val guard = WrapGuard()
    guard.seekIssued()

    guard.mayWrap(running = true) shouldBe false

    guard.seekLanded(long) shouldBe null
    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `no wrap may happen while a wrap is in flight`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)

    guard.mayWrap(running = true) shouldBe false
  }

  @Test
  fun `a wrap landing on an unchanged range never redirects`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)

    guard.seekLanded(long) shouldBe null
    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `a wrap landing after the range changed redirects to the new range's first slot`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    guard.seekLanded(middle) shouldBe middle.firstSlot
  }

  // The narrowed range starts in the middle of the old one, so the redirect cannot pass by landing on either end.
  @Test
  fun `a range narrowed to the middle of the old one redirects to its own first slot`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    val redirect = guard.seekLanded(middle).shouldNotBeNull()

    redirect shouldBe middle.firstSlot
    (redirect > long.firstSlot && redirect < long.lastSlot) shouldBe true
    outputSlotTime(redirect, RATE) shouldBe outputSlotTime(middle.firstSlot, RATE)
  }

  @Test
  fun `a range sharing the old start still redirects when it changed`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    guard.seekLanded(atStart) shouldBe atStart.firstSlot
  }

  @Test
  fun `a wrap landing after the range was cleared keeps its landing`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    guard.seekLanded(current = null) shouldBe null
    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `a redirect lands once and does not loop`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    guard.seekLanded(middle) shouldBe middle.firstSlot
    guard.mayWrap(running = true) shouldBe false

    guard.seekLanded(middle) shouldBe null
    guard.seekLanded(middle) shouldBe null
    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `a redirect in flight follows a second range change`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()
    guard.seekLanded(middle) shouldBe middle.firstSlot
    guard.rangeChanged()

    guard.seekLanded(atStart) shouldBe atStart.firstSlot
    guard.seekLanded(atStart) shouldBe null
  }

  @Test
  fun `a seek landing after the range changed lands where it was sent`() {
    val guard = WrapGuard()
    guard.seekIssued()
    guard.rangeChanged()

    guard.seekLanded(middle) shouldBe null
  }

  @Test
  fun `a seek issued over a wrap in flight takes its place and does not redirect`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.seekIssued()
    guard.rangeChanged()

    guard.seekLanded(middle) shouldBe null
  }

  @Test
  fun `a range change with no wrap in flight does not redirect a later wrap`() {
    val guard = WrapGuard()
    guard.rangeChanged()
    guard.seekIssued(wrap = true)

    guard.seekLanded(middle) shouldBe null
  }

  @Test
  fun `a reset clears a seek left in flight so a wrap may happen on the new composition`() {
    val guard = WrapGuard()
    guard.seekIssued()

    guard.reset()

    guard.mayWrap(running = true) shouldBe true
  }

  @Test
  fun `a reset clears a wrap in flight so a stale landing does not redirect`() {
    val guard = WrapGuard()
    guard.seekIssued(wrap = true)
    guard.rangeChanged()

    guard.reset()

    guard.seekLanded(middle) shouldBe null
    guard.mayWrap(running = true) shouldBe true
  }

  private fun wrapOf(range: TimeRange): LoopWrap = loopWrapOf(range, LENGTH, RATE).shouldNotBeNull()

  private companion object {
    const val RATE = 30
    val LENGTH: Duration = 10.seconds
  }
}
