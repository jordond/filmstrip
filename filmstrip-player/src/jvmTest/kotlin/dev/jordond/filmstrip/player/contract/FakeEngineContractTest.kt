package dev.jordond.filmstrip.player.contract

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
 * The suite's own inheritor, so the shape runs before a real backend adopts it. A failure here is the
 * harness, not a platform. It runs on the JVM only, since the fake is plain coroutines and one target
 * proves the harness. The real engines run the same suite on the JVM, macOS, iOS, the browser and
 * an Android device. The Android host tests run no engine contract at all.
 */
class FakeEngineContractTest : PlayerEngineContractTest() {
  override fun createEngine(scope: CoroutineScope): EngineUnderTest = FakeEngineUnderTest(scope)

  // The fake has no item to fail, so it refuses its first load. Every failed item case here goes through the refused
  // path aPlayAfterARefusedFirstLoadMovesThePlayheadOnTheNextLoad covers.
  override fun createFailingItem(scope: CoroutineScope): FailingItem {
    var refused = false
    val subject = FakeEngineUnderTest(scope) { !refused.also { refused = true } }
    return FailingItem(subject, fixture.cutInTwo(fixtureFrameRate), refusedAtLoad = true)
  }

  override val fixture: EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of("contract.mp4"), TimeRange.of(Duration.ZERO, 8.seconds))))),
    )

  override val bytesFixture: EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(ByteArray(1)), TimeRange.of(Duration.ZERO, 8.seconds))))),
    )

  override val unreadableFixture: EditComposition = FAKE_UNREADABLE

  override val claimsLiveParameterRedraw: Boolean = true

  override val fixtureFrameRate: Int = FAKE_FRAME_RATE

  // The fake reports one preview info whatever it loads.
  override val reportsOutputFrame: Boolean = false

  // Nothing is out of reach for a fake, so it takes everything short of the two occasions no
  // platform exposes at all.
  override val stageableInterruptions: Set<Interruption> =
    Interruption.entries.toSet() - UNSTAGEABLE_INTERRUPTIONS
}
