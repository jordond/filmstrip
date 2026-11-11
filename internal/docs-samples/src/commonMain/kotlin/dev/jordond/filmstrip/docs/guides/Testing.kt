package dev.jordond.filmstrip.docs.guides

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.testing.FakeFilmstrip
import dev.jordond.filmstrip.testing.FakeVideoPlayer
import dev.jordond.filmstrip.testing.FilmstripCall
import dev.jordond.filmstrip.testing.PlayerCall
import dev.jordond.filmstrip.testing.testDegradedVerdict
import dev.jordond.filmstrip.testing.testExportPlan
import dev.jordond.filmstrip.testing.testIncapableVerdict
import dev.jordond.filmstrip.testing.testMediaInfo
import dev.jordond.filmstrip.testing.testMediaSink
import dev.jordond.filmstrip.testing.testMediaSource
import dev.jordond.filmstrip.testing.testVideoTrack
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestResult
import kotlinx.coroutines.test.advanceTimeBy
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.time.Duration.Companion.seconds

// region subject
class ExportScreen(
  private val filmstrip: Filmstrip,
) {
  var label: String = "Idle"
    private set

  suspend fun export(
    composition: Composition,
    to: MediaSink,
  ) {
    filmstrip.export(composition, ExportSpec.Share, to).collect { status ->
      label =
        when (status) {
          ExportStatus.Started -> "Starting"
          is ExportStatus.Adjusted -> "Adjusted"
          is ExportStatus.Progress -> "${(status.fraction * 100).toInt()}%"
          is ExportStatus.Success -> "Done"
          is ExportStatus.Failure -> status.error.message
        }
    }
  }
}
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun playerOnTheTestClock(): TestResult =
  // region player
  runTest {
    val player = FakeVideoPlayer(this, duration = 10.seconds)

    player.play()
    advanceTimeBy(4.seconds)

    check(player.state.value.playWhenReady)
    check(player.currentPosition() in 3.9.seconds..4.seconds)
    check(player.calls.last() == PlayerCall.Play)

    advanceTimeBy(10.seconds)
    check(player.state.value.status == PlaybackStatus.Ended)
    player.close()
  }
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun playerSeeks(): TestResult =
  // region seek
  runTest {
    val player = FakeVideoPlayer(this, duration = 10.seconds)

    player.seekTo(2.seconds, SeekAccuracy.Exact)
    runCurrent()
    check(player.currentPosition() == 2.seconds)

    player.holdSeeks = true
    player.seekTo(6.seconds, SeekAccuracy.Exact)
    runCurrent()
    check(player.state.value.isSeeking)

    player.landSeek()
    runCurrent()
    check(!player.state.value.isSeeking)
    player.close()
  }
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun playerShuttles(): TestResult =
  // region shuttle
  runTest {
    val player = FakeVideoPlayer(this, duration = 10.seconds)
    val edges = mutableListOf<PlaybackEvent.ShuttleReachedEdge>()
    player.addEventListener { if (it is PlaybackEvent.ShuttleReachedEdge) edges += it }

    player.beginShuttle(8f)
    advanceTimeBy(10.seconds)

    check(edges.size == 1)
    check(player.state.value.shuttleRate == 8f)

    player.endShuttle()
    check(player.state.value.shuttleRate == null)
    player.close()
  }
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun playerMisbehaves(): TestResult =
  // region backend
  runTest {
    val player = FakeVideoPlayer(this)

    player.setStatus(PlaybackStatus.Preparing)
    player.setStatus(PlaybackStatus.Ready)
    player.setStalled(true)
    player.emit(PlaybackEvent.FirstFrameRendered)
    player.fail(PlaybackError.DecoderUnavailable("The decoder is busy."))
    check(player.state.value.status is PlaybackStatus.Error)

    val broken = PlaybackError.SourceUnreadable("The file is gone.")
    player.onSetComposition = { SetCompositionResult.Failure(broken) }
    val result = player.setComposition(Composition(emptyList()))
    check(result == SetCompositionResult.Failure(broken))
    check(player.compositions.size == 2)
    player.close()
  }
// endregion

internal fun filmstripAnswers(): TestResult =
  // region scripts
  runTest {
    val filmstrip = FakeFilmstrip(this)
    val silent4k =
      testMediaInfo(
        video = testVideoTrack(displaySize = Size(3840, 2160)),
        audioTracks = emptyList(),
      )
    filmstrip.onProbe = { source ->
      when (source) {
        testMediaSource("silent-4k") -> ProbeResult.Success(silent4k)
        else -> ProbeResult.Success(testMediaInfo())
      }
    }
    filmstrip.onPlan = { _, spec ->
      if (spec == ExportSpec.Archive) testIncapableVerdict() else testDegradedVerdict()
    }
    filmstrip.onFrame = { _, _ -> FrameResult.Failure(FilmstripError.InvalidComposition("Nothing to draw.")) }

    val probed = filmstrip.probe(testMediaSource("silent-4k"))
    val verdict = filmstrip.plan(Composition(emptyList()), ExportSpec.Archive)

    check(probed == ProbeResult.Success(silent4k))
    check(verdict is Verdict.Incapable)
    check(filmstrip.calls.first() == FilmstripCall.Probe(testMediaSource("silent-4k")))
  }
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun exportIsDriven(): TestResult =
  // region export
  runTest {
    val filmstrip = FakeFilmstrip(this)
    val screen = ExportScreen(filmstrip)

    val job = launch { screen.export(Composition(emptyList()), testMediaSink()) }
    runCurrent()
    val export = filmstrip.exports.single()

    export.start()
    runCurrent()
    check(screen.label == "Starting")

    export.progress(0.5f)
    runCurrent()
    check(screen.label == "50%")

    export.succeed()
    runCurrent()
    check(screen.label == "Done")
    check(job.isCompleted)
  }
// endregion

@OptIn(ExperimentalCoroutinesApi::class)
internal fun exportsTakeTurns(): TestResult =
  // region export-queue
  runTest {
    val filmstrip = FakeFilmstrip(this)
    val first = launch { filmstrip.export(testExportPlan(), testMediaSink("a")).collect { } }
    val second = launch { filmstrip.export(testExportPlan(), testMediaSink("b")).collect { } }
    runCurrent()
    val (running, waiting) = filmstrip.exports

    check(running.isRunning)
    check(!waiting.isRunning)

    first.cancelAndJoin()
    runCurrent()
    check(running.wasCancelled)
    check(waiting.isRunning)

    waiting.succeed()
    second.join()
  }
// endregion

internal fun previewIsAFakePlayer(): TestResult =
  // region preview
  runTest {
    val filmstrip = FakeFilmstrip(this)
    filmstrip.onPreview = { composition, _ ->
      FakeVideoPlayer(this, composition, duration = 30.seconds)
    }

    val player = filmstrip.preview(Composition(emptyList()))

    check(player === filmstrip.players.single())
    check(player.state.value.duration == 30.seconds)
    player.close()
  }
// endregion
