package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.avfoundation.internal.writer.videoReaderSettings
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
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
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.CounterCells
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
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
import kotlin.time.Duration.Companion.seconds

/**
 * Animated picture overlays exported for real, with the counter on every written frame read back and compared with the
 * frame the file's own timeline puts on that slot.
 *
 * Each frame of the file writes its index as eight black or white squares, so a written frame is read by what its
 * overlay shows and never by a color the encoder moves. The clip is long enough for the file to play more than twice,
 * so the reads cover the middle of the second play as well as the first.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleAnimatedPictureHardwareTest {
  private val filmstrip = Filmstrip { avFoundationBackend() }
  private val ops = AnimatedPictures.counterOps(FRAMES)
  private val canvas = AnimatedPictures.COUNTER_CANVAS

  @Test
  fun `a GIF shows the timeline's frame on every written frame`() =
    runTest(timeout = TIMEOUT) { assertWritten(AnimatedGifs.bytes(canvas, ops, loops = 0), "gif") }

  @Test
  fun `an APNG shows the timeline's frame on every written frame`() =
    runTest(timeout = TIMEOUT) { assertWritten(AnimatedApngs.bytes(canvas, ops), "apng") }

  @Test
  fun `a WebP shows the timeline's frame on every written frame`() =
    runTest(timeout = TIMEOUT) { assertWritten(AnimatedWebps.bytes(canvas, ops), "webp") }

  // The overlay's clock is the composition's, so a clip played at twice the speed still shows the frame its timeline
  // names on each output slot.
  @Test
  fun `a clip at twice the speed shows the timeline's frame on every written frame`() =
    runTest(timeout = TIMEOUT) { assertWritten(AnimatedApngs.bytes(canvas, ops), "fast", speed = 2.0) }

  @Test
  fun `a kept HDR grade shows the timeline's frame in the middle of the run`() =
    runTest(timeout = HDR_TIMEOUT) {
      HdrTransfer.entries.forEach { transfer ->
        val read = assertWritten(AnimatedApngs.bytes(canvas, ops), "hdr-$transfer", transfer = transfer)

        withClue("the $transfer frames play on into the second play") { read.shouldContain(0) }
        withClue(
          "the $transfer frames read more than one frame of the file",
        ) { (read.distinct().size > 1) shouldBe true }
      }
    }

  @Test
  fun `an animated AVIF is refused by name`() =
    runTest(timeout = TIMEOUT) {
      val avis = byteArrayOf(0, 0, 0, 16) + "ftypavis".encodeToByteArray() + ByteArray(4)

      val verdict = filmstrip.plan(compositionWith(avis, SDR_CLIP, 1.0, null), spec(null))

      verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.map { it.message } shouldContain ANIMATED_AVIF_REFUSED
    }

  /**
   * Exports a clip with the picture in [bytes] drawn over it, checks every written frame and answers the frame index
   * each one shows.
   */
  private suspend fun assertWritten(
    bytes: ByteArray,
    name: String,
    transfer: HdrTransfer? = null,
    speed: Double = 1.0,
  ): List<Int> {
    val clip = if (transfer == null) SDR_CLIP else HDR_CLIP
    val composition = compositionWith(bytes, clip, speed, transfer)
    val path = exported(composition, spec(transfer))
    try {
      val timeline = animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture.timeline
      val frames = writtenFrames(path, hdr = transfer != null)
      val expected = (clip.inWholeMilliseconds / speed * RATE / MILLIS).toInt()
      withClue("the $name export wrote ${frames.size} frames") { frames.size shouldBe expected }
      return frames.mapIndexed { slot, frame ->
        val rect = overlay(bytes).placedOn(frame.size, canvas).rectOn(frame.size)
        val read = frame.counterIn(rect, if (transfer == null) SDR_WHITE else HDR_WHITE)
        val shown = withClue("the $name frame of slot $slot holds no counter") { read.shouldNotBeNull() }
        withClue("the $name frame of slot $slot") {
          shown shouldBe timeline.frameOnSlot(Duration.ZERO, slot.toLong(), RATE)
        }
        shown
      }
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun overlay(bytes: ByteArray) =
    ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart, margin = 0f, scale = SCALE)

  private fun compositionWith(
    bytes: ByteArray,
    clip: Duration,
    speed: Double,
    transfer: HdrTransfer?,
  ): Composition {
    val source = if (transfer == null) fixture(LANDSCAPE) else hdrFixture(transfer)
    return compositionOf {
      clip(MediaSource.of(source)) {
        trim(Duration.ZERO, clip)
        speed(speed)
      }
      effects { add(overlay(bytes)) }
    }
  }

  private fun spec(transfer: HdrTransfer?): ExportSpec =
    if (transfer == null) {
      ExportSpec(heightPx = HEIGHT, frameRate = RATE, bitrate = COLOR_READ_BITRATE)
    } else {
      ExportSpec(heightPx = HDR_HEIGHT, frameRate = RATE, hdr = HdrMode.KeepHdr, bitrate = COLOR_READ_BITRATE)
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

  /**
   * One written frame, its luma read as a fraction of the codes the frame holds.
   */
  private class WrittenFrame(
    val size: Size,
    private val luma: FloatArray,
  ) {
    /**
     * The counter written in the top left of the canvas drawn in [rect], or null when a square is neither dark nor
     * light.
     */
    fun counterIn(
      rect: NormalizedRect,
      white: Float,
    ): Int? {
      val cell = AnimatedPictures.COUNTER_CELL
      val shown = AnimatedPictures.COUNTER_CANVAS
      val stepX = rect.width * size.width / shown.width
      val stepY = rect.height * size.height / shown.height
      val texels =
        IntArray(cell.width * cell.height) { index ->
          val x = (rect.left * size.width + (index % cell.width + HALF) * stepX).toInt().coerceIn(0, size.width - 1)
          val y = (rect.top * size.height + (index / cell.width + HALF) * stepY).toInt().coerceIn(0, size.height - 1)
          if (luma[y * size.width + x] > white) LIGHT else DARK
        }
      return runCatching { CounterCells.indexOf(texels) }.getOrNull()
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

    // A play is 20 frames of 40 ms, so a two second clip plays two and a half times.
    const val FRAMES = 20
    val SDR_CLIP = 2.seconds
    val HDR_CLIP = 1.5.seconds
    const val RATE = 30
    const val MILLIS = 1_000
    const val SCALE = 0.6f
    const val HEIGHT = 360
    const val HDR_HEIGHT = 720

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
