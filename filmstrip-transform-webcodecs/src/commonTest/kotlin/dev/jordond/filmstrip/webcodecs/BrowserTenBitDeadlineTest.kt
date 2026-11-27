package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.FormatHint
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ExportGrade
import dev.jordond.filmstrip.transform.internal.ProbeOperation
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.ProbeExpired
import dev.jordond.filmstrip.webcodecs.internal.SourceCache
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSink
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the plan's ten-bit check ends when its frame read is still waiting at the deadline, with an injected read so
 * nothing real decodes inside the window.
 */
class BrowserTenBitDeadlineTest {
  @Test
  fun aFrameReadThatNeverSettlesFailsAsTimedOutRatherThanNotTenBit() =
    runTest {
      val reader = readerOver(tenBitDeadline = SHORT) { awaitCancellation() }
      try {
        val expired = assertFailsWith<ProbeExpired> { reader.readsTenBit() }

        expired.error.operation shouldBe ProbeOperation.TEN_BIT_READ
        expired.error.deadline shouldBe SHORT
      } finally {
        reader.close()
      }
    }

  @Test
  fun aFrameReadThatGivesNothingStillAnswersNotTenBit() =
    runTest {
      val reader = readerOver(tenBitDeadline = SHORT) { null }
      try {
        reader.readsTenBit() shouldBe false
      } finally {
        reader.close()
      }
    }

  @Test
  fun anHdrSourceWhoseTenBitCheckNeverSettlesRefusesThePlanAndTheGradeAsTimedOut() =
    runTest {
      val source = MediaSource.Bytes(makeHdrClip(HdrTransfer.Pq) { BRIGHT })
      val sources = SourceCache { SourceReader.of(it, SHORT) { awaitCancellation() } }
      val engine =
        BrowserExportEngine(
          ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
          BrowserProber(),
          null,
          sources,
        )
      val composition = Composition(tracks = listOf(Track(listOf(Clip(source)))), audio = AudioSpec.Remove)
      try {
        val verdict = engine.plan(composition, ExportSpec()).shouldBeInstanceOf<Verdict.Incapable>()
        verdict.reasons.single().shouldBeTenBitTimeout()

        engine
          .exportGrade(
            composition,
            ExportSpec(),
          ).shouldBeInstanceOf<ExportGrade.Refused>()
          .error
          .shouldBeTenBitTimeout()
      } finally {
        sources.close()
      }
    }

  private fun FilmstripError.shouldBeTenBitTimeout() {
    val timedOut = shouldBeInstanceOf<FilmstripError.TimedOut>()
    timedOut.operation shouldBe ProbeOperation.TEN_BIT_READ
    timedOut.deadline shouldBe SHORT
  }

  private suspend fun readerOver(
    tenBitDeadline: Duration,
    firstSampleOf: suspend (VideoSampleSink) -> VideoSample?,
  ): SourceReader =
    SourceReader.of(MediaSource.ofBytes(makeClip(), FormatHint.Mp4), tenBitDeadline, firstSampleOf).shouldNotBeNull()

  private companion object {
    val SHORT = 20.milliseconds
    val BRIGHT = floatArrayOf(240f, 205f, 275f)
  }
}
