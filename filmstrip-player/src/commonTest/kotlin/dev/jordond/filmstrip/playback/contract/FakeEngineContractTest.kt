package dev.jordond.filmstrip.playback.contract

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import kotlinx.coroutines.CoroutineScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Runs the engine contracts against a backend with no platform under it.
 *
 * The suite's own inheritor, so the shape is executed on every target before a real backend adopts
 * it. A failure here is the harness, not a platform.
 */
class FakeEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = FakeEngineUnderTest(scope)

  override val fixture: EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of("contract.mp4"), TimeRange.of(Duration.ZERO, 8.seconds))))),
    )

  // Nothing is out of reach for a fake, so it takes everything short of the two occasions no
  // platform exposes at all.
  override val stageableInterruptions: Set<Interruption> =
    Interruption.entries.toSet() - UNSTAGEABLE_INTERRUPTIONS
}
