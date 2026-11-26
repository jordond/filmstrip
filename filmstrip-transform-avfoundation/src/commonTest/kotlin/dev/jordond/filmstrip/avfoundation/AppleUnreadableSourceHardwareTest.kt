package dev.jordond.filmstrip.avfoundation

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
import dev.jordond.filmstrip.test.TestScratch
import dev.jordond.filmstrip.test.UnreadableCase
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.types.shouldNotBeInstanceOf
import kotlinx.cinterop.ByteVar
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.create
import platform.Foundation.dataWithContentsOfFile
import platform.Foundation.writeToFile
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
@OptIn(InternalFilmstripApi::class, ExperimentalForeignApi::class)
class AppleUnreadableSourceHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

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
      val path = TestScratch.directory + "filmstrip-unreadable-${case.name}.mp4"
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
      val bytes = case.bytesFrom(NSData.dataWithContentsOfFile(fixture(FIXTURE))!!.toByteArray())
      bytes?.let { writeBytes(path, it) }
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
      val sink = MediaSink.of(TestScratch.directory + "filmstrip-unreadable-out.mp4")
      val last = filmstrip.export(plan, sink).toList().last()
      val ended = started.elapsedNow() - planned
      val outcome =
        (last as? ExportStatus.Failure)?.let { "${it.error::class.simpleName} ${it.error.message}" } ?: "$last"
      println("$TAG ${case.name} planned in $planned, export ended after $ended: $outcome")
      withClue("${case.name} exported") { last.shouldNotBeInstanceOf<ExportStatus.Success>() }
    }

  private fun NSData.toByteArray(): ByteArray = bytes?.reinterpret<ByteVar>()?.readBytes(length.toInt()) ?: ByteArray(0)

  private fun writeBytes(
    path: String,
    bytes: ByteArray,
  ) {
    if (bytes.isEmpty()) {
      NSFileManager.defaultManager.createFileAtPath(path, null, null)
      return
    }
    bytes.usePinned { NSData.create(bytes = it.addressOf(0), length = bytes.size.toULong()) }.writeToFile(path, true)
  }

  private companion object {
    const val TAG = "FilmstripUnreadable"
    const val FIXTURE = "apple_export_a.mp4"
    val TIMEOUT = 2.minutes
  }
}
