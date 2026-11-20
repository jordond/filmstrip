package dev.jordond.filmstrip.player.internal

import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * When the slot a pause rested on stops being the floor of the playhead.
 */
class Media3PlayheadFloorTest {
  @Test
  fun `a paused player with no frame to name reads the slot it rested on though its clock is past it`() {
    val rig = Rig()
    rig.restOn(RESTED)

    rig.read(PAST) shouldBe RESTED
  }

  @Test
  fun `a play that took the clock past the slot lets go of it though nothing was read while it played`() {
    val rig = Rig()
    rig.restOn(RESTED)

    rig.play()
    rig.pause()

    rig.read(PAST) shouldBe PAST.milliseconds
  }

  @Test
  fun `a play that stopped short of the slot keeps it`() {
    val rig = Rig()
    rig.restOn(RESTED)

    rig.play()
    rig.pause()

    rig.read(SHORT) shouldBe RESTED
  }

  @Test
  fun `a play that has reached the slot reads the clock from there on`() {
    val rig = Rig()
    rig.restOn(RESTED)

    rig.play()
    rig.read(SHORT) shouldBe RESTED
    rig.read(PAST) shouldBe PAST.milliseconds
    rig.pause()

    rig.read(SHORT) shouldBe SHORT.milliseconds
  }

  @Test
  fun `a rest after a play holds its own slot again`() {
    val rig = Rig()
    rig.restOn(RESTED)
    rig.play()
    rig.pause()
    rig.restOn(LATER)

    rig.read(PAST) shouldBe LATER
  }

  // The clock passing the slot lets go of the floor, and the frame on the surface can still be the sample the replay
  // went back for.
  @Test
  fun `the slot a replay was for outlasts a play that took the clock past it`() {
    val rig = Rig()
    rig.floor.raiseTo(RESTED)

    rig.play()
    rig.read(PAST) shouldBe PAST.milliseconds
    rig.pause()

    rig.floor.replayed() shouldBe RESTED
  }

  @Test
  fun `a seek lets go of the slot a replay was for`() {
    val rig = Rig()
    rig.floor.raiseTo(RESTED)

    rig.floor.clear()

    rig.floor.replayed().shouldBeNull()
  }

  private class Rig {
    var resting: Duration? = null
    var playWanted = false
    val floor = Media3PlayheadFloor(resting = { resting }, playWanted = { playWanted })

    // A frame on the surface names the slot, and the reading taken then makes it the floor. The frame then goes.
    fun restOn(slot: Duration) {
      resting = slot
      read(0)
      resting = null
    }

    fun play() {
      playWanted = true
      floor.played()
    }

    fun pause() {
      playWanted = false
    }

    fun read(clockMs: Long): Duration = floor.read(clockMs, clockMs.milliseconds)
  }

  private companion object {
    val RESTED = 100.milliseconds
    val LATER = 700.milliseconds
    const val SHORT = 60L
    const val PAST = 430L
  }
}
