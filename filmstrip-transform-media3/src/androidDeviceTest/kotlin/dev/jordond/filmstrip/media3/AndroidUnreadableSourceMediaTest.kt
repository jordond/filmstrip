package dev.jordond.filmstrip.media3

import android.os.SystemClock
import android.util.Log
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

/**
 * Exports of a source that cannot be read, one per way of being unreadable, each timed from the plan to the export's
 * last status.
 *
 * Every case has to end in a refused plan or a failed export, never a written file, and a truncated `mdat` has to be
 * refused at the plan with the message [UnreadableCase.refusalAt] gives. The times and errors go to logcat under [TAG],
 * one line per case.
 */
@OptIn(InternalFilmstripApi::class)
class AndroidUnreadableSourceMediaTest {
  private val probe = Media3ExportProbe()

  @Test
  fun aTruncatedMdatNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.TruncatedMdat) }

  @Test
  fun aGarbledMdatNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.GarbledMdat) }

  @Test
  fun aFileWithNoMoovNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.NoMoov) }

  @Test
  fun anEmptyFileNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.ZeroBytes) }

  @Test
  fun aFileOfTextNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.NotVideo) }

  @Test
  fun aMissingFileNeverExports() = runTest(timeout = TIMEOUT) { measure(UnreadableCase.Missing) }

  @Test
  fun aContentUriNothingAnswersNeverExports() =
    runTest(timeout = TIMEOUT) { measure("DeadContentUri", MediaSource.Uri(DEAD_URI)) }

  @Test
  fun aTruncatedMdatWithNoDeclaredLengthIsRefusedAtPlan() =
    runTest(timeout = TIMEOUT) { measureUnsized(UnsizedSourceProvider.UNSIZED) }

  @Test
  fun aTruncatedMdatThroughAPipeNeverExports() =
    runTest(timeout = TIMEOUT) { measureUnsized(UnsizedSourceProvider.PIPE) }

  @Test
  fun aGarbledMdatBehindAContentUriIsRefusedAtPlan() =
    runTest(timeout = TIMEOUT) { measureUnsized(UnsizedSourceProvider.UNSIZED, UnreadableCase.GarbledMdat) }

  @Test
  fun aWholeFileStillPlans() =
    runTest(timeout = TIMEOUT) {
      val source = MediaSource.of(probe.fixtureFile().path)
      val started = SystemClock.elapsedRealtime()
      val verdict = probe.filmstrip.plan(Composition(listOf(Track(listOf(Clip(source))))), ExportSpec())
      Log.i(TAG, "Whole planned in ${SystemClock.elapsedRealtime() - started} ms")
      verdict.shouldNotBeInstanceOf<Verdict.Incapable>()
    }

  private suspend fun measureUnsized(
    kind: String,
    case: UnreadableCase = UnreadableCase.TruncatedMdat,
  ) {
    val file = File(probe.context.cacheDir, "unsized-${case.name}.mp4")
    val bytes = checkNotNull(case.bytesFrom(probe.fixtureFile().readBytes())).also(file::writeBytes)
    val source = MediaSource.Uri(UnsizedSourceProvider.uriOf(kind, file.name))
    measure(
      "$kind ${case.name}",
      source,
      case.refusalAt(source, bytes).takeIf { kind == UnsizedSourceProvider.UNSIZED },
    )
  }

  private suspend fun measure(case: UnreadableCase) {
    val file = File(probe.context.cacheDir, "unreadable-${case.name}.mp4").also { it.delete() }
    val bytes = case.bytesFrom(probe.fixtureFile().readBytes())?.also(file::writeBytes)
    val source = MediaSource.of(file.path)
    measure(case.name, source, case.refusalAt(source, bytes))
  }

  private suspend fun measure(
    name: String,
    source: MediaSource,
    refusal: String? = null,
  ) = withContext(Dispatchers.Default) {
    val composition = Composition(listOf(Track(listOf(Clip(source)))))
    val started = SystemClock.elapsedRealtime()
    val plan =
      when (val verdict = probe.filmstrip.plan(composition, ExportSpec())) {
        is Verdict.Incapable -> {
          val elapsed = SystemClock.elapsedRealtime() - started
          Log.i(TAG, "$name refused at plan after $elapsed ms: ${verdict.reasons.map { it.message }}")
          refusal?.let { verdict.reasons.map { reason -> reason.message } shouldContain it }
          return@withContext
        }
        is Verdict.Capable -> {
          verdict.plan
        }
        is Verdict.Degraded -> {
          verdict.plan
        }
      }
    withClue("$name planned") { refusal.shouldBeNull() }
    val planned = SystemClock.elapsedRealtime()
    val last =
      probe.filmstrip
        .export(plan, MediaSink.temporary())
        .toList()
        .last()
    val ended = SystemClock.elapsedRealtime()
    val outcome = if (last is ExportStatus.Failure) "${last.error::class.simpleName} ${last.error.message}" else "$last"
    Log.i(TAG, "$name planned in ${planned - started} ms, export ended after ${ended - planned} ms: $outcome")
    withClue("$name exported") { last.shouldNotBeInstanceOf<ExportStatus.Success>() }
  }

  private companion object {
    const val TAG = "FilmstripUnreadable"
    const val DEAD_URI = "content://dev.jordond.filmstrip.nothing/clip.mp4"
    val TIMEOUT = 2.minutes
  }
}
