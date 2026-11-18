package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.internal.writer.videoReaderSettings
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.SpriteSheet
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContainInOrder
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.UShortVar
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreMedia.CMSampleBufferGetPresentationTimeStamp
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBaseAddressOfPlane
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetBytesPerRowOfPlane
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.Foundation.NSFileManager
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sprite sheet overlays exported for real, with the cell on every written frame read back and compared with the frame
 * the shared timeline puts on that slot.
 *
 * Each cell paints its index as eight black or white squares, so a frame is read by what its overlay shows and never by
 * a color the encoder moves. The expected cell comes from the timeline the sheet counts its frames on, and the cases
 * that read a run of frames cover the middle of a cycle as well as its ends. The HDR case exports a kept grade, where
 * every cell is drawn from the floats made for it.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleSpriteSheetHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `a sheet of one duration shows the timeline's cell on every written frame`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM)

      assertWritten(sheet, "uniform")
    }

  // The durations are 100, 30, 250, 40 and 70 ms over and over. A cycle runs a little over a second, so the written
  // frames reach the middle of the second one.
  @Test
  fun `a sheet of uneven durations shows the timeline's cell on every written frame`() =
    runTest(timeout = TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)

      val read = assertWritten(sheet, "uneven")

      withClue("the written frames read every cell of the sheet") {
        read.distinct().size shouldBe SpriteSheets.UNEVEN.size
      }
    }

  @Test
  fun `a kept HDR grade shows the timeline's cell in the middle of the run`() =
    runTest(timeout = HDR_TIMEOUT) {
      val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)

      HdrTransfer.entries.forEach { transfer ->
        val read = assertWritten(sheet, "hdr-$transfer", transfer)

        withClue("the $transfer frames pass through the long cells in the middle of the first cycle") {
          read.shouldContainInOrder(listOf(2, 3, 4, 5))
        }
      }
    }

  /**
   * Exports a clip with [sheet] drawn over it and checks every written frame, and answers the cell each one shows.
   */
  private suspend fun assertWritten(
    sheet: SpriteSheet,
    name: String,
    transfer: HdrTransfer? = null,
  ): List<Int> {
    val overlay =
      ImageOverlay(ImageSource.ofBytes(SpriteSheets.png()), Corner.TopStart, margin = 0f, scale = SCALE, sheet = sheet)
    val source = if (transfer == null) fixture(LANDSCAPE) else hdrFixture(transfer)
    val clip = if (transfer == null) SDR_CLIP else HDR_CLIP
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) { trim(Duration.ZERO, clip) }
        effects { add(overlay) }
      }
    val spec =
      if (transfer == null) {
        ExportSpec(heightPx = 180, frameRate = RATE, bitrate = COLOR_READ_BITRATE)
      } else {
        ExportSpec(heightPx = 720, frameRate = RATE, hdr = HdrMode.KeepHdr, bitrate = COLOR_READ_BITRATE)
      }

    val path = exported(composition, spec)
    try {
      val timeline = timelineOf(sheet)
      val frames = writtenFrames(path, hdr = transfer != null)
      withClue("the $name export wrote ${frames.size} frames") {
        frames.size shouldBe (clip.inWholeMilliseconds * RATE / MILLIS).toInt()
      }
      return frames.mapIndexed { slot, frame ->
        val rect = overlay.placedOn(frame.size, SpriteSheets.CELL).rectOn(frame.size)
        val read = frame.cellIn(rect, if (transfer == null) SDR_WHITE else HDR_WHITE)
        val shown = withClue("the $name frame of slot $slot holds no cell") { read.shouldNotBeNull() }
        withClue("the $name frame of slot $slot") {
          shown shouldBe timeline.frameOnSlot(Duration.ZERO, slot.toLong(), RATE)
        }
        shown
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private suspend fun exported(
    composition: Composition,
    spec: ExportSpec,
  ): String {
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("the export was refused: ${verdict.reasons.map { it.message }}")
      }
    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    return finished
      .shouldBeInstanceOf<ExportStatus.Success>()
      .output
      .shouldBeInstanceOf<MediaSink.Path>()
      .path
  }

  // The timeline [sheet] counts its frames on, built the way the sheet builds its own.
  private fun timelineOf(sheet: SpriteSheet): FrameTimeline {
    val first = sheet.frameDurations.first()
    return if (sheet.frameDurations.all { it == first }) {
      FrameTimeline.uniform(sheet.frameCount, first, sheet.plays)
    } else {
      FrameTimeline(sheet.frameDurations, sheet.plays)
    }
  }

  /**
   * One written frame, its luma read as a fraction of the codes the frame holds.
   */
  private class WrittenFrame(
    val size: Size,
    private val luma: FloatArray,
  ) {
    /**
     * The cell shown in [rect], or null when a texel of it is neither dark nor light.
     */
    fun cellIn(
      rect: NormalizedRect,
      white: Float,
    ): Int? {
      val cell = SpriteSheets.CELL
      val stepX = rect.width * size.width / cell.width
      val stepY = rect.height * size.height / cell.height
      val texels =
        IntArray(cell.width * cell.height) { index ->
          val x = (rect.left * size.width + (index % cell.width + HALF) * stepX).toInt().coerceIn(0, size.width - 1)
          val y = (rect.top * size.height + (index / cell.width + HALF) * stepY).toInt().coerceIn(0, size.height - 1)
          if (luma[y * size.width + x] > white) LIGHT else DARK
        }
      return runCatching { SpriteSheets.indexOf(texels) }.getOrNull()
    }
  }

  /**
   * Every video frame of the file at [path] in presentation order, read as the codes the file holds.
   */
  private fun writtenFrames(
    path: String,
    hdr: Boolean,
  ): List<WrittenFrame> {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).firstOrNull() as? AVAssetTrack ?: error("no video track")
    val output = AVAssetReaderTrackOutput(track = track, outputSettings = videoReaderSettings(encodesHdr = hdr))
    val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
    reader.addOutput(output)
    if (!reader.startReading()) error("the reader refused to start: ${reader.error?.localizedDescription}")

    val frames = mutableListOf<Pair<Duration, WrittenFrame>>()
    try {
      while (true) {
        val sample = output.copyNextSampleBuffer() ?: break
        try {
          val buffer = CMSampleBufferGetImageBuffer(sample) ?: continue
          frames += CMSampleBufferGetPresentationTimeStamp(sample).toDuration() to lumaOf(buffer, hdr)
        } finally {
          CFRelease(sample)
        }
      }
    } finally {
      reader.cancelReading()
    }
    return frames.sortedBy { it.first }.map { it.second }
  }

  private fun lumaOf(
    buffer: CVPixelBufferRef,
    hdr: Boolean,
  ): WrittenFrame {
    val width = CVPixelBufferGetWidth(buffer).toInt()
    val height = CVPixelBufferGetHeight(buffer).toInt()
    val luma = FloatArray(width * height)
    CVPixelBufferLockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    try {
      if (hdr) {
        val plane = CVPixelBufferGetBaseAddressOfPlane(buffer, 0u)!!.reinterpret<UShortVar>()
        val stride = CVPixelBufferGetBytesPerRowOfPlane(buffer, 0u).toInt() / Short.SIZE_BYTES
        for (y in 0 until height) {
          for (x in 0 until width) luma[y * width + x] = (plane[y * stride + x].toInt() shr CODE_SHIFT) / TEN_BIT_TOP
        }
      } else {
        val bytes = CVPixelBufferGetBaseAddress(buffer)!!.reinterpret<UByteVar>()
        val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
        for (y in 0 until height) {
          for (x in 0 until width) {
            val at = y * stride + x * BGRA
            luma[y * width + x] = (bytes[at].toInt() + bytes[at + 1].toInt() + bytes[at + 2].toInt()) / (3f * BYTE_TOP)
          }
        }
      }
    } finally {
      CVPixelBufferUnlockBaseAddress(buffer, kCVPixelBufferLock_ReadOnly)
    }
    return WrittenFrame(Size(width, height), luma)
  }

  private companion object {
    const val LANDSCAPE = "apple_export_a.mp4"
    val TIMEOUT = 3.seconds * 60
    val HDR_TIMEOUT = 5.seconds * 60
    val UNIFORM = 40.milliseconds
    val SDR_CLIP = 2.seconds
    val HDR_CLIP = 1.5.seconds
    const val RATE = 30
    const val MILLIS = 1_000
    const val SCALE = 0.35f

    // Where black ends and white begins, as a fraction of the codes. An SDR white square is 203 nits in an HDR frame,
    // which both transfers put well below the top of their range.
    const val SDR_WHITE = 0.5f
    const val HDR_WHITE = 0.3f
    const val CODE_SHIFT = 6
    const val TEN_BIT_TOP = 1023f
    const val BYTE_TOP = 255f
    const val BGRA = 4
    const val HALF = 0.5f
    const val LIGHT = 0xFFFFFFFF.toInt()
    const val DARK = 0xFF000000.toInt()
  }
}
