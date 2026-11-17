package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.LoopEndContract
import kotlinx.coroutines.test.TestResult
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

/**
 * Where the AVFoundation engine reads once it has played to the end of an edit no tick of the item's clock lands on.
 *
 * The item counts in ticks of the media timescale, so it stops on the tick nearest the end of the edit, and the shared
 * contract suite only plays an edit that ends on one.
 */
class AppleEndPositionMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a play to an end between two ticks of the item clock reports the duration`(): TestResult =
    LoopEndContract({ AppleEngineUnderTest(it) { _, _ -> } }, endingOffTick(), FIXTURE_FRAME_RATE).endsAtTheDuration()

  private fun endingOffTick(): Composition {
    val range = TimeRange.of(Duration.ZERO, CLIP_LENGTH + OFF_TICK)
    return Composition(tracks = listOf(Track(listOf(Clip(MediaSource.of(appleFixtureClip()), range)))))
  }

  private companion object {
    // A third of a tick at 3600 ticks a second, which the item's clock rounds away.
    val OFF_TICK = 100.microseconds
  }
}
