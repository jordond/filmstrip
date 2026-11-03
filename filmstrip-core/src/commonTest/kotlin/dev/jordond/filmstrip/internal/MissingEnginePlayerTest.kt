package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

class MissingEnginePlayerTest {
  @Test
  fun theStateReportsTheMissingPlayerArtifactAndTheMessageItWasGiven() {
    val player = MissingEnginePlayer(MESSAGE)

    val state = player.state.value
    val error =
      state.status
        .shouldBeInstanceOf<PlaybackStatus.Error>()
        .error
        .shouldBeInstanceOf<PlaybackError.BackendMissing>()

    error.artifact shouldBe PLAYER_ARTIFACT
    error.message shouldBe MESSAGE
    state.playWhenReady.shouldBeFalse()
    state.duration.shouldBeNull()
  }

  @Test
  fun loadingACompositionFailsWithTheSameError() =
    runTest {
      val player = MissingEnginePlayer(MESSAGE)

      val result = player.setComposition(COMPOSITION, startAt = 2.seconds, playWhenReady = true)

      result.shouldBeInstanceOf<SetCompositionResult.Failure>().error shouldBeSameInstanceAs stateError(player)
    }

  @Test
  fun theCallbackFormAnswersBeforeItReturns() {
    val player = MissingEnginePlayer(MESSAGE)
    var answered: SetCompositionResult? = null

    player.setComposition(SetCompositionRequest(COMPOSITION)) { answered = it }

    answered.shouldBeInstanceOf<SetCompositionResult.Failure>().error shouldBeSameInstanceAs stateError(player)
  }

  @Test
  fun aReadbackFailsWithTheSameError() {
    val player = MissingEnginePlayer(MESSAGE)
    var answered: ReadbackResult? = null

    player.readback.requestFrame(1.seconds) { answered = it }

    answered.shouldBeInstanceOf<ReadbackResult.Failure>().error shouldBeSameInstanceAs stateError(player)
  }

  @Test
  fun aStateListenerHearsTheErrorTheMomentItIsAdded() {
    val player = MissingEnginePlayer(MESSAGE)
    val heard = mutableListOf<PlayerState>()

    player.addStateListener { heard += it }

    heard shouldBe listOf(player.state.value)
  }

  @Test
  fun transportCallsLeaveThePlayheadAtZeroAndTheErrorInPlace() =
    runTest {
      val player = MissingEnginePlayer(MESSAGE)
      val before = player.state.value

      player.play()
      player.seekTo(3.seconds)
      player.stepFrames(4)

      player.currentPosition() shouldBe Duration.ZERO
      player.positionFlow(100.seconds).toList() shouldBe listOf(Duration.ZERO)
      val heard = mutableListOf<Duration>()
      player.addPositionListener(100.seconds) { heard += it }
      heard shouldBe listOf(Duration.ZERO)
      player.state.value shouldBeSameInstanceAs before
      player.nativePlayer.shouldBeNull()
    }

  private fun stateError(player: MissingEnginePlayer): PlaybackError =
    player.state.value.status
      .shouldBeInstanceOf<PlaybackStatus.Error>()
      .error

  private companion object {
    const val MESSAGE = "`preview` needs a playback backend."

    const val PLAYER_ARTIFACT = "dev.jordond.filmstrip:filmstrip-player"

    val COMPOSITION: EditComposition = compositionOf { clip(MediaSource.of("/fixtures/clip.mp4")) }
  }
}
