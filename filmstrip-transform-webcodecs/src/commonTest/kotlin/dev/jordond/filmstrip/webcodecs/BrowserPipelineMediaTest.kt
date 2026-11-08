package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.BrowserPipeline
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.FrameStream
import dev.jordond.filmstrip.webcodecs.internal.PipelineResult
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.verify
import dev.jordond.filmstrip.webcodecs.internal.verifyAudio
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

/**
 * The export loop on its own: what it encodes for a render, where each frame lands on the output
 * grid, and what it does when a run stops part way.
 */
class BrowserPipelineMediaTest {
  @Test
  fun aRunEncodesEverySlotThePlanCountedOnTheSharedGrid() =
    runTest {
      val render = renderOf(compositionOf(makeClip(frames = FRAMES)))

      val (result, progress) = runAndRecord(render)

      result.encodedFrames shouldBe render.estimatedFrames
      progress.map { it.first } shouldBe (1L..render.estimatedFrames).toList()
      progress.map { it.second } shouldBe (0L until render.estimatedFrames).map(render::slotUs)
      result.file
        .verify()
        .shouldNotBeNull()
        .decodedFrames shouldBe result.encodedFrames
    }

  // A track that opens late fills its lead slots with the fill alone before the first clip, and
  // those slots count toward the same progress the clip carries on.
  @Test
  fun aLateTrackEncodesItsLeadAheadOfTheClip() =
    runTest {
      val start = outputSlotTime(LEAD_SLOTS, FRAME_RATE)
      val render = renderOf(compositionOf(makeClip(frames = FRAMES), start = start))

      val (result, progress) = runAndRecord(render)

      render.leadFrames shouldBe outputSlotOf(start, FRAME_RATE)
      withClue("the lead was ${render.leadFrames} slots") {
        (render.leadFrames in 1 until render.estimatedFrames) shouldBe true
      }
      result.encodedFrames shouldBe render.estimatedFrames
      progress[(render.leadFrames - 1).toInt()].second shouldBe render.slotUs(render.leadFrames - 1)
      progress[render.leadFrames.toInt()].second shouldBe render.slotUs(render.leadFrames)
      result.file
        .verify()
        .shouldNotBeNull()
        .decodedFrames shouldBe result.encodedFrames
    }

  // The loop is Kotlin so a run can stop between frames, and one that stops writes nothing more.
  @Test
  fun aRunThatStopsBetweenFramesEncodesNoFurther() =
    runTest {
      val render = renderOf(compositionOf(makeClip(frames = FRAMES)))
      var reported = 0L

      val sources = SourceCache()
      try {
        assertFailsWith<Stopped> {
          BrowserPipeline(render, sources).run { count, _ ->
            reported = count
            if (count == STOP_AT) throw Stopped()
          }
        }
      } finally {
        sources.close()
      }

      reported shouldBe STOP_AT
    }

  // Nothing has been drawn yet, so the only thing holding the sink open is the stream the first pull is
  // waiting on.
  @Test
  fun aRunCancelledDuringItsFirstDecodeClosesTheStream() =
    runTest {
      val render = renderOf(compositionOf(makeClip(frames = FRAMES)))
      val pulled = CompletableDeferred<Unit>()
      var closes = 0
      val stream =
        FrameStream(
          stubIterator(
            next = {
              pulled.complete(Unit)
              pendingStep()
            },
            close = {
              closes++
              resolvedStep(true, null)
            },
          ),
        ) { it.close() }

      val sources = SourceCache()
      try {
        val job = launch { BrowserPipeline(render, sources) { stream }.run { _, _ -> } }
        pulled.await()
        job.cancelAndJoin()
      } finally {
        sources.close()
      }

      closes shouldBe 1
    }

  @Test
  fun aRunWhoseFirstDecodeFailsClosesTheStream() =
    runTest {
      val render = renderOf(compositionOf(makeClip(frames = FRAMES)))
      var closes = 0
      val stream =
        FrameStream(
          stubIterator(
            next = { rejectedStep("the decoder gave up") },
            close = {
              closes++
              resolvedStep(true, null)
            },
          ),
        ) { it.close() }

      val sources = SourceCache()
      try {
        assertFailsWith<Throwable> { BrowserPipeline(render, sources) { stream }.run { _, _ -> } }
      } finally {
        sources.close()
      }

      closes shouldBe 1
    }

  // No frame is drawn on an audio-only run, so nothing is reported against a frame count either.
  @Test
  fun anAudioOnlyRunMixesWithoutReportingProgress() =
    runTest {
      if (!browserEncodesAac()) return@runTest
      val render =
        renderOf(
          compositionOf(makeClipWithAudio(frames = FRAME_RATE), audio = AudioSpec.AudioOnly),
          ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.Aac),
        )

      val (result, progress) = runAndRecord(render)

      result.encodedFrames shouldBe 0L
      withClue("an audio-only run reported $progress") { progress.isEmpty() shouldBe true }
      val verified = withClue("the audio-only file did not read back") { result.file.verifyAudio().shouldNotBeNull() }
      verified.video.shouldBeNull()
      verified.audio.shouldNotBeNull()
    }

  private suspend fun runAndRecord(render: BrowserRender): Pair<PipelineResult, List<Pair<Long, Double>>> {
    val progress = mutableListOf<Pair<Long, Double>>()
    val sources = SourceCache()
    try {
      val result = BrowserPipeline(render, sources).run { count, outputUs -> progress += count to outputUs }
      return result to progress
    } finally {
      sources.close()
    }
  }

  private fun compositionOf(
    bytes: ByteArray,
    start: Duration = Duration.ZERO,
    audio: AudioSpec = AudioSpec.Remove,
  ): EditComposition =
    EditComposition(tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(bytes))), start = start)), audio = audio)

  private class Stopped : Exception("stopped")

  private companion object {
    const val FRAMES = 12
    const val FRAME_RATE = 30
    const val LEAD_SLOTS = 4L
    const val STOP_AT = 3L
  }
}
