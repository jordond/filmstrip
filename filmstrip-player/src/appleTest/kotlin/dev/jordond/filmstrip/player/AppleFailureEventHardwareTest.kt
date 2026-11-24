package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.failure.AVFOUNDATION_BACKEND
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
import dev.jordond.filmstrip.player.contract.contractTest
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.maps.shouldContainKey
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.withTimeoutOrNull
import platform.Foundation.NSData
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.posix.memcpy
import kotlin.concurrent.atomics.AtomicReference
import kotlin.concurrent.atomics.ExperimentalAtomicApi
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A real AVFoundation failure, played and exported through `Filmstrip`, reaches a diagnostic listener as exactly one
 * failure event that names AVFoundation and carries its error's code and domain.
 */
@OptIn(ExperimentalAtomicApi::class, ExperimentalForeignApi::class, BetaInteropApi::class)
class AppleFailureEventHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  private val events = AtomicReference(emptyList<DiagnosticEvent>())

  private val filmstrip =
    Filmstrip {
      addDiagnosticListener { event ->
        while (true) {
          val seen = events.load()
          if (events.compareAndSet(seen, seen + event)) break
        }
      }
    }

  @Test
  fun `a source deleted after planning fails the export once`() =
    contractTest {
      val source = copyOfFixture("deleted")
      val plan =
        when (val verdict = filmstrip.plan(compositionOf(source), TRANSCODE)) {
          is Verdict.Capable -> verdict.plan
          is Verdict.Degraded -> verdict.plan
          is Verdict.Incapable -> fail("the export refused the fixture: ${verdict.reasons.map { it.message }}")
        }
      withClue("the copied source could not be deleted") {
        NSFileManager.defaultManager.removeItemAtPath(source, null) shouldBe true
      }

      filmstrip
        .export(plan, MediaSink.temporary())
        .toList()
        .last()
        .shouldBeInstanceOf<ExportStatus.Failure>()

      val failure = failures().shouldHaveSize(1).single()
      withClue(failure.detail) { failure.source shouldBe AVFOUNDATION_BACKEND }
      failure.detail[DiagnosticEvent.Detail.STAGE] shouldBe "export"
      failure.detail shouldContainKey DiagnosticEvent.Detail.ERROR_CODE
      failure.detail shouldContainKey DiagnosticEvent.Detail.ERROR_DOMAIN
    }

  @Test
  fun `a clip garbled mid stream fails playback once`() =
    contractTest {
      val player = filmstrip.preview(compositionOf(garbledClip()))
      try {
        player.play()
        withTimeoutOrNull(BUDGET) { player.state.first { it.status is PlaybackStatus.Error } }
          ?: fail("the player never failed on a clip whose later samples are garbage")
        delay(SETTLE)
      } finally {
        player.close()
      }

      val failure = failures().shouldHaveSize(1).single()
      withClue(failure.detail) { failure.source shouldBe AVFOUNDATION_BACKEND }
      failure.detail[DiagnosticEvent.Detail.STAGE] shouldBe "playback"
      failure.detail shouldContainKey DiagnosticEvent.Detail.ERROR_DOMAIN
    }

  private fun failures(): List<DiagnosticEvent> = events.load().filter { it.name == DiagnosticEvent.FAILURE }

  private fun compositionOf(path: String): Composition =
    Composition(tracks = listOf(Track(listOf(Clip(MediaSource.of(path), TimeRange.of(Duration.ZERO, CLIP_LENGTH))))))

  private fun copyOfFixture(name: String): String {
    val copy = "${NSTemporaryDirectory()}failure-$name.mp4"
    NSFileManager.defaultManager.removeItemAtPath(copy, null)
    withClue("the fixture could not be copied to $copy") {
      NSFileManager.defaultManager.copyItemAtPath(appleFixtureClip(), copy, null) shouldBe true
    }
    return copy
  }

  // A clip cut short is refused by the probe before AVFoundation opens it, and so is a garbled first frame. Filling
  // the second half of the mdat with 0xFF leaves the container and the opening frames whole.
  private fun garbledClip(): String {
    val copy = copyOfFixture("garbled")
    val data = NSData.create(contentsOfFile = copy) ?: fail("Nothing to read at $copy.")
    val bytes = ByteArray(data.length.toInt())
    bytes.usePinned { pinned -> memcpy(pinned.addressOf(0), data.bytes, data.length) }

    val mdat = bytes.boxOffset("mdat")
    bytes.fill(GARBAGE, mdat + (bytes.size - mdat) / 2, bytes.size)

    val written =
      bytes.usePinned { pinned ->
        NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()).writeToFile(copy, atomically = true)
      }
    withClue("the garbled clip could not be written to $copy") { written shouldBe true }
    return copy
  }

  private fun ByteArray.boxOffset(type: String): Int {
    var offset = 0
    while (offset + BOX_HEADER <= size) {
      val boxSize =
        ((this[offset].toInt() and 0xFF) shl 24) or ((this[offset + 1].toInt() and 0xFF) shl 16) or
          ((this[offset + 2].toInt() and 0xFF) shl 8) or (this[offset + 3].toInt() and 0xFF)
      if (decodeToString(offset + 4, offset + BOX_HEADER) == type) return offset
      if (boxSize < BOX_HEADER) break
      offset += boxSize
    }
    fail("the fixture has no top level $type box")
  }

  private companion object {
    const val BOX_HEADER = 8

    // A copy checks its source's samples before AVFoundation reads them, so the export scales to reach the reader.
    val TRANSCODE = ExportSpec(heightPx = 144)
    const val GARBAGE: Byte = -1
    val BUDGET = 30.seconds

    // Long enough for a repeated error from the same failure to have reached the listener too.
    val SETTLE = 2.seconds
  }
}
