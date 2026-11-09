package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.internal.process.BytesRoot
import dev.jordond.filmstrip.ffmpeg.internal.process.readablePath
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.TIMEOUT
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A buffer holding a clip behaves as the file it came from, at every place this backend reads a source.
 */
class FfmpegBytesSourceMediaTest {
  private val fixture = FfmpegExportFixture()
  private val outputs = mutableListOf<File>()

  @AfterTest
  fun removeOutputs() {
    outputs.forEach(File::delete)
  }

  private fun output(): File = File.createTempFile("filmstrip-bytes", ".mp4").also { it.delete() }.also(outputs::add)

  private fun bytesOf(file: File): MediaSource.Bytes = MediaSource.Bytes(file.readBytes())

  @Test
  fun `a buffer probes the same as the file it came from`() =
    runTest(timeout = TIMEOUT) {
      val fromFile = fixture.filmstrip.probe(MediaSource.of(fixture.landscape.path))
      val fromBytes = fixture.filmstrip.probe(bytesOf(fixture.landscape))

      fromBytes.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe
        fromFile.shouldBeInstanceOf<ProbeResult.Success>().info
    }

  // The name a buffer with no ftyp lands under is for AVFoundation. ffmpeg sniffs, so a Matroska buffer still reads.
  @Test
  fun `a buffer in a container the neutral name does not claim still probes`() =
    runTest(timeout = TIMEOUT) {
      val matroska = output().resolveSibling("filmstrip-bytes-${System.nanoTime()}.mkv").also(outputs::add)
      val muxed =
        ProcessBuilder(hostFfmpeg, "-v", "error", "-i", fixture.landscape.path, "-c", "copy", matroska.path)
          .redirectErrorStream(true)
          .start()
      muxed.inputStream.readAllBytes()
      muxed.waitFor() shouldBe 0

      val fromBytes = fixture.filmstrip.probe(bytesOf(matroska))

      fromBytes
        .shouldBeInstanceOf<ProbeResult.Success>()
        .info.video
        ?.displaySize shouldBe
        fixture.filmstrip
          .probe(MediaSource.of(fixture.landscape.path))
          .shouldBeInstanceOf<ProbeResult.Success>()
          .info.video
          ?.displaySize
    }

  @Test
  fun `a copied export of a buffer holds the bytes the file's does`() =
    runTest(timeout = TIMEOUT) {
      val fromFile = output()
      val fromBytes = output()

      fixture.exported(
        fixture.plannedRun(compositionOf { clip(MediaSource.of(fixture.landscape.path)) }, ExportSpec()),
        fromFile,
      )
      fixture.exported(fixture.plannedRun(compositionOf { clip(bytesOf(fixture.landscape)) }, ExportSpec()), fromBytes)

      fromBytes.readBytes().contentEquals(fromFile.readBytes()) shouldBe true
    }

  @Test
  fun `a transcoded export of a buffer runs the graph the file's runs and lands the same`() =
    runTest(timeout = TIMEOUT) {
      val file = MediaSource.of(fixture.landscape.path)
      val bytes = bytesOf(fixture.landscape)
      val spec = ExportSpec(heightPx = TRANSCODED_HEIGHT)
      val fromFile = compositionOf { clip(file) }
      val fromBytes = compositionOf { clip(bytes) }

      val fileGraph = fixture.plannedLowering(fromFile, spec, listOf(file)).graph.shouldNotBeNull()
      val bytesGraph = fixture.plannedLowering(fromBytes, spec, listOf(bytes)).graph.shouldNotBeNull()
      bytesGraph.filterGraph shouldBe fileGraph.filterGraph

      val fileInfo = fixture.exported(fixture.plannedRun(fromFile, spec), output())
      val bytesInfo = fixture.exported(fixture.plannedRun(fromBytes, spec), output())
      bytesInfo.duration shouldBe fileInfo.duration
      bytesInfo.video?.displaySize shouldBe fileInfo.video?.displaySize
    }

  @Test
  fun `a trim on a buffer reads the sync sample the file reads`() =
    runTest(timeout = TIMEOUT) {
      val file = MediaSource.of(fixture.long.path)
      val bytes = bytesOf(fixture.long)
      val opensAt: suspend (MediaSource) -> Duration = { source ->
        fixture
          .plannedTracks(
            compositionOf {
              clip(source) {
                trim(TimeRange.of(TRIM_START, TRIM_END))
                snapWithin(SNAP_WITHIN)
              }
            },
            ExportSpec(),
            listOf(source),
          ).single()
          .clips
          .single()
          .start
      }

      val snapped = opensAt(bytes)

      snapped shouldBe opensAt(file)
      snapped shouldNotBe TRIM_START
    }

  @Test
  fun `a snapped trim on a buffer stays on the copy path`() =
    runTest(timeout = TIMEOUT) {
      val composition =
        compositionOf {
          clip(bytesOf(fixture.long)) {
            trim(TimeRange.of(TRIM_START, TRIM_END))
            snapWithin(SNAP_WITHIN)
          }
        }

      fixture.filmstrip
        .plan(composition, ExportSpec())
        .shouldBeInstanceOf<Verdict.Capable>()
        .plan.path shouldBe ExportPath.Copy
    }

  @Test
  fun `a preview seek on a buffer does not write it again and lands where the file does`() =
    runTest(timeout = TIMEOUT) {
      val engine =
        FfmpegExportEngine(
          ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
          FfmpegRuntime.of(FfmpegConfig()),
        )
      val bytes = bytesOf(fixture.landscape)
      val fromBytes = compositionOf { clip(bytes) }
      val fromFile = compositionOf { clip(MediaSource.of(fixture.landscape.path)) }
      val written = File(readablePath(bytes).shouldNotBeNull()).toPath()

      suspend fun frameAt(
        composition: Composition,
        at: Duration,
      ): ByteArray {
        val stream =
          engine
            .openPreview(composition, ExportSpec(), at = at)
            .shouldBeInstanceOf<PreviewStreamResult.Opened>()
            .stream
            .shouldBeInstanceOf<FfmpegPreviewStream>()
        return try {
          stream.next().shouldNotBeNull()
        } finally {
          stream.close()
        }
      }

      val before = Files.readAttributes(written, BasicFileAttributes::class.java)
      frameAt(fromBytes, Duration.ZERO)
      val seeked = frameAt(fromBytes, SEEK)
      val after = Files.readAttributes(written, BasicFileAttributes::class.java)

      after.fileKey() shouldBe before.fileKey()
      after.lastModifiedTime() shouldBe before.lastModifiedTime()
      seeked.contentEquals(frameAt(fromFile, SEEK)) shouldBe true
    }

  // A registered prober answers for the buffer, so the read of the copy opening is the first thing to reach the root.
  @Test
  fun `a buffer the snap or the lowering cannot write is refused as unreadable`() =
    runTest(timeout = TIMEOUT) {
      val info = fixture.filmstrip.probe(MediaSource.of(fixture.long.path)).shouldBeInstanceOf<ProbeResult.Success>()
      val blocked = output().also { it.writeBytes(ByteArray(1)) }
      val engine =
        FfmpegExportEngine(
          ComponentRegistry
            .Builder()
            .addEffectResolver(BuiltInEffectResolver())
            .addMediaProberFactory { MediaProber { info } }
            .build(),
          FfmpegRuntime.unshared(FfmpegConfig(), bytesRoot = BytesRoot(blocked.path)),
        )
      val composition =
        compositionOf {
          clip(MediaSource.Bytes(ByteArray(16))) {
            trim(TimeRange.of(TRIM_START, TRIM_END))
            snapWithin(SNAP_WITHIN)
          }
        }

      val refused = engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Refused>()

      refused.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldContain "temporary file"
    }

  private companion object {
    const val TRANSCODED_HEIGHT = 180
    val SEEK = 1_500.milliseconds
    val TRIM_START = 4_500.milliseconds
    val TRIM_END = 7_250.milliseconds
    val SNAP_WITHIN = 2.seconds
  }
}
