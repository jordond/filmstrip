package dev.jordond.filmstrip.player.internal.thumbnail

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.CLIP_LENGTH
import dev.jordond.filmstrip.player.CONTRACT_COMPONENTS
import dev.jordond.filmstrip.player.FIXTURE_FRAME
import dev.jordond.filmstrip.player.MID_GOP_POSITION
import dev.jordond.filmstrip.player.internal.HeightLowering
import dev.jordond.filmstrip.player.internal.IndexedStream
import dev.jordond.filmstrip.player.internal.PlanResult
import dev.jordond.filmstrip.player.internal.PreviewFrame
import dev.jordond.filmstrip.player.internal.frameAt
import dev.jordond.filmstrip.player.internal.frameRate
import dev.jordond.filmstrip.player.jvmFixtureClip
import dev.jordond.filmstrip.player.jvmFixtureComposition
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withTimeout
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How the desktop strip lowers a request and what it hands back, against the real toolchain.
 */
@OptIn(ExperimentalCoroutinesApi::class)
class FfmpegThumbnailSourceTest {
  private val planner = FfmpegThumbnailPlanner(CONTRACT_COMPONENTS)

  @Test
  fun `a height below the export's lowers at that height and lays text out against the natural frame`(): Unit =
    runBlocking {
      val ready =
        planner
          .lower(
            request(height = HALF_HEIGHT),
          ).shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>()
          .plan

      ready.spec shouldBe ExportSpec(heightPx = HALF_HEIGHT)
      ready.layoutSize shouldBe FIXTURE_FRAME
      ready.resolved.output.size.height shouldBe HALF_HEIGHT
    }

  @Test
  fun `a height at or above the export's lowers once at the export's frame`(): Unit =
    runBlocking {
      for (height in listOf(FIXTURE_FRAME.height, FIXTURE_FRAME.height * 2, null)) {
        val ready = planner.lower(request(height = height)).shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>().plan

        ready.spec shouldBe ExportSpec()
        ready.layoutSize.shouldBeNull()
        ready.resolved.output.size shouldBe FIXTURE_FRAME
      }
    }

  @Test
  fun `an edit the export refuses is refused here too`(): Unit =
    runBlocking {
      planner.lower(request(unreadable())).shouldBeInstanceOf<PlanResult.Refused<*>>()

      val result = render(request(unreadable()))

      result.shouldBeInstanceOf<ThumbnailResult.Failure>()
    }

  // A resolver that throws rather than refusing still leaves the strip with an answer for the frame.
  @Test
  fun `a lowering that throws is answered as a failure`(): Unit =
    runBlocking {
      val throwing =
        CONTRACT_COMPONENTS
          .newBuilder()
          .addEffectResolver(EffectResolver { _, _, _ -> error("the resolver broke") })
          .build()
      val graded = jvmFixtureComposition().withEffects(listOf(Brightness(0.2f)))

      val result = render(request(graded), FfmpegThumbnailPlanner(throwing))

      val error = result.shouldBeInstanceOf<ThumbnailResult.Failure>().error
      error.shouldBeInstanceOf<FilmstripError.Underlying>().message shouldBe "the resolver broke"
    }

  @Test
  fun `a thumbnail is the frame its pump renders, packed opaque on the slot it rounds to`(): Unit =
    runBlocking {
      val request = request(height = HALF_HEIGHT)
      val ready = planner.lower(request).shouldBeInstanceOf<PlanResult.Ready<HeightLowering>>().plan
      val expected = renderedFrame(ready, request)

      val thumbnail = render(request).shouldBeInstanceOf<ThumbnailResult.Success>()
      try {
        val rate = ready.resolved.frameRate
        thumbnail.presentationTime shouldBe outputSlotTime(outputSlotOf(MID_GOP_POSITION, rate), rate)
        Size(thumbnail.image.widthPx, thumbnail.image.heightPx) shouldBe ready.resolved.output.size

        val rgba = thumbnail.image.toRgba8888()
        val mismatched =
          expected.pixels.indices.filter { index ->
            val want = if (index % CHANNELS == ALPHA) OPAQUE else expected.pixels[index]
            rgba[index] != want
          }
        mismatched.shouldBeEmpty()
      } finally {
        thumbnail.image.close()
      }
    }

  // The source went between the lowering and the process, so only the exit can say which one it was.
  @Test
  fun `a process that fails on a missing source fails the thumbnail with the source the stream names`(): Unit =
    runBlocking {
      val stream =
        IndexedStream(MID_GOP_POSITION, frames = 0, status = FAILED, stderr = NO_SUCH_FILE, failure = GONE)
      val scripted =
        object : FfmpegThumbnailPlanner(CONTRACT_COMPONENTS) {
          override suspend fun open(
            plan: HeightLowering,
            composition: EditComposition,
            at: Duration,
          ): PreviewStreamResult = PreviewStreamResult.Opened(stream)
        }

      val result = render(request(), scripted)

      val error = result.shouldBeInstanceOf<ThumbnailResult.Failure>().error
      error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      error shouldBe GONE
    }

  @Test
  fun `a request cancelled before it renders is never answered`() =
    runTest {
      val source = FfmpegThumbnailSource(backgroundScope, planner)
      val answers = mutableListOf<ThumbnailResult>()

      source.requestThumbnail(request(height = HALF_HEIGHT)) { answers += it }.cancel()
      runCurrent()

      answers.shouldBeEmpty()
    }

  private suspend fun render(
    request: ThumbnailRequest,
    planner: FfmpegThumbnailPlanner = this.planner,
  ): ThumbnailResult {
    val scope = CoroutineScope(Dispatchers.Default + SupervisorJob())
    try {
      val answer = CompletableDeferred<ThumbnailResult>()
      FfmpegThumbnailSource(scope, planner).requestThumbnail(request) { answer.complete(it) }
      return withTimeout(BUDGET) { answer.await() }
    } finally {
      scope.cancel()
    }
  }

  // The frame a pump of the same lowering reads at the request's position, straight off the process.
  private suspend fun renderedFrame(
    ready: HeightLowering,
    request: ThumbnailRequest,
  ): PreviewFrame {
    val stream =
      when (val opened = planner.open(ready, request.composition, request.position)) {
        is PreviewStreamResult.Opened -> opened.stream
        is PreviewStreamResult.Refused -> fail("the pump refused the fixture: ${opened.error.message}")
      }
    try {
      return stream.frameAt(request.position, ready.resolved.frameRate).shouldNotBeNull()
    } finally {
      stream.close()
    }
  }

  private fun request(
    composition: EditComposition = jvmFixtureComposition(),
    height: Int? = HALF_HEIGHT,
  ): ThumbnailRequest = ThumbnailRequest(composition, MID_GOP_POSITION, height, REVISION, precise = true)

  private fun unreadable(): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(listOf(Clip(MediaSource.of(jvmFixtureClip() + ".absent"), TimeRange.of(Duration.ZERO, CLIP_LENGTH)))),
        ),
    )

  private companion object {
    // Half the fixture's height, which is in the middle of the range a cap applies to.
    val HALF_HEIGHT = FIXTURE_FRAME.height / 2

    const val REVISION = 1L
    const val CHANNELS = 4
    const val ALPHA = 3
    const val OPAQUE = 0xFF.toByte()
    val BUDGET = 60.seconds

    // What ffmpeg exits with on an input it could not open.
    const val FAILED = 1

    const val NO_SUCH_FILE = "scripted.mp4: No such file or directory"

    val GONE = FilmstripError.SourceUnreadable(source = "scripted.mp4", message = NO_SUCH_FILE)
  }
}
