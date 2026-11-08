package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.probe.IMAGE_PROBE_SIZE
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.fixtures.JxlBits
import dev.jordond.filmstrip.picture.fixtures.LATE_PNG_SIZE
import dev.jordond.filmstrip.picture.fixtures.LATE_TIFF_SIZE
import dev.jordond.filmstrip.picture.fixtures.PAST_THE_HEAD
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.exifBlock
import dev.jordond.filmstrip.picture.fixtures.heifItems
import dev.jordond.filmstrip.picture.fixtures.heifOriented
import dev.jordond.filmstrip.picture.fixtures.jpeg
import dev.jordond.filmstrip.picture.fixtures.jxlBoxed
import dev.jordond.filmstrip.picture.fixtures.jxlCodestream
import dev.jordond.filmstrip.picture.fixtures.lateTaggedJpeg
import dev.jordond.filmstrip.picture.fixtures.lateTaggedJxl
import dev.jordond.filmstrip.picture.fixtures.lateTaggedPng
import dev.jordond.filmstrip.picture.fixtures.lateTaggedTiff
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.fixtures.pngWithExif
import dev.jordond.filmstrip.picture.fixtures.tiffWith
import dev.jordond.filmstrip.picture.fixtures.u64
import dev.jordond.filmstrip.picture.fixtures.webpChunk
import dev.jordond.filmstrip.picture.fixtures.webpWithExif
import dev.jordond.filmstrip.picture.heif.clap
import dev.jordond.filmstrip.picture.heif.heifExtent
import dev.jordond.filmstrip.picture.heif.imir
import dev.jordond.filmstrip.picture.heif.irot
import dev.jordond.filmstrip.picture.heif.ispe
import dev.jordond.filmstrip.picture.heif.sized
import dev.jordond.filmstrip.picture.png.hex
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class PictureExtentTest {
  @Test
  fun everyContainerReadsItsStoredSizeWholeAndThroughRangedReads() =
    runTest {
      for ((name, file, size) in sized()) {
        withClue("$name read whole") { pictureExtentOf(file) shouldBe size }
        withClue("$name fetched") { ranged(file) shouldBe size }
      }
    }

  @Test
  fun aSizeWrittenAfterThePixelsOrAPaddingBoxIsFoundWithoutReadingThem() =
    runTest {
      val late =
        listOf(
          Triple("png", lateTaggedPng(6), LATE_PNG_SIZE),
          Triple("jpeg", lateTaggedJpeg(6), IMAGE_PROBE_SIZE),
          Triple("tiff", lateTaggedTiff(6), LATE_TIFF_SIZE),
          Triple("big endian tiff", lateTaggedTiff(6, little = false), LATE_TIFF_SIZE),
          Triple("heif", padded(heifSized(WIDE)), WIDE),
          Triple("jxl", lateTaggedJxl(6), JXL_FULL_SIZE),
        )
      for ((name, file, size) in late) {
        var fetched = 0L
        val read = pictureExtentOf(file.size.toLong()) { start, count -> read(file, start, count) { fetched += it } }

        withClue(name) { read shouldBe size }
        withClue("$name fetched $fetched of ${file.size} bytes") { (fetched < FETCH_BOUND).shouldBeTrue() }
      }
    }

  // Ratio 2 is 12 to 10, so a width read as the height times anything else does not come out at 48.
  @Test
  fun aJxlWidthGivenAsARatioOfItsHeightComesOutOfThatRatio() {
    val bits =
      JxlBits()
        .write(1, 1)
        .write(4, 5)
        .write(2, 3)
        .write(1, 1)
    val codestream = JXL_CODESTREAM + bits.toByteArray() + ByteArray(8)

    pictureExtentOf(codestream) shouldBe Size(48, 40)
  }

  @Test
  fun aContainerWithNoSizeOrABrokenOneReadsAsNull() {
    val jpeg = rotatedImageProbeBytes(6)
    val unread =
      listOf(
        "GIF89a".encodeToByteArray() + ByteArray(32),
        heifOriented(turns = 1, axis = null),
        jpeg.copyOf(frameHeaderAt(jpeg) + SOF_HEIGHT_CUT),
        jpeg.copyOf(frameHeaderAt(jpeg) + SOF_WIDTH_CUT),
        png().copyOf(PNG_IHDR_CUT),
        webpWithExif(exifBlock(1)).copyOf(WEBP_CANVAS),
        tiffWith(null, Triple(256, 3, 64)),
        ByteArray(0),
      )
    for (file in unread) pictureExtentOf(file).shouldBeNull()
  }

  // Each segment's data would read as 34 by 17 if it were taken for a frame header.
  @Test
  fun aJpegReadsItsFrameHeaderPastTableSegmentsThatShareItsMarkerRange() =
    runTest {
      val jpeg = rotatedImageProbeBytes(6)
      val sof = frameHeaderAt(jpeg)
      for (marker in listOf(DHT, JPG, DAC)) {
        val segment = byteArrayOf(0xFF.toByte(), marker.toByte()) + hex("000908001100220000")
        val file = jpeg.copyOf(sof) + segment + jpeg.copyOfRange(sof, jpeg.size)

        withClue("marker $marker read whole") { pictureExtentOf(file) shouldBe IMAGE_PROBE_SIZE }
        withClue("marker $marker fetched") { ranged(file) shouldBe IMAGE_PROBE_SIZE }
      }
    }

  @Test
  fun aProgressiveJpegReadsItsSizeFromItsFrameHeader() =
    runTest {
      val file = rotatedImageProbeBytes(6).also { it[frameHeaderAt(it) + 1] = SOF2.toByte() }

      pictureExtentOf(file) shouldBe IMAGE_PROBE_SIZE
      ranged(file) shouldBe IMAGE_PROBE_SIZE
    }

  @Test
  fun aVp8FrameReadsItsSidesWithoutItsScaleBits() =
    runTest {
      val file = webpLossy(TALL, scale = 3)

      pictureExtentOf(file) shouldBe TALL
      ranged(file) shouldBe TALL
    }

  @Test
  fun aTiledHeifReadsTheSizeOfItsPrimaryItemAndNeverATiles() {
    val tile = listOf(ispe(TILE))

    pictureExtentOf(heifItems(listOf(ispe(TALL)), tile = tile)) shouldBe TALL
    pictureExtentOf(heifItems(emptyList(), tile = tile)).shouldBeNull()
    pictureExtentOf(heifItems(listOf(ispe(CODED), clap(CROPPED)), tile = tile)) shouldBe CROPPED
  }

  // 2561 over 2 is 1280.5 and rounds up, 2159 over 3 is 719.67 and rounds to 720. 121 over 4 is 30.25 and 100 over 3
  // is 33.33, which round down to libheif's 30 by 33 where rounding up would give 31 by 34.
  //
  // libheif halves both terms, dropping the remainder, while the denominator is over 65536 and then while the numerator
  // is over 65536 and the denominator over 1. 65536 over 3 stays as it is and rounds to 21845, 65537 over 3 becomes
  // 32768 over 1, 100000 over 3 becomes 50000 over 1, 70001 over 2 becomes 35000 over 1, 1000001 over 1000 becomes
  // 62500 over 62 and rounds to 1008, and 128000000 over 100000 becomes 62500 over 48 and rounds to 1302. The offsets
  // are never read, so a negative one and the largest denominators still read.
  @Test
  fun aHeifWithACleanApertureReadsTheCroppedSizeRoundedToTheNearestPixel() =
    runTest {
      val wide = ispe(Size(100_000, 720))
      val cropped =
        listOf(
          heifItems(listOf(ispe(CODED), clap(CROPPED))) to CROPPED,
          heifItems(listOf(ispe(CODED), clap(2561, 2, 2159, 3))) to Size(1281, 720),
          heifItems(listOf(ispe(CODED), clap(121, 4, 100, 3))) to Size(30, 33),
          heifItems(listOf(ispe(CODED), clap(CROPPED), irot(1))) to CROPPED,
          heifItems(listOf(wide, clap(65_536, 3, 720, 1))) to Size(21_845, 720),
          heifItems(listOf(wide, clap(65_537, 3, 720, 1))) to Size(32_768, 720),
          heifItems(listOf(wide, clap(100_000, 3, 720, 1))) to Size(50_000, 720),
          heifItems(listOf(wide, clap(70_001, 2, 720, 1))) to Size(35_000, 720),
          heifItems(listOf(ispe(CODED), clap(1_000_001, 1000, 720, 1))) to Size(1008, 720),
          heifItems(listOf(wide, clap(128_000_000, 100_000, 720, 1))) to Size(1302, 720),
          heifItems(listOf(ispe(CODED), clap(1280, 1, 720, 1, horizontalOffset = -4))) to CROPPED,
          // Moved four rows above the frame, libheif decodes 716 rows of a picture it reports as 720.
          heifItems(listOf(ispe(CODED), clap(1280, 1, 720, 1, verticalOffset = -4))) to null,
          heifItems(listOf(ispe(CODED), clap(1280, 1, 720, 1, horizontalDenominator = INT_MAX))) to CROPPED,
          heifItems(listOf(ispe(CODED), clap(1280, 1, 720, 1, verticalDenominator = INT_MAX))) to CROPPED,
          heifItems(listOf(ispe(INT_MAX, 720), clap(INT_MAX, 1, 720, 1))) to Size(Int.MAX_VALUE, 720),
          heifItems(listOf(ispe(1280, INT_MAX), clap(1280, 1, INT_MAX, 1))) to Size(1280, Int.MAX_VALUE),
        )
      for ((file, size) in cropped) {
        pictureExtentOf(file) shouldBe size
        ranged(file) shouldBe size
      }
    }

  // libheif sizes an item by walking its properties in order, a clap setting the size and a quarter turn swapping it.
  // Each shown size here is the one libheif reports, and the stored size has to turn into it.
  @Test
  fun aHeifCleanApertureListedAfterAQuarterTurnCropsTheTurnedPicture() =
    runTest {
      val ispe = ispe(Size(40, 30))
      val clap = clap(Size(20, 30))
      val listings =
        listOf(
          Triple("irot 1 then clap", listOf(ispe, irot(1), clap), Size(30, 20) to Size(20, 30)),
          Triple("irot 3 then clap", listOf(ispe, irot(3), clap), Size(30, 20) to Size(20, 30)),
          Triple("irot 2 then clap", listOf(ispe, irot(2), clap), Size(20, 30) to Size(20, 30)),
          Triple("imir then clap", listOf(ispe, imir(0), clap), Size(20, 30) to Size(20, 30)),
          Triple("clap then irot 1", listOf(ispe, clap, irot(1)), Size(20, 30) to Size(30, 20)),
          Triple("two claps", listOf(ispe, clap, clap(Size(10, 10))), Size(10, 10) to Size(10, 10)),
          Triple("irot 1 twice then clap", listOf(ispe, irot(1), irot(1), clap), Size(20, 30) to Size(20, 30)),
          Triple("clap between two irot 1", listOf(ispe, irot(1), clap, irot(1)), Size(30, 20) to Size(30, 20)),
          Triple("clap 35x10 then irot 1", listOf(ispe, clap(Size(35, 10)), irot(1)), Size(35, 10) to Size(10, 35)),
        )
      for ((name, properties, sizes) in listings) {
        val (stored, shown) = sizes
        val file = heifItems(properties)

        withClue("$name read whole") { pictureExtentOf(file) shouldBe stored }
        withClue("$name fetched") { ranged(file) shouldBe stored }
        withClue(name) { pictureOrientationOf(file).orientedSize(stored) shouldBe shown }
      }
      withClue("too wide once turned") {
        pictureExtentOf(heifItems(listOf(ispe, irot(1), clap(Size(35, 10))))).shouldBeNull()
      }
      withClue("too wide once turned fetched") {
        ranged(heifItems(listOf(ispe, irot(1), clap(Size(35, 10))))).shouldBeNull()
      }
    }

  @Test
  fun aHeifCleanApertureThatDoesNotReadOrDoesNotFitReadsAsNull() {
    val broken =
      listOf(
        "cut short" to box("clap", clap(CROPPED).copyOfRange(8, 8 + 28)),
        "zero width denominator" to clap(1280, 0, 720, 1),
        "zero height denominator" to clap(1280, 1, 720, 0),
        "zero horizontal offset denominator" to clap(1280, 1, 720, 1, horizontalDenominator = 0),
        "zero vertical offset denominator" to clap(1280, 1, 720, 1, verticalDenominator = 0),
        "width past an Int" to clap(INT_MAX + 1, 1, 720, 1),
        "width denominator past an Int" to clap(1280, INT_MAX + 1, 720, 1),
        "height past an Int" to clap(1280, 1, INT_MAX + 1, 1),
        "height denominator past an Int" to clap(1280, 1, 720, INT_MAX + 1),
        "horizontal offset denominator past an Int" to clap(1280, 1, 720, 1, horizontalDenominator = INT_MAX + 1),
        "vertical offset denominator past an Int" to clap(1280, 1, 720, 1, verticalDenominator = INT_MAX + 1),
        "wider than its ispe" to clap(1289, 1, 720, 1),
        "taller than its ispe" to clap(1280, 1, 721, 1),
      )
    for ((name, clap) in broken) withClue(name) { pictureExtentOf(heifItems(listOf(ispe(CODED), clap))).shouldBeNull() }
    withClue("no ispe") { pictureExtentOf(heifItems(listOf(clap(CROPPED)))).shouldBeNull() }
  }

  // Each file keeps every byte it had and only its frame header's segment or chunk length shrinks, so a size read past
  // that length would still find the real sides. The unpatched files are the controls.
  @Test
  fun aJpegOrWebpSizeIsNeverReadPastTheLengthItsSegmentOrChunkGives() =
    runTest {
      val jpeg = rotatedImageProbeBytes(6)
      val sofLength = frameHeaderAt(jpeg) + 2
      val canvas = webpCanvas(WIDE)
      val lossy = webpLossy(TALL)
      val lossless = webpLossless(WIDE)
      val cases =
        listOf(
          Triple("jpeg", jpeg, IMAGE_PROBE_SIZE),
          Triple("jpeg sof length 7", jpeg.patched(sofLength, bytes { u16(7) }), IMAGE_PROBE_SIZE),
          Triple("jpeg sof length 6", jpeg.patched(sofLength, bytes { u16(6) }), null),
          Triple("jpeg sof length 5", jpeg.patched(sofLength, bytes { u16(5) }), null),
          Triple("webp vp8x", canvas, WIDE),
          Triple("webp vp8x length 9", canvas.patched(WEBP_CHUNK_LENGTH, bytes { u32(9, little = true) }), null),
          Triple("webp vp8x length 4", canvas.patched(WEBP_CHUNK_LENGTH, bytes { u32(4, little = true) }), null),
          Triple("webp vp8", lossy, TALL),
          Triple("webp vp8 length 9", lossy.patched(WEBP_CHUNK_LENGTH, bytes { u32(9, little = true) }), null),
          Triple("webp vp8l", lossless, WIDE),
          Triple("webp vp8l length 4", lossless.patched(WEBP_CHUNK_LENGTH, bytes { u32(4, little = true) }), null),
        )
      for ((name, file, size) in cases) {
        withClue("$name read whole") { pictureExtentOf(file) shouldBe size }
        withClue("$name fetched") { ranged(file) shouldBe size }
      }
    }

  // A jxlc box whose length runs past the end of the file, or is zero, is read as far as the file goes. One shorter
  // than its own header, or a large size past any file, does not read.
  @Test
  fun aJxlcBoxWithALyingLengthReadsAsFarAsTheFileGoesOrNotAtAll() =
    runTest {
      val file = jxlBoxed(jxlCodestream(6))
      val jxlcAt = 12 + ByteView(file, file.size).u32(12).toInt()
      val patched = { length: Long -> file.copyOf().also { bytes { u32(length) }.copyInto(it, jxlcAt) } }
      val large =
        file.copyOf(jxlcAt) +
          bytes {
            u32(1).ascii("jxlc")
            u64(-1, little = false)
          } + file.copyOfRange(jxlcAt + 8, file.size)
      for ((name, lying) in mapOf("past the end" to patched(0x7FFFFFFF), "zero" to patched(0))) {
        withClue(name) { pictureOrientationOf(lying) shouldBe PictureOrientation.ofExif(6) }
        withClue(name) { pictureExtentOf(lying) shouldBe JXL_FULL_SIZE }
        withClue("$name fetched") { ranged(lying) shouldBe JXL_FULL_SIZE }
      }
      for ((name, lying) in mapOf("shorter than its header" to patched(4), "large size past any file" to large)) {
        withClue(name) { pictureOrientationOf(lying) shouldBe PictureOrientation.Normal }
        withClue(name) { pictureExtentOf(lying).shouldBeNull() }
        withClue("$name fetched") { ranged(lying).shouldBeNull() }
      }
    }

  // libheif refuses a clean aperture that crops a side to nothing. The size is checked straight out of heifExtent too,
  // so the zero size check every container shares cannot stand in for it.
  @Test
  fun aHeifCleanApertureWithASideThatRoundsToNothingReadsAsNull() =
    runTest {
      val emptied = mapOf("width" to clap(1, 3, 720, 1), "height" to clap(1280, 1, 1, 3))
      for ((side, clap) in emptied) {
        val file = heifItems(listOf(ispe(CODED), clap))

        withClue(side) { pictureExtentOf(file).shouldBeNull() }
        withClue("$side fetched") { ranged(file).shouldBeNull() }
        withClue("$side straight out of heifExtent") { heifExtent(ByteView(file, file.size)).shouldBeNull() }
      }
    }

  // Files that say they are a gigabyte and end long before it, each with the header it needs pointing past the end.
  @Test
  fun aFileThatEndsBeforeItsSaidSizeReadsAsNull() =
    runTest {
      val jpeg = rotatedImageProbeBytes(6)
      val tiff = tiffWith(null, Triple(256, 3, 300), Triple(257, 4, 200))
      val heif = heifSized(TALL)
      val metaAt = ByteView(heif, heif.size).u32(0).toInt()
      val lying =
        mapOf(
          "jpeg" to jpeg.copyOf(frameHeaderAt(jpeg)),
          "tiff" to tiff.copyOf(4) + bytes { u32(GIGABYTE / 2, little = true) } + tiff.copyOfRange(8, tiff.size),
          "heif" to heif.copyOf(metaAt) + bytes { u32(1L shl 20) } + heif.copyOfRange(metaAt + 4, heif.size),
        )
      for ((name, file) in lying) {
        val size =
          pictureExtentOf(GIGABYTE) { start, count ->
            val from = minOf(start, file.size.toLong()).toInt()
            file.copyOfRange(from, minOf(file.size.toLong(), start + count).toInt())
          }

        withClue(name) { size.shouldBeNull() }
      }
    }

  // This pins that the answer does not hang on where the walk's blocks end, where the file ends or the bounds a ranged
  // read is held to. It does not say whether that answer is right.
  @Test
  fun whereReadsEndOrTheFileIsCutNeverChangesTheSizeOfAMutatedContainer() =
    runTest {
      for ((name, file, _) in sized()) {
        for (at in file.indices) {
          for (value in MUTATIONS) {
            val mutated = file.copyOf().also { it[at] = (it[at].toInt() xor value).toByte() }

            withClue("$name byte $at xor $value") { ranged(mutated) shouldBe pictureExtentOf(mutated) }
          }
          withClue("$name cut to $at") { ranged(file.copyOf(at)) shouldBe pictureExtentOf(file.copyOf(at)) }
        }
      }
    }

  private suspend fun ranged(file: ByteArray): Size? =
    pictureExtentOf(file.size.toLong()) { start, count -> read(file, start, count) {} }

  private inline fun read(
    file: ByteArray,
    start: Long,
    count: Int,
    counted: (Int) -> Unit,
  ): ByteArray {
    check(start >= 0 && count >= 0 && start + count <= file.size) { "read $count bytes at $start of ${file.size}" }
    counted(count)
    return file.copyOfRange(start.toInt(), start.toInt() + count)
  }

  private fun sized(): List<Triple<String, ByteArray, Size>> =
    listOf(
      Triple("jpeg", rotatedImageProbeBytes(6), IMAGE_PROBE_SIZE),
      Triple("png", pngWithExif(exifBlock(6)), Size(1, 1)),
      Triple("webp vp8x", webpCanvas(WIDE), WIDE),
      Triple("webp vp8", webpLossy(TALL), TALL),
      Triple("webp vp8l", webpLossless(WIDE), WIDE),
      Triple("tiff", tiffWith(null, Triple(256, 3, 300), Triple(257, 4, 200)), WIDE),
      Triple("big tiff", tiffWith(null, Triple(256, 4, 200), Triple(257, 3, 300), little = false, big = true), TALL),
      Triple("heif", heifSized(TALL), TALL),
      Triple("cropped heif", heifItems(listOf(ispe(CODED), clap(CROPPED))), CROPPED),
      Triple("jxl", jxlCodestream(6), JXL_FULL_SIZE),
      Triple("small jxl", jxlCodestream(6, small = true), Size(40, 40)),
      Triple("boxed jxl", jxlBoxed(jxlCodestream(6), splitAt = 3), JXL_FULL_SIZE),
    )

  private fun heifSized(size: Size): ByteArray = heifItems(listOf(ispe(size)))

  // A free box past the head ahead of everything but the file type box.
  private fun padded(heif: ByteArray): ByteArray {
    val fileType = ByteView(heif, heif.size).u32(0).toInt()
    return heif.copyOf(fileType) + box("free", ByteArray(PAST_THE_HEAD)) + heif.copyOfRange(fileType, heif.size)
  }

  private fun ByteArray.patched(
    at: Int,
    bytes: ByteArray,
  ): ByteArray = copyOf().also { bytes.copyInto(it, at) }

  // Where the first SOF0 marker of [jpeg] starts.
  private fun frameHeaderAt(jpeg: ByteArray): Int =
    (0 until jpeg.size - 1).first { jpeg[it] == 0xFF.toByte() && jpeg[it + 1] == 0xC0.toByte() }

  private fun webpCanvas(size: Size): ByteArray =
    webpOf(
      bytes {
        val canvas = bytes { u32(size.width - 1L, little = true).u32(size.height - 1L, little = true) }
        webpChunk("VP8X", ByteArray(4) + canvas.copyOf(3) + canvas.copyOfRange(4, 7))
        webpChunk("VP8L", ByteArray(5))
      },
    )

  // A VP8 frame header with [scale] in the top two bits of each side.
  private fun webpLossy(
    size: Size,
    scale: Int = 0,
  ): ByteArray {
    val sides = bytes { u16(size.width or (scale shl 14), true).u16(size.height or (scale shl 14), true) }
    return webpOf(bytes { webpChunk("VP8 ", hex("0000009D012A") + sides) })
  }

  private fun webpLossless(size: Size): ByteArray {
    val sides = (size.width - 1L) or ((size.height - 1L) shl 14)
    return webpOf(bytes { webpChunk("VP8L", bytes { u8(0x2F).u32(sides, little = true) }) })
  }

  private fun webpOf(chunks: ByteArray): ByteArray =
    bytes {
      ascii("RIFF").u32(4L + chunks.size, little = true).ascii("WEBP")
      raw(chunks)
    }

  private companion object {
    val WIDE = Size(300, 200)
    val TALL = Size(200, 300)
    val TILE = Size(512, 512)

    // An AVIF coded in 8 pixel blocks and cropped back to 720p.
    val CODED = Size(1288, 720)
    val CROPPED = Size(1280, 720)
    const val INT_MAX = Int.MAX_VALUE.toLong()

    // The full sides jxlCodestream writes, 1000 and 70000 past their one.
    val JXL_FULL_SIZE = Size(70_001, 1_001)
    val MUTATIONS = intArrayOf(0x01, 0x80, 0xFF)
    const val FETCH_BOUND = 4096
    const val GIGABYTE = 1L shl 30
    const val WEBP_CANVAS = 12 + 8 + 4

    // The little endian length of a WebP's first chunk, past the RIFF header and the chunk's type.
    const val WEBP_CHUNK_LENGTH = 12 + 4
    const val PNG_IHDR_CUT = 20

    // Past the marker, length and precision, then one byte into the height or one into the width.
    const val SOF_HEIGHT_CUT = 6
    const val SOF_WIDTH_CUT = 8
    const val SOF2 = 0xC2
    const val DHT = 0xC4
    const val JPG = 0xC8
    const val DAC = 0xCC
  }
}
