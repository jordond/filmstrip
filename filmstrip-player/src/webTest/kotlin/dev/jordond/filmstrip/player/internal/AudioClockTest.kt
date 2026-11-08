package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the browser's audio clock reads the playhead off the graph, and what it reads once a seek or a wrap moves it.
 */
class AudioClockTest {
  private var graphPosition = Duration.ZERO
  private val clock = AudioClock({ graphPosition }, { true })

  @Test
  fun `a running clock reads the graph`() {
    clock.start(1000.milliseconds)
    graphPosition = 1250.milliseconds

    clock.position shouldBe 1250.milliseconds
    clock.isRunning shouldBe true
  }

  // The graph goes on sounding the old position until it is started again, however long that start takes.
  @Test
  fun `a moved clock holds where it was moved until it is started again`() {
    clock.start(1000.milliseconds)
    graphPosition = 1400.milliseconds

    clock.moveTo(600.milliseconds)
    graphPosition = 1450.milliseconds

    clock.position shouldBe 600.milliseconds
    clock.isRunning shouldBe false

    clock.start(600.milliseconds)
    graphPosition = 650.milliseconds

    clock.position shouldBe 650.milliseconds
  }

  @Test
  fun `a stopped clock holds where the graph had reached`() {
    clock.start(1000.milliseconds)
    graphPosition = 1300.milliseconds

    clock.stop()
    graphPosition = 1350.milliseconds

    clock.position shouldBe 1300.milliseconds
  }
}
