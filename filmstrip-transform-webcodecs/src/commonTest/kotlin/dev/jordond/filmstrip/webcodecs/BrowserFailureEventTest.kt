@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.diagnostics.CodecRole
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.failure.CodecSite
import dev.jordond.filmstrip.webcodecs.internal.failure.WEBCODECS_BACKEND
import dev.jordond.filmstrip.webcodecs.internal.failure.running
import dev.jordond.filmstrip.webcodecs.internal.failure.webCodecsFailure
import dev.jordond.filmstrip.webcodecs.internal.interop.await
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * What the browser threw reaches the failure event: the `DOMException` name, the JavaScript `cause` chain, and which
 * decoder or encoder was running with the codec string it was opened with.
 */
class BrowserFailureEventTest {
  @Test
  fun aRejectedDomExceptionIsNamed() =
    runTest {
      val thrown = assertFailsWith<Throwable> { rejectedWithDomException("EncodingError", "Decoding error.").await() }

      val failure = webCodecsFailure(thrown)

      failure.backend shouldBe WEBCODECS_BACKEND
      failure.codeName shouldBe "EncodingError"
      failure.causes shouldContainExactly listOf("EncodingError: Decoding error.")
      failure.codecRole.shouldBeNull()
    }

  @Test
  fun aThrownDomExceptionIsNamed() {
    val thrown = assertFailsWith<Throwable> { throwDomException("NotSupportedError", "Unsupported codec.") }

    webCodecsFailure(thrown).codeName shouldBe "NotSupportedError"
  }

  @Test
  fun theCauseChainIsReadOutermostFirst() =
    runTest {
      val thrown =
        assertFailsWith<Throwable> {
          rejectedWithCause("Encoder closed.", "OperationError", "Hardware gone.").await()
        }

      val failure = webCodecsFailure(thrown)

      failure.causes shouldContainExactly listOf("Error: Encoder closed.", "OperationError: Hardware gone.")
      failure.codeName shouldBe "OperationError"
    }

  @Test
  fun aCodecSiteNamesItsRoleAndFormat() =
    runTest {
      val site = CodecSite(CodecRole.Encoder, "avc1.42001f")
      val thrown =
        assertFailsWith<Throwable> {
          site.running { rejectedWithDomException("EncodingError", "Encoding error.").await() }
        }

      val failure = webCodecsFailure(thrown)

      thrown.message shouldBe thrown.cause?.message
      failure.codecRole shouldBe CodecRole.Encoder
      failure.codecFormat shouldBe "avc1.42001f"
      failure.codeName shouldBe "EncodingError"
    }

  @Test
  fun aKotlinFailureCarriesItsOwnChainAndNoName() {
    val failure = webCodecsFailure(IllegalStateException("outer", IllegalArgumentException("inner")))

    failure.codeName.shouldBeNull()
    failure.causes shouldContainExactly listOf("IllegalStateException: outer", "IllegalArgumentException: inner")
  }

  @Test
  fun aDecoderThatFailsMidClipReportsOneFailureEvent() =
    runTest {
      val events = mutableListOf<DiagnosticEvent>()
      val filmstrip =
        Filmstrip {
          webCodecsBackend()
          addDiagnosticListener { events += it }
        }
      val clip = garbleSecondHalfOfMdat(makeClip(frames = 60, keyFrameSeconds = 4.0) { _, _ -> Rgb(200, 40, 40) })
      val composition =
        Composition(tracks = listOf(Track(listOf(Clip(MediaSource.Bytes(clip))))), audio = AudioSpec.Remove)

      val last = filmstrip.export(composition, ExportSpec(), MediaSink.Uri("")).toList().last()

      val failure = events.filter { it.name == DiagnosticEvent.FAILURE }.shouldHaveSize(1).single()
      withClue("$last ${failure.detail}") {
        last.shouldBeInstanceOf<ExportStatus.Failure>()
        failure.source shouldBe WEBCODECS_BACKEND
        failure.detail[Detail.CODEC_ROLE] shouldBe CodecRole.Decoder.key
        failure.detail[Detail.ERROR_NAME] shouldBe "EncodingError"
      }
    }
}

/**
 * Overwrites the second half of the `mdat` payload with noise, so the probe and the first frame read and a decoder
 * fails partway through the clip.
 */
private fun garbleSecondHalfOfMdat(mp4: ByteArray): ByteArray {
  var offset = 0
  while (offset + 8 <= mp4.size) {
    val size =
      (mp4[offset].toInt() and 0xff shl 24) or (mp4[offset + 1].toInt() and 0xff shl 16) or
        (mp4[offset + 2].toInt() and 0xff shl 8) or (mp4[offset + 3].toInt() and 0xff)
    val type = mp4.decodeToString(offset + 4, offset + 8)
    if (type == "mdat") {
      val payload = offset + 8
      val end = offset + size
      var noise = 0x2545F491
      return mp4.copyOf().also { out ->
        for (i in payload + (end - payload) / 2 until end) {
          noise = noise xor (noise shl 13)
          noise = noise xor (noise ushr 17)
          noise = noise xor (noise shl 5)
          out[i] = noise.toByte()
        }
      }
    }
    offset += size
  }
  error("no mdat in the clip")
}

internal expect fun rejectedWithDomException(
  name: String,
  message: String,
): Promise<JsAny?>

internal expect fun rejectedWithCause(
  message: String,
  causeName: String,
  causeMessage: String,
): Promise<JsAny?>

internal expect fun throwDomException(
  name: String,
  message: String,
)
