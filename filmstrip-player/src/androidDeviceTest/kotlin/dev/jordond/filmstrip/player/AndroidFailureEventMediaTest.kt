package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media3.internal.failure.MEDIA3_BACKEND
import dev.jordond.filmstrip.media3.media3Backend
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File
import java.util.concurrent.CopyOnWriteArrayList
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A real media3 failure, played and exported through `Filmstrip`, reaches a diagnostic listener as exactly one failure
 * event that names media3 and carries media3's own code.
 */
class AndroidFailureEventMediaTest {
  private val events = CopyOnWriteArrayList<DiagnosticEvent>()

  private val filmstrip =
    Filmstrip(contractContext()) {
      media3Backend()
      playerBackend()
      addDiagnosticListener { event -> events += event }
    }

  @Test
  fun aClipGarbledMidStreamFailsPlaybackOnce() =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) {
        val player = filmstrip.preview(compositionOf(garbledClip()))
        try {
          player.play()
          val failed =
            withTimeoutOrNull(BUDGET) { player.state.first { it.status is PlaybackStatus.Error } }
              ?: fail("the player never failed on a clip whose later samples are garbage")
          failed.status.shouldBeInstanceOf<PlaybackStatus.Error>()
          delay(SETTLE)
        } finally {
          player.close()
        }

        val failure = failures().shouldHaveSize(1).single()
        withClue(failure.detail) { failure.source shouldBe MEDIA3_BACKEND }
        failure.detail[DiagnosticEvent.Detail.STAGE] shouldBe "playback"
        failure.detail shouldContainKey DiagnosticEvent.Detail.ERROR_CODE
        failure.detail shouldContainKey DiagnosticEvent.Detail.ERROR_NAME
      }
    }

  @Test
  fun aSourceDeletedAfterPlanningFailsTheExportOnce() =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) {
        val source = copyOfFixture("deleted")
        val plan =
          when (val verdict = filmstrip.plan(compositionOf(MediaSource.Path(source.path)), ExportSpec())) {
            is Verdict.Capable -> verdict.plan
            is Verdict.Degraded -> verdict.plan
            is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.map { it.message }}")
          }
        withClue("the copied source could not be deleted") { source.delete() shouldBe true }

        filmstrip
          .export(plan, MediaSink.temporary())
          .toList()
          .last()
          .shouldBeInstanceOf<ExportStatus.Failure>()

        val failure = failures().shouldHaveSize(1).single()
        failure.source shouldBe MEDIA3_BACKEND
        failure.detail[DiagnosticEvent.Detail.STAGE] shouldBe "export"
        failure.detail[DiagnosticEvent.Detail.ERROR_NAME] shouldBe "ERROR_CODE_IO_FILE_NOT_FOUND"
      }
    }

  private fun failures(): List<DiagnosticEvent> = events.filter { it.name == DiagnosticEvent.FAILURE }

  private fun compositionOf(source: MediaSource): Composition =
    Composition(tracks = listOf(Track(listOf(Clip(source, TimeRange.of(Duration.ZERO, CLIP_LENGTH))))))

  private fun copyOfFixture(name: String): File {
    val fixture = androidFixtureClip().shouldBeInstanceOf<MediaSource.Path>()
    return File(fixture.path).copyTo(File(contractContext().cacheDir, "failure-$name.mp4"), overwrite = true)
  }

  // A clip cut short is refused by the probe before media3 opens it, and so is a garbled first sample. Filling the
  // second half of the mdat with 0xFF leaves the container and the opening frames whole, and media3's extractor reads
  // a NAL length of -1 once playback gets there.
  private fun garbledClip(): MediaSource {
    val copy = copyOfFixture("garbled")
    val bytes = copy.readBytes()
    val mdat = bytes.boxOffset("mdat")
    val middle = mdat + (bytes.size - mdat) / 2
    bytes.fill(GARBAGE, middle, bytes.size)
    copy.writeBytes(bytes)
    return MediaSource.Path(copy.path)
  }

  private fun ByteArray.boxOffset(type: String): Int {
    var offset = 0
    while (offset + BOX_HEADER <= size) {
      val boxSize =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
          ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)
      if (String(this, offset + 4, 4, Charsets.US_ASCII) == type) return offset
      if (boxSize < BOX_HEADER) break
      offset += boxSize
    }
    fail("the fixture has no top level $type box")
  }

  private companion object {
    const val BOX_HEADER = 8
    const val GARBAGE: Byte = -1
    val TIMEOUT = 90.seconds
    val BUDGET = 45.seconds

    // Long enough for a repeated error from the same failure to have reached the listener too.
    val SETTLE = 2.seconds
  }
}
