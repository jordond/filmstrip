package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.AudioStretchFailure
import dev.jordond.filmstrip.avfoundation.internal.StretchedAudio
import dev.jordond.filmstrip.avfoundation.internal.export.AvDriver
import dev.jordond.filmstrip.avfoundation.internal.renderClipAudio
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.audioFramesOf
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.useContents
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.cancelAndJoin
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.naturalTimeScale
import platform.AVFoundation.timeRange
import platform.Foundation.NSDate
import platform.Foundation.NSFileManager
import platform.Foundation.NSFileModificationDate
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSUUID
import platform.Foundation.dateWithTimeIntervalSinceNow
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The files an export renders a clip's audio to at a speed: how long they run, and what happens to them when a render
 * is cancelled or fails or a process dies before cleaning up.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleStretchedAudioMediaTest {
  @Test
  fun `a render sweeps stretched audio a day old and leaves a newer directory`() =
    runTest(timeout = TIMEOUT) {
      val root = temporaryRoot()
      val manager = NSFileManager.defaultManager
      val abandoned = "$root/${StretchedAudio.DIRECTORY_PREFIX}abandoned"
      val running = "$root/${StretchedAudio.DIRECTORY_PREFIX}running"
      val other = "$root/not-stretched-audio"
      listOf(abandoned, running, other).forEach { manager.createDirectoryAtPath(it, true, null, null) }
      val old = NSDate.dateWithTimeIntervalSinceNow(-TWO_DAYS_SECONDS)
      listOf(abandoned, other).forEach { path ->
        manager.setAttributes(mapOf<Any?, Any?>(NSFileModificationDate to old), ofItemAtPath = path, error = null)
      }
      val resolved = resolved(compositionOf { clip(MediaSource.of(fixture(SHORT))) { speed(1.5) } })

      val stretched = StretchedAudio(root)
      withContext(Dispatchers.Default) { stretched.renderAll(resolved, root) }

      manager.fileExistsAtPath(abandoned) shouldBe false
      manager.fileExistsAtPath(running) shouldBe true
      manager.fileExistsAtPath(other) shouldBe true
      stretched.discard()
      remove(root)
    }

  @Test
  fun `a stretched file holds exactly the frames of the clip's output length`() =
    runTest(timeout = TIMEOUT) {
      val root = temporaryRoot()
      listOf(CLIP_SPEED_RANGE.start, 0.7, 1.5, CLIP_SPEED_RANGE.endInclusive).forEach { speed ->
        val resolved =
          resolved(
            compositionOf {
              clip(MediaSource.of(fixture(SHORT))) {
                trim(TimeRange.of(Duration.ZERO, 2.seconds))
                speed(speed)
              }
            },
          )
        val stretched = StretchedAudio(root)
        withContext(Dispatchers.Default) { stretched.renderAll(resolved, root) }

        val clip =
          resolved.tracks
            .single()
            .clips
            .single()
        val track = stretched.trackOf(clip).shouldNotBeNull()
        val frames = track.timeRange.useContents { duration.value * track.naturalTimeScale / duration.timescale }
        val sampleRate =
          resolved.output.audioFormat
            .shouldNotBeNull()
            .sampleRate
        withClue("speed $speed") { frames shouldBe audioFramesOf(outputLengthOf(2.seconds, speed), sampleRate) }
        stretched.discard()
      }
      remove(root)
    }

  @Test
  fun `cancelling a render reports the start and leaves no file`() =
    runTest(timeout = TIMEOUT) {
      val root = temporaryRoot()
      val composition =
        compositionOf {
          clip(MediaSource.of(fixture(LONG))) { speed(CLIP_SPEED_RANGE.start) }
        }
      val resolved = resolved(composition)
      val sink = MediaSink.of(NSTemporaryDirectory() + "filmstrip-apple-speed-cancelled.mp4")

      withContext(Dispatchers.Default) {
        val seen = mutableListOf<ExportStatus>()
        val job =
          launch {
            AvDriver(UNUSED_PROBER, bytesRoot = root).export(resolved, sink).collect { seen += it }
          }
        // A file on disk means the render pass has begun, and this fixture takes it seconds to finish.
        while (!hasStretchedFile(root)) delay(1.milliseconds)
        job.cancelAndJoin()
        seen shouldBe listOf(ExportStatus.Started)
      }

      directoriesUnder(root).shouldBeEmpty()
      NSFileManager.defaultManager.fileExistsAtPath(sink.pathOrThrow()) shouldBe false
      remove(root)
    }

  @Test
  fun `a render that fails names its error and leaves no file`() =
    runTest(timeout = TIMEOUT) {
      val root = temporaryRoot()
      val refused = FilmstripError.Underlying(RENDER_STATUS, "refused")
      val resolved =
        resolved(
          compositionOf {
            clip(MediaSource.of(fixture(SHORT))) { speed(0.7) }
            clip(MediaSource.of(fixture(SHORT))) { speed(1.5) }
          },
        )
      var renders = 0
      val driver =
        AvDriver(UNUSED_PROBER, bytesRoot = root) { render ->
          renders += 1
          if (renders > 1) throw AudioStretchFailure(refused)
          renderClipAudio(render)
        }

      val finished =
        withContext(Dispatchers.Default) {
          driver.export(resolved, MediaSink.of(NSTemporaryDirectory() + "filmstrip-apple-speed-failed.mp4")).toList()
        }

      renders shouldBe 2
      finished.first() shouldBe ExportStatus.Started
      finished.last().shouldBeInstanceOf<ExportStatus.Failure>().error shouldBe refused
      directoriesUnder(root).shouldBeEmpty()
      remove(root)
    }

  @Test
  fun `an export that fails after its render leaves no file`() =
    runTest(timeout = TIMEOUT) {
      val root = temporaryRoot()
      val resolved = resolved(compositionOf { clip(MediaSource.of(fixture(SHORT))) { speed(1.5) } })
      val unwritable = MediaSink.of("relative/filmstrip-apple-speed.mp4")

      val finished =
        withContext(Dispatchers.Default) {
          AvDriver(UNUSED_PROBER, bytesRoot = root).export(resolved, unwritable).toList()
        }

      finished.last().shouldBeInstanceOf<ExportStatus.Failure>()
      directoriesUnder(root).shouldBeEmpty()
      remove(root)
    }

  private fun temporaryRoot(): String =
    (NSTemporaryDirectory() + "filmstrip-apple-speed-root-" + NSUUID().UUIDString).also {
      NSFileManager.defaultManager.createDirectoryAtPath(it, true, null, null)
    }

  private fun directoriesUnder(root: String): List<*> =
    NSFileManager.defaultManager.contentsOfDirectoryAtPath(root, error = null).orEmpty()

  private fun hasStretchedFile(root: String): Boolean =
    directoriesUnder(root).any { directory ->
      NSFileManager.defaultManager
        .contentsOfDirectoryAtPath("$root/$directory", error = null)
        .orEmpty()
        .isNotEmpty()
    }

  private fun MediaSink.pathOrThrow(): String = (this as MediaSink.Path).path

  private suspend fun resolved(composition: Composition): ResolvedComposition =
    withContext(Dispatchers.Default) {
      val components = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
      when (val result = avFoundationExportEngine(components).resolve(composition, ExportSpec(frameRate = RATE))) {
        is ResolveResult.Refused -> error(result.error.message)
        is ResolveResult.Resolved -> result.composition
      }
    }

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    val TIMEOUT = 3.minutes
    const val SHORT = "apple_export_a.mp4"
    const val LONG = "apple_export_long.mp4"
    const val RENDER_STATUS = -1
    const val RATE = 30
    const val TWO_DAYS_SECONDS = 2.0 * 24 * 60 * 60
    val UNUSED_PROBER = MediaProber { error("No probe is expected here.") }
  }
}
