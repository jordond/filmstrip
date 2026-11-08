package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.settle
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import platform.AVFoundation.AVPlayerItemDidPlayToEndTimeNotification
import platform.AVFoundation.currentItem
import platform.AVFoundation.rate
import platform.Foundation.NSNotificationCenter
import kotlin.test.Test
import kotlin.time.Duration

/**
 * What a load refused over an item already loaded leaves behind.
 *
 * The item stays attached and the transport keeps running, while the status reports the refusal until a load is
 * applied.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleRefusedLoadMediaTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a refusal stays on the status while the player changes under it`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitReady(recorder, appleFixtureComposition())

        val refused = engine.awaitComposition(unreadable()).shouldBeInstanceOf<SetCompositionResult.Failure>()
        engine.play()
        engine.pause()
        engine.drainCallbacks()
        settle()

        recorder.lastState.status shouldBe PlaybackStatus.Error(refused.error)
        recorder.events.filterIsInstance<PlaybackEvent.Failed>() shouldHaveSize 1
      }
    }

  @Test
  fun `a play running under a refusal keeps going and carries on to the next load`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitReady(recorder, appleFixtureComposition())
        engine.play()

        engine.awaitComposition(unreadable()).shouldBeInstanceOf<SetCompositionResult.Failure>()
        engine.drainCallbacks()
        engine.platformPlayer.rate shouldNotBe 0f
        recorder.lastState.playWhenReady shouldBe true

        engine.awaitReady(recorder, shorter())
        val first = recorder.playheads.lastOrNull() ?: Duration.ZERO
        awaitContract("the playhead to move on from $first") { (recorder.playheads.lastOrNull() ?: first) > first }
        recorder.lastState.status shouldBe PlaybackStatus.Ready
      }
    }

  @Test
  fun `an item that ends under a refusal stops playback and stays on the refusal`() =
    contractTest { scope ->
      val engine = engine(scope)
      withEngine(engine) { recorder ->
        engine.awaitReady(recorder, appleFixtureComposition())
        engine.play()
        val refused = engine.awaitComposition(unreadable()).shouldBeInstanceOf<SetCompositionResult.Failure>()

        NSNotificationCenter.defaultCenter.postNotificationName(
          AVPlayerItemDidPlayToEndTimeNotification ?: error("no notification name"),
          `object` = engine.platformPlayer.currentItem.shouldNotBeNull(),
        )
        awaitContract("the ending to be reported") { recorder.events.any { it is PlaybackEvent.Ended } }
        engine.drainCallbacks()

        recorder.lastState.status shouldBe PlaybackStatus.Error(refused.error)
        recorder.lastState.playWhenReady shouldBe false
      }
    }

  private fun engine(scope: CoroutineScope): AvPlayerEngine =
    AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)

  private suspend fun AvPlayerEngine.awaitReady(
    recorder: ContractRecorder,
    composition: EditComposition,
  ) {
    awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("the item to be ready") { recorder.lastState.status == PlaybackStatus.Ready }
  }

  private fun unreadable(): EditComposition = clipOf(MediaSource.of(appleFixtureClip() + ".absent"), CLIP_LENGTH)

  private fun shorter(): EditComposition = clipOf(MediaSource.of(appleFixtureClip()), CLIP_LENGTH / 2)

  private fun clipOf(
    source: MediaSource,
    length: Duration,
  ): EditComposition =
    EditComposition(tracks = listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, length))))))
}
