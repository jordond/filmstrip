package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.UnreadableCase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.minutes
import kotlin.time.TimeSource

/**
 * Exports of a source that cannot be read, one per way of being unreadable, each timed from the plan to the export's
 * last status.
 *
 * Every case has to end in a refused plan or a failed export, never a written file, and a truncated `mdat` has to be
 * refused at the plan with the message [UnreadableCase.refusalAt] gives. The times and errors are printed,
 * one line per case starting with [TAG].
 */
@OptIn(InternalFilmstripApi::class)
class FfmpegUnreadableSourceMediaTest {
  private val filmstrip = Filmstrip { ffmpegBackend() }

  @Test
  fun `a truncated mdat never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.TruncatedMdat) }

  @Test
  fun `a garbled mdat never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.GarbledMdat) }

  @Test
  fun `a video codec with no decoder is refused at plan`() =
    runTest(timeout = TIMEOUT) { measure(UnreadableCase.UnknownCodec) }

  @Test
  fun `a file with no moov never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.NoMoov) }

  @Test
  fun `an empty file never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.ZeroBytes) }

  @Test
  fun `a file of text never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.NotVideo) }

  @Test
  fun `a missing file never exports`() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.Missing) }

  private suspend fun measure(case: UnreadableCase) =
    withContext(Dispatchers.Default) {
      val path = File.createTempFile("filmstrip-unreadable-${case.name}", ".mp4").also { it.delete() }.absolutePath
      val bytes = case.bytesFrom(File(System.getProperty("filmstrip.fixtures"), FIXTURE).readBytes())
      bytes?.let(File(path)::writeBytes)
      val source = MediaSource.of(path)
      val refusal = case.refusalAt(source, bytes)
      val composition = Composition(listOf(Track(listOf(Clip(source)))))
      val started = TimeSource.Monotonic.markNow()
      val plan =
        when (val verdict = filmstrip.plan(composition, ExportSpec())) {
          is Verdict.Incapable -> {
            val reasons = verdict.reasons.map { it.message }
            println("$TAG ${case.name} refused at plan after ${started.elapsedNow()}: $reasons")
            refusal?.let { reasons shouldContain it }
            return@withContext
          }
          is Verdict.Capable -> {
            verdict.plan
          }
          is Verdict.Degraded -> {
            verdict.plan
          }
        }
      withClue("${case.name} planned") { refusal.shouldBeNull() }
      val planned = started.elapsedNow()
      val sink = MediaSink.of(File.createTempFile("filmstrip-unreadable-out", ".mp4").absolutePath)
      val last = filmstrip.export(plan, sink).toList().last()
      val ended = started.elapsedNow() - planned
      val outcome =
        (last as? ExportStatus.Failure)?.let { "${it.error::class.simpleName} ${it.error.message}" } ?: "$last"
      println("$TAG ${case.name} planned in $planned, export ended after $ended: $outcome")
      withClue("${case.name} exported") { last.shouldNotBeInstanceOf<ExportStatus.Success>() }
    }

  private companion object {
    const val TAG = "FilmstripUnreadable"
    const val FIXTURE = "export_landscape.mp4"
    val TIMEOUT = 2.minutes
  }
}
