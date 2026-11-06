package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.composition.AvCompositorInstruction
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A collector cancelling a running export, over real AVFoundation. It leaves no file at the destination and the process
 * can export again straight after.
 *
 * An SDR plan draws through the filter handler and a tone mapped one through the custom compositor, and each case
 * checks which of the two its plan took.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleExportCancelTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }
  private val cancelled = temporaryPath("cancelled")
  private val following = temporaryPath("following")

  @AfterTest
  fun removeOutputs() {
    NSFileManager.defaultManager.removeItemAtPath(cancelled, error = null)
    NSFileManager.defaultManager.removeItemAtPath(following, error = null)
  }

  // Cancelling here lands during the first frame's setup, inside the reader's first read.
  @Test
  fun `cancelling on Started leaves no file and the next export succeeds`() =
    runTest(timeout = TIMEOUT) {
      drawsThroughCompositor(sdrClip(), SDR_SPEC) shouldBe false
      cancelOn<ExportStatus.Started>(sdrClip(), SDR_SPEC)

      NSFileManager.defaultManager.fileExistsAtPath(cancelled) shouldBe false
      exportsAfterwards()
    }

  @Test
  fun `cancelling on the first Progress leaves no file and the next export succeeds`() =
    runTest(timeout = TIMEOUT) {
      drawsThroughCompositor(sdrClip(), SDR_SPEC) shouldBe false
      cancelOn<ExportStatus.Progress>(sdrClip(), SDR_SPEC)

      NSFileManager.defaultManager.fileExistsAtPath(cancelled) shouldBe false
      exportsAfterwards()
    }

  @Test
  fun `cancelling a tone mapped export on Started leaves no file and the next export succeeds`() =
    runTest(timeout = TIMEOUT) {
      drawsThroughCompositor(gradedClip(), TONE_MAP_SPEC) shouldBe true
      cancelOn<ExportStatus.Started>(gradedClip(), TONE_MAP_SPEC)

      NSFileManager.defaultManager.fileExistsAtPath(cancelled) shouldBe false
      exportsAfterwards()
    }

  @Test
  fun `cancelling a tone mapped export on the first Progress leaves no file and the next export succeeds`() =
    runTest(timeout = TIMEOUT) {
      drawsThroughCompositor(gradedClip(), TONE_MAP_SPEC) shouldBe true
      cancelOn<ExportStatus.Progress>(gradedClip(), TONE_MAP_SPEC)

      NSFileManager.defaultManager.fileExistsAtPath(cancelled) shouldBe false
      exportsAfterwards()
    }

  // The bed outlasts the clip many times over, so the first Progress can land in the tail.
  @Test
  fun `cancelling a tone mapped export with a held tail leaves no file and the next export succeeds`() =
    runTest(timeout = TIMEOUT) {
      val composition = heldTailClip()
      resolved(composition, TONE_MAP_SPEC).heldTail.isEmpty() shouldBe false
      drawsThroughCompositor(composition, TONE_MAP_SPEC) shouldBe true
      cancelOn<ExportStatus.Progress>(composition, TONE_MAP_SPEC)

      NSFileManager.defaultManager.fileExistsAtPath(cancelled) shouldBe false
      exportsAfterwards()
    }

  // An export that ends before the status arrives stops the wait too, and then the check on the last status fails
  // rather than a successful export's file reading as a cancel that left one.
  private suspend inline fun <reified S : ExportStatus> cancelOn(
    composition: EditComposition,
    spec: ExportSpec,
  ) {
    val last =
      withContext(Dispatchers.Default) {
        val reached = CompletableDeferred<ExportStatus>()
        val job =
          launch {
            filmstrip.export(composition, spec, MediaSink.of(cancelled)).collect {
              if (it is S || it is ExportStatus.Success || it is ExportStatus.Failure) reached.complete(it)
            }
          }
        reached.await().also { job.cancelAndJoin() }
      }

    last.shouldBeInstanceOf<S>()
  }

  // Three passes, since one can encode in under 400 ms on a fast Mac and the first Progress comes at 250 ms.
  private fun sdrClip(): EditComposition = passesOf("apple_export_long.mp4", count = 3)

  // Twelve passes, since a pass of this short clip renders in under 150 ms and three can end before the first Progress.
  private fun gradedClip(): EditComposition = passesOf(hdrFixtureName(HdrTransfer.Pq), count = 12)

  private fun heldTailClip(): EditComposition {
    val graded = MediaSource.of(hdrFixture(HdrTransfer.Hlg))
    val bed = MediaSource.of(fixture("tone_bed_880.mp4"))
    return compositionOf {
      clip(graded) { trim(Duration.ZERO, 500.milliseconds) }
      track(TrackContent.Audio) { repeat(BED_PASSES) { clip(bed) } }
    }
  }

  private fun passesOf(
    name: String,
    count: Int,
  ): EditComposition {
    val source = MediaSource.of(fixture(name))
    return compositionOf { repeat(count) { clip(source) } }
  }

  private suspend fun drawsThroughCompositor(
    composition: EditComposition,
    spec: ExportSpec,
  ): Boolean =
    resolved(composition, spec)
      .toAvComposition()
      .videoComposition
      .shouldNotBeNull()
      .instructions
      .any { it is AvCompositorInstruction }

  private suspend fun resolved(
    composition: EditComposition,
    spec: ExportSpec,
  ): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, spec)) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private suspend fun exportsAfterwards() {
    val short = MediaSource.of(fixture("apple_export_a.mp4"))
    val composition = compositionOf { clip(short) { trim(0.seconds, 1.seconds) } }

    val statuses =
      withContext(Dispatchers.Default) {
        filmstrip.export(composition, ExportSpec(heightPx = 180), MediaSink.of(following)).toList()
      }

    statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
  }

  private fun temporaryPath(name: String): String =
    "${NSTemporaryDirectory()}filmstrip-apple-cancel-$name-${NSUUID().UUIDString}.mp4"

  private companion object {
    const val BED_PASSES = 4
    val TIMEOUT = 2.minutes
    val SDR_SPEC = ExportSpec(heightPx = 360)
    val TONE_MAP_SPEC = ExportSpec(heightPx = 720, hdr = HdrMode.ToneMapToSdr)
  }
}
