package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.ffmpeg.internal.picture.PictureRun
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRun
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRuns
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.HeifCodec
import dev.jordond.filmstrip.picture.heif.HeifCodedImage
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.heifCodedImages
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThan
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.longs.shouldBeLessThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.ByteArrayOutputStream
import java.io.File
import java.io.OutputStream
import java.lang.management.ManagementFactory
import kotlin.test.Test

/**
 * The coded images of a HEIF overlay framed as elementary streams, one a run of a shared configuration, and decoded by
 * this ffmpeg.
 */
class PictureRunsTest {
  private val avif = ColorPictures.NCLX_BT709_LIMITED.thumbnail()
  private val edge = ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA.thumbnail()
  private val heic = ColorPictures.HEIC_NCLX_BT709_LIMITED.thumbnail()

  @Test
  fun `a change of codec starts a new run and each run decodes one frame an image`() {
    val runs = runsOf(listOf(avif, edge, heic, heic, avif)).shouldNotBeNull()
    runs.map { it.format to it.images } shouldBe listOf("obu" to 2, "hevc" to 2, "obu" to 1)
    for (run in runs) decodedFrames(run) shouldBe run.images
  }

  @Test
  fun `a sample whose NAL length runs past its end does not frame`() {
    val image = codedImages(listOf(heic)).single()
    val cut =
      HeifCodedImage(image.item, HeifCodec.Hevc, image.configuration, image.sample.copyOf(image.sample.size - 1))
    pictureRuns(listOf(cut)).shouldBeNull()
  }

  @Test
  fun `an AV1 sample whose last OBU has no size field frames as the same sample with one`() {
    val image = codedImages(listOf(avif)).single()
    val unsized = HeifCodedImage(image.item, HeifCodec.Av1, image.configuration, image.sample.lastObuUnsized())
    unsized.sample.size shouldBeLessThan image.sample.size
    framed(unsized) shouldBe framed(image)
    decodedFrames(pictureRun(listOf(unsized)).shouldNotBeNull()) shouldBe 1
  }

  @Test
  fun `an unsized last OBU behind a sized one takes its extension byte and a size of two bytes`() {
    val padding = byteArrayOf(0x7A, 1, 9)
    val payload = ByteArray(200) { it.toByte() }
    val sample = padding + byteArrayOf(0x34, 0x28) + payload
    val written = framedAv1(sample).toList()
    written.takeLast(payload.size + 4) shouldBe (byteArrayOf(0x36, 0x28, 0xC8.toByte(), 1) + payload).toList()
    written.dropLast(payload.size + 4).takeLast(padding.size) shouldBe padding.toList()
  }

  @Test
  fun `an AV1 sample whose OBU size runs past its end or that sets the forbidden bit does not frame`() {
    val image = codedImages(listOf(avif)).single()
    for (sample in listOf(byteArrayOf(0x7A, 5, 1), byteArrayOf(0x7A, 0x80.toByte()), byteArrayOf(0xB2.toByte(), 0))) {
      pictureRuns(listOf(HeifCodedImage(image.item, HeifCodec.Av1, image.configuration, sample))).shouldBeNull()
    }
  }

  @Test
  fun `one tile named in every place of the largest grid is framed from its own arrays`() {
    val images = repeated(listOf(heic), MAX_GRID_TILES)
    val run = pictureRuns(images).shouldNotBeNull().single()
    run.images shouldBe MAX_GRID_TILES
    run.shouldBeWrittenFrom(images.first())
  }

  @Test
  fun `two inputs named in turn over and over are framed from their own arrays`() {
    val images = repeated(listOf(avif, edge), MAX_GRID_TILES)
    val run = pictureRuns(images).shouldNotBeNull().single()
    run.images shouldBe MAX_GRID_TILES
    run.shouldBeWrittenFrom(images[0], images[1])
  }

  @Test
  fun `a sample of empty NAL units is framed and written without holding a part for each`() {
    val image = codedImages(listOf(heic)).single()
    // Every four zero bytes is one NAL unit of no length, a quarter of a million of them.
    val empty = HeifCodedImage(image.item, HeifCodec.Hevc, image.configuration, ByteArray(1 shl 20))
    val threads = ManagementFactory.getThreadMXBean() as com.sun.management.ThreadMXBean
    val id = Thread.currentThread().id
    val before = threads.getThreadAllocatedBytes(id)
    pictureRun(listOf(empty, empty)).shouldNotBeNull().writeTo(OutputStream.nullOutputStream())
    val allocated = threads.getThreadAllocatedBytes(id) - before
    withClue("$allocated bytes allocated") { allocated shouldBeLessThan empty.sample.size.toLong() / 4 }
  }

  @Test
  fun `an image named three times decodes three frames`() {
    val run = pictureRuns(repeated(listOf(heic), 3)).shouldNotBeNull().single()
    decodedFrames(run) shouldBe 3
  }

  // The run writes as many bytes as the images it names framed one by one, and never an array larger than the
  // largest sample or configuration of [distinct], so no part of it is joined into a buffer of its own.
  private fun PictureRun.shouldBeWrittenFrom(vararg distinct: HeifCodedImage) {
    val framedAlone = distinct.map { framedLength(listOf(it)) }
    val framedTwice = distinct.map { framedLength(listOf(it, it)) }
    val header = 2 * framedAlone.first() - framedTwice.first()
    val each = framedTwice.zip(framedAlone) { twice, alone -> twice - alone }
    val sink = WrittenArrays()
    writeTo(sink)
    sink.length shouldBe header + (0 until images).sumOf { each[it % distinct.size] }
    sink.largest shouldBeLessThanOrEqual distinct.maxOf { maxOf(it.sample.size, it.configuration.size) }
  }

  private fun framed(image: HeifCodedImage): List<Byte> {
    val out = ByteArrayOutputStream()
    pictureRun(listOf(image)).shouldNotBeNull().writeTo(out)
    return out.toByteArray().toList()
  }

  private fun framedAv1(sample: ByteArray): ByteArray {
    val image = codedImages(listOf(avif)).single()
    val out = ByteArrayOutputStream()
    pictureRun(listOf(HeifCodedImage(image.item, HeifCodec.Av1, image.configuration, sample)))
      .shouldNotBeNull()
      .writeTo(out)
    return out.toByteArray()
  }

  // This sample with its last OBU's size field taken out, as a sample may leave it.
  private fun ByteArray.lastObuUnsized(): ByteArray {
    var at = 0
    while (true) {
      val headerBytes = if (this[at].toInt() and 0x04 == 0) 1 else 2
      var size = 0
      var sizeBytes = 0
      do {
        val next = this[at + headerBytes + sizeBytes].toInt() and 0xFF
        size = size or ((next and 0x7F) shl (7 * sizeBytes))
        sizeBytes++
      } while (next and 0x80 != 0)
      val end = at + headerBytes + sizeBytes + size
      if (end == this.size) {
        val header = byteArrayOf((this[at].toInt() and 0x02.inv()).toByte()) + copyOfRange(at + 1, at + headerBytes)
        return copyOfRange(0, at) + header + copyOfRange(at + headerBytes + sizeBytes, end)
      }
      at = end
    }
  }

  private fun framedLength(images: List<HeifCodedImage>): Long {
    val sink = WrittenArrays()
    pictureRun(images).shouldNotBeNull().writeTo(sink)
    return sink.length
  }

  // The coded images of an overlay of [images] named [times] times over, in turn.
  private fun repeated(
    images: List<HeifThumbnail>,
    times: Int,
  ): List<HeifCodedImage> {
    val offsets = images.indices.map { 8 * it to 0 }
    val bytes = HeifOverlays.overlay("heic", images.first(), offsets, Size(8 * images.size, 8), images = images)
    val items =
      heifPrimaryOverlay(bytes)
        .shouldBeInstanceOf<HeifOverlayRead.Read>()
        .overlay.inputs
        .map { it.item }
    return heifCodedImages(bytes, List(times) { items[it % items.size] }).shouldNotBeNull()
  }

  // Counts what is written and the largest array it is written from.
  private class WrittenArrays : OutputStream() {
    var length = 0L
    var largest = 0

    override fun write(byte: Int) {
      length++
    }

    override fun write(
      bytes: ByteArray,
      offset: Int,
      count: Int,
    ) {
      length += count
      largest = maxOf(largest, bytes.size)
    }
  }

  private fun runsOf(images: List<HeifThumbnail>): List<PictureRun>? = pictureRuns(codedImages(images))

  private fun codedImages(images: List<HeifThumbnail>): List<HeifCodedImage> {
    val offsets = images.indices.map { 8 * it to 0 }
    val bytes = HeifOverlays.overlay("heic", images.first(), offsets, Size(8 * images.size, 8), images = images)
    val overlay = heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Read>().overlay
    return heifCodedImages(bytes, overlay.inputs.map { it.item }).shouldNotBeNull()
  }

  // How many frames this ffmpeg decodes out of [run], each its own line of framemd5 output.
  private fun decodedFrames(run: PictureRun): Int {
    val file = File.createTempFile("filmstrip-run", ".${run.format}")
    try {
      file.outputStream().buffered().use(run::writeTo)
      val command =
        listOf("ffmpeg", "-nostdin", "-hide_banner", "-loglevel", "error", "-threads", "1", "-f", run.format) +
          listOf("-i", file.path, "-fps_mode", "passthrough", "-f", "framemd5", "-")
      val process = ProcessBuilder(command).redirectErrorStream(true).start()
      val output = process.inputStream.bufferedReader().readText()
      process.waitFor() shouldBe 0
      return output.lines().count { it.isNotBlank() && !it.startsWith("#") }
    } finally {
      file.delete()
    }
  }

  private companion object {
    // The most tiles a grid lists, 256 a side.
    const val MAX_GRID_TILES = 65536
  }
}
