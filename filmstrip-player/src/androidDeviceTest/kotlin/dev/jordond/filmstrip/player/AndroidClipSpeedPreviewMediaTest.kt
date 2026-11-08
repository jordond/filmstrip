package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailPlanner
import dev.jordond.filmstrip.player.internal.thumbnail.Media3ThumbnailSource
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A clip at speed previewed on a real `CompositionPlayer`: a seek lands on the composition's own slot and shows the
 * source frame the shared offset reaches, and a strip frame reads that same source frame.
 *
 * The source is the panned fixture written to a file, since the fixture itself is one still picture. Each case first
 * checks the source frame it expects is not the one a clip at speed 1 shows at that time, so a preview that ignored the
 * speed fails rather than matching a picture that does not move.
 */
class AndroidClipSpeedPreviewMediaTest {
  @Test
  fun aSeekIntoAClipAtSpeedLandsOnItsSlotAndShowsTheSourceFrameTheOffsetReaches() =
    contractTest { scope ->
      val subject = AndroidEngineUnderTest(scope)
      val moving = movingSource()
      withEngine(subject.engine) { recorder ->
        CASES.forEach { (speed, target) ->
          val landing = outputSlotTime(outputSlotOf(target, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

          subject.engine.awaitComposition(reference(moving)).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the plain clip to be presentable") { recorder.lastState.hasComposition }
          val reached =
            subject.engine.readback
              .awaitFrame(sourceOffsetAt(landing, speed))
              .asTestFrame()
          val unmoved =
            subject.engine.readback
              .awaitFrame(landing)
              .asTestFrame()
          reached.assertUnlike(unmoved, speed)

          subject.engine.awaitComposition(composition(moving, speed)).shouldBeInstanceOf<SetCompositionResult.Success>()
          awaitContract("the clip at $speed to be presentable") { recorder.lastState.hasComposition }
          val mark = recorder.events.size
          subject.engine.seekTo(target, SeekAccuracy.Exact)
          awaitContract("the seek to land") {
            recorder.events.drop(mark).any { it is PlaybackEvent.SeekCompleted }
          }
          recorder.events
            .drop(mark)
            .first { it is PlaybackEvent.SeekCompleted }
            .shouldBeInstanceOf<PlaybackEvent.SeekCompleted>()
            .position shouldBe landing

          val shown =
            subject.engine.readback
              .awaitFrame(landing)
              .asTestFrame()
          assertFramesSimilar(reached, shown, message = "the preview at $speed against the source frame it reaches")
        }
      }
    }

  @Test
  fun aThumbnailInsideAClipAtSpeedReadsTheSourceFrameTheOffsetReaches() =
    contractTest { scope ->
      val source = source(scope)
      val moving = movingSource()
      CASES.forEachIndexed { index, (speed, target) ->
        val revision = REVISION + index * 3
        val reached = source.frameAt(reference(moving), sourceOffsetAt(target, speed), revision)
        val unmoved = source.frameAt(reference(moving), target, revision + 1)
        reached.assertUnlike(unmoved, speed)

        val shown = source.frameAt(composition(moving, speed), target, revision + 2)
        assertFramesSimilar(reached, shown, message = "the thumbnail at $speed against the source frame it reaches")
      }
    }

  private fun TestFrame.assertUnlike(
    other: TestFrame,
    speed: Double,
  ) {
    val comparison = compareFrames(this, other)
    withClue("the source frames apart at $speed read ${comparison.psnrDb} dB, so the picture barely moves there") {
      (comparison.psnrDb < MOVED_BELOW_PSNR_DB).shouldBeTrue()
    }
  }

  private fun source(scope: CoroutineScope): ThumbnailSource =
    Media3ThumbnailSource(
      scope = scope,
      context = contractContext(),
      planner = Media3ThumbnailPlanner(CONTRACT_COMPONENTS),
    )

  private suspend fun ThumbnailSource.frameAt(
    composition: EditComposition,
    position: Duration,
    revision: Long,
  ): TestFrame {
    val request = ThumbnailRequest(composition, position, FIXTURE_FRAME.height, revision, precise = true)
    val thumbnail =
      suspendCancellableCoroutine { continuation ->
        val handle: Cancellable =
          requestThumbnail(request) { result ->
            if (!continuation.isActive) return@requestThumbnail
            when (result) {
              is ThumbnailResult.Success -> continuation.resume(result)
              is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
            }
          }
        continuation.invokeOnCancellation { handle.cancel() }
      }
    return try {
      TestFrame(thumbnail.image.toRgba8888(), Size(thumbnail.image.widthPx, thumbnail.image.heightPx))
    } finally {
      thumbnail.image.close()
    }
  }

  private suspend fun movingSource(): MediaSource {
    val panned = Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH), effects = listOf(FIXTURE_PAN))
    return MediaSource.of(export(EditComposition(listOf(Track(listOf(panned))))).path)
  }

  private fun composition(
    source: MediaSource,
    speed: Double,
  ): EditComposition =
    EditComposition(listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH), speed = speed)))))

  // A bare clip at speed 1 is a copy and draws its source as it stands, while a clip at speed transcodes, which moves
  // an SD source onto BT.709's primaries. The panned source is SD wherever the encoder writes BT.601, as on API 30. A
  // fade over the first slot makes this clip transcode too and leaves every frame read here untouched.
  private fun reference(source: MediaSource): EditComposition =
    EditComposition(
      listOf(
        Track(
          listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
          fadeIn = 1.seconds / FIXTURE_FRAME_RATE,
        ),
      ),
    )

  private companion object {
    const val REVISION = 300L
    const val MOVED_BELOW_PSNR_DB = 40.0

    // A speed in the middle of the range either side of 1, read far enough in that the source frame sits well away.
    val CASES = listOf(0.7 to 1.5.seconds, 1.5 to 800.milliseconds)
  }
}
