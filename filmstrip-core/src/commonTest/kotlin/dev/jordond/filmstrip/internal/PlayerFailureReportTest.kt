package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.PlatformFailure
import dev.jordond.filmstrip.diagnostics.attachFailure
import dev.jordond.filmstrip.diagnostics.redactLocation
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.SetCompositionRequest
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration

@OptIn(InternalFilmstripApi::class)
class PlayerFailureReportTest {
  private val events = mutableListOf<DiagnosticEvent>()
  private val components =
    ComponentRegistry
      .Builder()
      .addDiagnosticListener { events += it }
      .build()
  private val engine = FakePlayerEngine()
  private val player = EngineVideoPlayer(engine, FIRST, components)

  @Test
  fun reachingAnErrorIsReportedOnceAsPlayback() {
    val error = PlaybackError.DecoderUnavailable("Decoder failed.")

    push(PlaybackStatus.Preparing)
    push(PlaybackStatus.Error(error))

    val event = events.single()
    event.name shouldBe DiagnosticEvent.FAILURE
    event.detail[DiagnosticEvent.Detail.STAGE] shouldBe "playback"
    event.detail[DiagnosticEvent.Detail.ERROR] shouldBe "DecoderUnavailable"
    event.detail[DiagnosticEvent.Detail.MESSAGE] shouldBe "Decoder failed."
    event.detail[DiagnosticEvent.Detail.SOURCES] shouldBe redactLocation(FIRST_PATH)
  }

  @Test
  fun theSameErrorRepublishedIsNotASecondFailure() {
    val error = PlaybackError.Underlying(3, "Stopped.")

    push(PlaybackStatus.Error(error))
    push(PlaybackStatus.Error(error))
    push(PlaybackStatus.Error(PlaybackError.Underlying(3, "Stopped.")))

    events.size shouldBe 1
  }

  @Test
  fun recoveringAndFailingAgainIsASecondFailure() {
    val error = PlaybackError.Underlying(3, "Stopped.")

    push(PlaybackStatus.Error(error))
    push(PlaybackStatus.Ready)
    push(PlaybackStatus.Error(error))

    events.size shouldBe 2
  }

  @Test
  fun aDifferentErrorWhileFailedIsASecondFailure() {
    push(PlaybackStatus.Error(PlaybackError.Underlying(3, "Stopped.")))
    push(PlaybackStatus.Error(PlaybackError.SourceUnreadable("Gone.")))

    events.map { it.detail[DiagnosticEvent.Detail.ERROR] } shouldBe listOf("Underlying", "SourceUnreadable")
  }

  @Test
  fun aPlayerThatNeverFailsReportsNothing() {
    push(PlaybackStatus.Preparing)
    push(PlaybackStatus.Ready)
    push(PlaybackStatus.Ended)
    push(PlaybackStatus.Released)

    events.shouldBeEmpty()
  }

  @Test
  fun theFailureNamesTheCompositionLastHandedToTheEngine() {
    player.setComposition(SetCompositionRequest(SECOND)) { }

    push(PlaybackStatus.Error(PlaybackError.Underlying(3, "Stopped.")))

    events.single().detail[DiagnosticEvent.Detail.SOURCES] shouldBe redactLocation(SECOND_PATH)
  }

  @Test
  fun whatTheEngineAttachedIsReadAndTheBackendNamed() {
    val error = PlaybackError.DecoderUnavailable("Decoder failed.")
    components.attachFailure(error, PlatformFailure("media3", code = 4003, codecName = "c2.goldfish.h264.decoder"))

    push(PlaybackStatus.Error(error))

    val event = events.single()
    event.source shouldBe "media3"
    event.detail[DiagnosticEvent.Detail.ERROR_CODE] shouldBe "4003"
    event.detail[DiagnosticEvent.Detail.CODEC_NAME] shouldBe "c2.goldfish.h264.decoder"
  }

  private fun push(status: PlaybackStatus) {
    engine.push { onStateChanged(PlayerState(status, false, false, false, Duration.ZERO)) }
  }

  private companion object {
    const val FIRST_PATH = "/Users/someone/Movies/first.mov"
    const val SECOND_PATH = "/Users/someone/Movies/second.mp4"
    val FIRST = compositionOf { clip(MediaSource.of(FIRST_PATH)) }
    val SECOND = compositionOf { clip(MediaSource.of(SECOND_PATH)) }
  }
}
