package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.ffmpeg.internal.picture.Assembly
import dev.jordond.filmstrip.ffmpeg.internal.picture.CODED_IMAGES_REASON
import dev.jordond.filmstrip.ffmpeg.internal.picture.PASS_THROUGH
import dev.jordond.filmstrip.ffmpeg.internal.picture.assembled
import dev.jordond.filmstrip.ffmpeg.internal.picture.codedImageSizeReason
import dev.jordond.filmstrip.ffmpeg.internal.picture.gridAssembly
import dev.jordond.filmstrip.ffmpeg.internal.picture.overlayAssembly
import dev.jordond.filmstrip.ffmpeg.internal.picture.scratchFile
import dev.jordond.filmstrip.ffmpeg.internal.process.ProbeOperation
import dev.jordond.filmstrip.ffmpeg.internal.process.probeExpiredReason
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifGridRead
import dev.jordond.filmstrip.picture.heif.HeifOverlay
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.io.IOException
import java.io.InputStream
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration.Companion.milliseconds

/**
 * How a picture put together in this process bounds the frames its children hand back, run against a stub in place of
 * ffmpeg that writes whatever frame a test gives it.
 */
class PictureAssemblyTest {
  private val directory = createTempDirectory("filmstrip-assembly").toFile()
  private val flat = ColorPictures.NCLX_BT709_LIMITED.thumbnail()
  private val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED.thumbnail()

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  @Test
  fun `a frame larger than core's ceiling leaves beside the canvas is refused before it is read`() {
    val bytes = ColorPictures.NCLX_BT709_LIMITED.gridBytes()
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    // 20000 by 20000 4:4:4 samples is 1.2 GB, past the ceiling and still inside an Int. The child goes on writing until
    // it is stopped, so a refusal that leaves it running reads as the deadline instead.
    val stub = stub("printf 'YUV4MPEG2 W20000 H20000 C444\\nFRAME\\n'; exec yes")
    var collected = false
    val collect = { stream: InputStream ->
      collected = true
      stream.readAllBytes()
    }
    val assembly = gridAssembly(grid, PASS_THROUGH)
    val read = assembled(stub, assembly, bytes, deadline(), TIMEOUT_MILLIS, directory, emptyList(), collect) { it }
    read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
      codedImageSizeReason(grid.tiles.first(), Size(20000, 20000))
    collected shouldBe false
  }

  @Test
  fun `a pam frame larger than core's ceiling leaves beside the canvas is refused and its child stopped`() {
    val (bytes, overlay) = overlayOf(flat, flat)
    val stub = stub("${pamHeader(Size(20000, 20000))}; exec yes")
    val read = assembled(stub, overlayAssembly(overlay, PASS_THROUGH, null), bytes, directory)
    read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
      codedImageSizeReason(overlay.inputs.first().item, Size(20000, 20000))
  }

  @Test
  fun `a run that writes more or fewer frames than its images is refused`() {
    val bytes = HeifStills.grid("avif", flat.itemType, flat.configuration, flat.codestream, flat.size)
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    for (frames in listOf(grid.tiles.size - 1, grid.tiles.size + 1)) {
      val stub = stub(y4mFrames(flat.size, frames))
      val read = assembled(stub, gridAssembly(grid, PASS_THROUGH), bytes, directory)
      withClue("$frames frames") {
        read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
          CODED_IMAGES_REASON
      }
    }
  }

  @Test
  fun `a run whose frame ends part way is refused`() {
    val bytes = HeifStills.grid("avif", flat.itemType, flat.configuration, flat.codestream, flat.size)
    val grid = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid
    val texels = flat.size.width * flat.size.height * 3
    val stub =
      stub(
        "printf 'YUV4MPEG2 W${flat.size.width} H${flat.size.height} C444\\nFRAME\\n'; head -c ${texels - 1} /dev/zero",
      )
    val read = assembled(stub, gridAssembly(grid, PASS_THROUGH), bytes, directory)
    read.shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe CODED_IMAGES_REASON
  }

  @Test
  fun `a decode whose second run outlasts the deadline is refused at the deadline`() {
    val (bytes, overlay) = overlayOf(flat, heic)
    val started = File(directory, "started")
    val first = "${pamHeader(flat.size)}; head -c ${flat.size.width * flat.size.height * 3} /dev/zero"
    val stub = stub("if [ -e '$started' ]; then exec sleep 30; fi; touch '$started'; $first")
    val timeout = 2_000L
    val deadline = System.nanoTime() + timeout * 1_000_000
    val assembly = overlayAssembly(overlay, PASS_THROUGH, null)
    val read = assembled(stub, assembly, bytes, deadline, timeout, directory, emptyList(), ::wholeStream) { it }
    started.exists() shouldBe true
    val expired = read.shouldBeInstanceOf<OverlayPictureRead.Expired>()
    expired.operation shouldBe ProbeOperation.PICTURE_DECODE
    expired.deadline shouldBe timeout.milliseconds
    expired.reason shouldBe probeExpiredReason("stub-ffmpeg", timeout.milliseconds)
  }

  @Test
  fun `the scratch directory is empty after a decode that reads and one that fails`() {
    val (bytes, overlay) = overlayOf(flat, flat)
    val frames = List(2) { "${pamHeader(flat.size)}; head -c ${flat.size.width * flat.size.height * 3} /dev/zero" }
    val scratch = File(directory, "scratch").apply { mkdirs() }
    for ((post, read) in listOf("cat > /dev/null; printf done" to true, "exit 1" to false)) {
      val stub = stub("case \"$*\" in *rawvideo*) $post ;; *) ${frames.joinToString("; ")} ;; esac")
      val drawn = assembled(stub, overlayAssembly(overlay, PASS_THROUGH, null), bytes, scratch)
      withClue("post child $post") {
        (drawn is OverlayPictureRead.Read) shouldBe read
        scratch.list()?.toList() shouldBe emptyList()
      }
    }
  }

  @Test
  fun `a scratch file whose write fails is deleted`() {
    val scratch = File(directory, "scratch").apply { mkdirs() }
    assertFailsWith<IOException> {
      scratchFile(scratch, ".obu") { out ->
        out.write(ByteArray(SPILLED_BYTES))
        throw IOException("No space left on device")
      }
    }
    scratch.list()?.toList() shouldBe emptyList()
  }

  private fun assembled(
    stub: String,
    assembly: Assembly,
    bytes: ByteArray,
    scratch: File,
  ): OverlayPictureRead<ByteArray> =
    assembled(stub, assembly, bytes, deadline(), TIMEOUT_MILLIS, scratch, emptyList(), ::wholeStream) { it }

  private fun wholeStream(stream: InputStream): ByteArray = stream.readAllBytes()

  // An overlay of [images] side by side, its bytes and how core reads it.
  private fun overlayOf(vararg images: HeifThumbnail): Pair<ByteArray, HeifOverlay> {
    val width = images.maxOf { it.size.width }
    val offsets = images.indices.map { width * it to 0 }
    val canvas = Size(width * images.size, images.maxOf { it.size.height })
    val bytes = HeifOverlays.overlay("heic", images.first(), offsets, canvas, images = images.toList())
    return bytes to heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Read>().overlay
  }

  // A shell line writing [frames] black 4:4:4 frames of [size] behind one y4m header.
  private fun y4mFrames(
    size: Size,
    frames: Int,
  ): String {
    val frame = "printf 'FRAME\\n'; head -c ${size.width * size.height * 3} /dev/zero"
    return "printf 'YUV4MPEG2 W${size.width} H${size.height} C444\\n'; " + List(frames) { frame }.joinToString("; ")
  }

  // A shell line writing the header of an eight-bit RGB pam frame of [size].
  private fun pamHeader(size: Size): String =
    "printf 'P7\\nWIDTH ${size.width}\\nHEIGHT ${size.height}\\nDEPTH 3\\nMAXVAL 255\\nTUPLTYPE RGB\\nENDHDR\\n'"

  private fun stub(script: String): String {
    val binary = File(directory, "stub-ffmpeg")
    binary.writeText("#!/bin/sh\n$script\n")
    check(binary.setExecutable(true)) { "could not make $binary executable" }
    return binary.absolutePath
  }

  private fun deadline(): Long = System.nanoTime() + TIMEOUT_MILLIS * 1_000_000

  private companion object {
    const val TIMEOUT_MILLIS = 10_000L

    // More than the write buffers, so some of it reaches the file before the write fails.
    const val SPILLED_BYTES = 65_536
  }
}
