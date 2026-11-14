package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.FrameResult
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerConfig
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.Job
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class FakeFilmstripTest {
  @Test
  fun `a probe answers the default file until a source is scripted`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val silent = testMediaInfo(audioTracks = emptyList())
      val broken = FilmstripError.SourceUnreadable("broken", "Could not read broken.")
      filmstrip.onProbe = { source ->
        when (source) {
          testMediaSource("silent") -> ProbeResult.Success(silent)
          testMediaSource("broken") -> ProbeResult.Failure(broken)
          else -> ProbeResult.Success(testMediaInfo())
        }
      }

      filmstrip.probe(testMediaSource("silent")).shouldBeInstanceOf<ProbeResult.Success>().info shouldBe silent
      filmstrip.probe(testMediaSource("broken")).shouldBeInstanceOf<ProbeResult.Failure>().error shouldBe broken
      FakeFilmstrip(this).probe(testMediaSource()) shouldBe ProbeResult.Success(testMediaInfo())
      filmstrip.calls.shouldContainExactly(
        FilmstripCall.Probe(testMediaSource("silent")),
        FilmstripCall.Probe(testMediaSource("broken")),
      )
    }

  @Test
  fun `capabilities answer the default device or the scripted result`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)

      val device = filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities
      device.encoderFor(VideoCodec.H264)?.codec shouldBe VideoCodec.H264

      val error = FilmstripError.BackendMissing("an-artifact", "No backend.")
      filmstrip.onCapabilities = { CapabilitiesResult.Failure(error) }
      filmstrip.capabilities().shouldBeInstanceOf<CapabilitiesResult.Failure>().error shouldBe error
      filmstrip.calls.shouldContainExactly(FilmstripCall.Capabilities, FilmstripCall.Capabilities)
    }

  @Test
  fun `a plan is capable of what was asked until a verdict is scripted`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val composition = Composition(emptyList())

      val plan = filmstrip.plan(composition, ExportSpec.Share).shouldBeInstanceOf<Verdict.Capable>().plan
      plan.composition shouldBeSameInstanceAs composition
      plan.spec shouldBe ExportSpec.Share

      val incapable = testIncapableVerdict()
      filmstrip.onPlan = { _, spec -> if (spec == ExportSpec.Archive) incapable else testDegradedVerdict() }
      filmstrip.plan(composition, ExportSpec.Archive) shouldBeSameInstanceAs incapable
      filmstrip.plan(composition, ExportSpec.Upload).shouldBeInstanceOf<Verdict.Degraded>()
      filmstrip.calls.shouldContainExactly(
        FilmstripCall.Plan(composition, ExportSpec.Share),
        FilmstripCall.Plan(composition, ExportSpec.Archive),
        FilmstripCall.Plan(composition, ExportSpec.Upload),
      )
    }

  @Test
  fun `parity is exact until an effect is scripted`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)

      filmstrip.parityOf(EffectId("blur")) shouldBe EffectParity.Exact
      filmstrip.onParityOf = { id -> EffectParity.Approximate.takeIf { id == EffectId("grain") } }
      filmstrip.parityOf(EffectId("grain")) shouldBe EffectParity.Approximate
      filmstrip.parityOf(EffectId("blur")).shouldBeNull()
      filmstrip.calls.last() shouldBe FilmstripCall.ParityOf(EffectId("blur"))
    }

  @Test
  fun `a frame is answered by position and recorded with everything asked for`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val composition = Composition(emptyList())
      val late = FilmstripError.InvalidComposition("Past the end.")
      val early = FilmstripError.InvalidComposition("Not yet.")
      filmstrip.onFrame = { _, at -> FrameResult.Failure(if (at > 5.seconds) late else early) }

      filmstrip.frame(composition, 2.seconds).shouldBeInstanceOf<FrameResult.Failure>().error shouldBe early
      filmstrip
        .frame(composition, 7.seconds, heightPx = 360, exportSpec = ExportSpec.Share)
        .shouldBeInstanceOf<FrameResult.Failure>()
        .error shouldBe late
      filmstrip.calls.shouldContainExactly(
        FilmstripCall.Frame(composition, 2.seconds, null, ExportSpec()),
        FilmstripCall.Frame(composition, 7.seconds, 360, ExportSpec.Share),
      )
    }

  @Test
  fun `a frame nobody scripted is a picture landing where it was asked for`() =
    runTest {
      if (!hostMakesImages) return@runTest
      val frame =
        FakeFilmstrip(
          this,
        ).frame(Composition(emptyList()), 3.seconds).shouldBeInstanceOf<FrameResult.Success>()

      frame.presentationTime shouldBe 3.seconds
      frame.image.close()
    }

  @Test
  fun `a height of zero is refused the way the real renderer refuses it`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      var asked = 0
      filmstrip.onFrame =
        { _, _ -> FrameResult.Failure(FilmstripError.InvalidComposition("Scripted.")).also { asked++ } }

      val single = filmstrip.frame(Composition(emptyList()), Duration.ZERO, heightPx = 0)
      val several = filmstrip.frames(Composition(emptyList()), listOf(1.seconds, 2.seconds), heightPx = -1).toList()

      val refusal = single.shouldBeInstanceOf<FrameResult.Failure>().error
      refusal.shouldBeInstanceOf<FilmstripError.InvalidComposition>()
      refusal.message shouldBe
        several
          .first()
          .shouldBeInstanceOf<FrameResult.Failure>()
          .error.message
          .replace("-1", "0")
      several.size shouldBe 2
      asked shouldBe 0
    }

  @Test
  fun `frames answer once per position in the order asked`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val composition = Composition(emptyList())
      val positions = listOf(4.seconds, 1.seconds, 9.seconds)
      filmstrip.onFrame = { _, at -> FrameResult.Failure(FilmstripError.InvalidComposition("$at")) }

      val frames = filmstrip.frames(composition, positions, heightPx = 90).toList()

      frames.map { it.shouldBeInstanceOf<FrameResult.Failure>().error.message } shouldBe positions.map { "$it" }
      filmstrip.calls.shouldContainExactly(FilmstripCall.Frames(composition, positions, 90, ExportSpec()))
    }

  @Test
  fun `a preview is a ready fake player over the composition`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val composition = Composition(emptyList())
      val config = PlayerConfig(exportSpec = ExportSpec.Share)

      val player = filmstrip.preview(composition, config)

      player shouldBeSameInstanceAs filmstrip.players.single()
      player.state.value.status shouldBe PlaybackStatus.Ready
      filmstrip.players
        .single()
        .compositions
        .single() shouldBeSameInstanceAs composition
      filmstrip.calls.shouldContainExactly(FilmstripCall.Preview(composition, config))
      player.close()
    }

  @Test
  fun `a preview hands out the player the test supplies and can open failed`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val supplied = FakeVideoPlayer(this, duration = 3.seconds)
      filmstrip.onPreview = { _, _ -> supplied }

      filmstrip.preview(Composition(emptyList())) shouldBeSameInstanceAs supplied

      val error = PlaybackError.DecoderUnavailable("busy")
      filmstrip.onPreview = { composition, _ -> FakeVideoPlayer(this, composition).also { it.fail(error) } }
      val failed = filmstrip.preview(Composition(emptyList()))
      failed.state.value.status
        .shouldBeInstanceOf<PlaybackStatus.Error>()
        .error shouldBe error
      filmstrip.players.size shouldBe 2
      supplied.close()
      failed.close()
    }

  @Test
  fun `an export delivers each status the handle sends and ends on success`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val plan = testExportPlan(duration = 4.seconds)
      val (seen, job) = collect(filmstrip.export(plan, testMediaSink()))
      val export = filmstrip.exports.single()

      seen.shouldBeEmpty()
      export.isRunning.shouldBeTrue()
      export.start()
      export.progress(0.5f, position = 2.seconds)
      runCurrent()
      seen.shouldContainExactly(ExportStatus.Started, ExportStatus.Progress(0.5f, 2.seconds, null))
      job.isActive.shouldBeTrue()

      export.succeed()
      runCurrent()

      val success = seen.last().shouldBeInstanceOf<ExportStatus.Success>()
      success.output shouldBe testMediaSink()
      success.info.duration shouldBe 4.seconds
      seen.size shouldBe 3
      job.isCompleted.shouldBeTrue()
      export.isFinished.shouldBeTrue()
      export.isRunning.shouldBeFalse()
      export.wasCancelled.shouldBeFalse()
      export.plan shouldBeSameInstanceAs plan
      filmstrip.calls.shouldContainExactly(FilmstripCall.ExportOfPlan(plan, testMediaSink()))
    }

  @Test
  fun `progress and success start an export that was not started and a failure does not`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val (finished, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("a")))
      val (failed, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("b")))
      val error = FilmstripError.InsufficientStorage(requiredBytes = 1_000, message = "The disk is full.")

      filmstrip.exports[0].succeed()
      filmstrip.exports[1].fail(error)
      runCurrent()

      finished.first() shouldBe ExportStatus.Started
      finished.size shouldBe 2
      failed.shouldContainExactly(ExportStatus.Failure(error))
    }

  @Test
  fun `statuses sent before anything collects wait for the collector`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val flow = filmstrip.export(testExportPlan(), testMediaSink())
      filmstrip.exports.single().progress(0.25f)

      flow.first { it is ExportStatus.Progress } shouldBe ExportStatus.Progress(0.25f, null, null)
    }

  @Test
  fun `a collector that goes away before the end is seen as a cancel`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val (seen, job) = collect(filmstrip.export(testExportPlan(), testMediaSink()))
      val export = filmstrip.exports.single()
      export.start()
      runCurrent()

      export.wasCancelled.shouldBeFalse()
      job.cancelAndJoin()

      export.wasCancelled.shouldBeTrue()
      export.isRunning.shouldBeFalse()
      export.isFinished.shouldBeFalse()
      seen.shouldContainExactly(ExportStatus.Started)
    }

  @Test
  fun `a collector that stops at the finish is not a cancel`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val flow = filmstrip.export(testExportPlan(), testMediaSink())
      val export = filmstrip.exports.single()
      export.succeed()

      flow.first { it is ExportStatus.Finished }.shouldBeInstanceOf<ExportStatus.Success>()

      export.wasCancelled.shouldBeFalse()
      export.isFinished.shouldBeTrue()
    }

  @Test
  fun `a second export waits for the first to end before it starts`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val (first, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("a")))
      val (second, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("b")))
      val (running, waiting) = filmstrip.exports

      running.start()
      waiting.start()
      waiting.progress(0.5f)
      runCurrent()
      first.shouldContainExactly(ExportStatus.Started)
      second.shouldBeEmpty()
      waiting.isRunning.shouldBeFalse()

      running.succeed()
      runCurrent()

      second.shouldContainExactly(ExportStatus.Started, ExportStatus.Progress(0.5f, null, null))
      waiting.isRunning.shouldBeTrue()
      waiting.succeed()
      runCurrent()
      second.last().shouldBeInstanceOf<ExportStatus.Success>()
    }

  @Test
  fun `a cancelled export gives its turn to the one waiting`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val (_, firstJob) = collect(filmstrip.export(testExportPlan(), testMediaSink("a")))
      val (second, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("b")))
      filmstrip.exports[1].start()
      runCurrent()
      second.shouldBeEmpty()

      firstJob.cancelAndJoin()
      runCurrent()

      second.shouldContainExactly(ExportStatus.Started)
      filmstrip.exports[1].succeed()
    }

  @Test
  fun `a collector that leaves while waiting keeps what was sent for the next one`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val (_, blocker) = collect(filmstrip.export(testExportPlan(), testMediaSink("a")))
      val flow = filmstrip.export(testExportPlan(), testMediaSink("b"))
      val waiting = filmstrip.exports[1]
      waiting.progress(0.75f)
      val (_, impatient) = collect(flow)

      impatient.cancelAndJoin()
      blocker.cancelAndJoin()
      val (seen, _) = collect(flow)

      waiting.wasCancelled.shouldBeTrue()
      seen.shouldContainExactly(ExportStatus.Started, ExportStatus.Progress(0.75f, null, null))
      waiting.succeed()
    }

  @Test
  fun `exporting a composition runs the plan its verdict carries`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val composition = Composition(emptyList())
      val (seen, _) = collect(filmstrip.export(composition, ExportSpec.Upload, testMediaSink()))
      val export = filmstrip.exports.single()

      export.plan.composition shouldBeSameInstanceAs composition
      export.plan.spec shouldBe ExportSpec.Upload
      export.sink shouldBe testMediaSink()
      export.succeed()
      runCurrent()
      seen.first() shouldBe ExportStatus.Started
      filmstrip.calls.shouldContainExactly(
        FilmstripCall.ExportOfComposition(composition, ExportSpec.Upload, testMediaSink()),
      )
    }

  @Test
  fun `a degraded composition export reports its adjustments before it starts`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val verdict = testDegradedVerdict()
      filmstrip.onPlan = { _, _ -> verdict }
      val (seen, _) = collect(filmstrip.export(Composition(emptyList()), ExportSpec(), testMediaSink()))

      seen.shouldContainExactly(ExportStatus.Adjusted(verdict.adjustments))
      filmstrip.exports
        .single()
        .plan shouldBeSameInstanceAs verdict.plan
      filmstrip.exports.single().succeed()
      runCurrent()
      seen[1] shouldBe ExportStatus.Started
    }

  @Test
  fun `an incapable composition export fails with every reason and never queues`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val verdict =
        testIncapableVerdict(
          reasons =
            listOf(
              FilmstripError.NoEncoder(VideoCodec.Av1, "No AV1 encoder."),
              FilmstripError.InvalidComposition("Nothing to export."),
            ),
        )
      filmstrip.onPlan = { _, _ -> verdict }
      val (blocker, _) = collect(filmstrip.export(testExportPlan(), testMediaSink("a")))

      val statuses = filmstrip.export(Composition(emptyList()), ExportSpec(), testMediaSink("b")).toList()

      statuses.shouldContainExactly(ExportStatus.Failure(verdict.reasons))
      filmstrip.exports.size shouldBe 1
      blocker.shouldBeEmpty()
      filmstrip.exports.single().succeed()
    }

  @Test
  fun `collecting an export again runs it again on the same handle`() =
    runTest {
      val filmstrip = FakeFilmstrip(this)
      val flow = filmstrip.export(testExportPlan(), testMediaSink())
      val export = filmstrip.exports.single()

      export.fail(FilmstripError.InvalidComposition("First run."))
      flow.toList().size shouldBe 1
      export.succeed()
      val again = flow.toList()

      again.first() shouldBe ExportStatus.Started
      again.last().shouldBeInstanceOf<ExportStatus.Success>()
      filmstrip.exports.size shouldBe 1
    }

  private fun TestScope.collect(flow: Flow<ExportStatus>): Pair<List<ExportStatus>, Job> {
    val seen = mutableListOf<ExportStatus>()
    val job = launch { flow.toList(seen) }
    runCurrent()
    return seen to job
  }
}
