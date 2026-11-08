package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import dev.jordond.filmstrip.picture.fixtures.EXIF_VALUE_END
import dev.jordond.filmstrip.picture.fixtures.PNG_EXIF_AT
import dev.jordond.filmstrip.picture.fixtures.asciiBytes
import dev.jordond.filmstrip.picture.fixtures.box
import dev.jordond.filmstrip.picture.fixtures.bytes
import dev.jordond.filmstrip.picture.fixtures.exifBlock
import dev.jordond.filmstrip.picture.fixtures.ftyp
import dev.jordond.filmstrip.picture.fixtures.fullBox
import dev.jordond.filmstrip.picture.fixtures.heifItems
import dev.jordond.filmstrip.picture.fixtures.heifOriented
import dev.jordond.filmstrip.picture.fixtures.jxlBoxed
import dev.jordond.filmstrip.picture.fixtures.jxlCodestream
import dev.jordond.filmstrip.picture.fixtures.png
import dev.jordond.filmstrip.picture.fixtures.pngWithExif
import dev.jordond.filmstrip.picture.fixtures.tiffWith
import dev.jordond.filmstrip.picture.fixtures.webpWithExif
import dev.jordond.filmstrip.picture.png.hex
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PictureOrientationReadTest {
  @Test
  fun everyContainerReadsBackEveryValueItWasTaggedWith() {
    for (orientation in PictureOrientation.entries) {
      val value = orientation.exif
      val tagged =
        mapOf(
          "jpeg big endian" to rotatedImageProbeBytes(value, bigEndian = true),
          "jpeg little endian" to rotatedImageProbeBytes(value, bigEndian = false),
          "png" to pngWithExif(exifBlock(value)),
          "png after its image data" to pngWithExif(exifBlock(value, little = true), afterImageData = true),
          "png with an exif identifier" to pngWithExif(EXIF_IDENTIFIER + exifBlock(value)),
          "webp" to webpWithExif(exifBlock(value, little = true)),
          "webp with an exif identifier" to webpWithExif(EXIF_IDENTIFIER + exifBlock(value)),
          "tiff little endian" to exifBlock(value, little = true),
          "tiff big endian" to exifBlock(value),
          "bigtiff" to exifBlock(value, little = true, big = true),
          "jxl codestream" to jxlCodestream(value),
          "jxl codestream in eighths" to jxlCodestream(value, small = true),
          "jxl in a jxlc box" to jxlBoxed(jxlCodestream(value)),
          "jxl split over jxlp boxes" to jxlBoxed(jxlCodestream(value), splitAt = 3),
        )
      for ((container, bytes) in tagged) {
        withClue("$container tagged $value") { pictureOrientationOf(bytes) shouldBe orientation }
      }
    }
  }

  @Test
  fun aContainerWithNoOrientationReadsAsNormal() {
    val untagged =
      listOf(
        png(),
        webpWithExif(exifBlock(6), flagged = false),
        tiffWith(null, Triple(256, 3, 64)),
        heifOriented(turns = null, axis = null),
        jxlCodestream(null),
        jxlCodestream(6, allDefault = true),
        jxlBoxed(jxlCodestream(null)),
        "GIF89a".encodeToByteArray(),
        ByteArray(0),
      )
    for (bytes in untagged) pictureOrientationOf(bytes) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aTagOutsideTheRangeOrOfAnotherTypeReadsAsNormal() {
    pictureOrientationOf(exifBlock(0)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(exifBlock(9)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(exifBlock(6, type = TIFF_LONG_TYPE)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(pngWithExif(exifBlock(6, type = TIFF_LONG_TYPE))) shouldBe PictureOrientation.Normal
  }

  // HEIF applies irot and imir in the order the item lists them. The expected pictures are built by turning and
  // mirroring a grid in that order, so the fold is checked against what the two properties do rather than against a
  // second copy of its own table. imir axis 0 swaps top and bottom and axis 1 swaps left and right.
  @Test
  fun everyTurnAndMirrorFoldsToTheOrientationThatShowsTheSamePicture() {
    val stored = Size(3, 2)
    val texels = IntArray(6) { it + 1 }
    for (mirrorFirst in listOf(false, true)) {
      for (turns in 0..3) {
        for (axis in listOf(null, 0, 1)) {
          var expected = Grid(stored.width, stored.height, texels)
          if (mirrorFirst) expected = expected.mirrored(axis)
          repeat(turns) { expected = expected.turnedAnticlockwise() }
          if (!mirrorFirst) expected = expected.mirrored(axis)
          val read = pictureOrientationOf(heifOriented(turns, axis, mirrorFirst))
          val case = "irot $turns imir $axis mirror first $mirrorFirst"

          withClue(case) { read.orientedSize(stored) shouldBe Size(expected.width, expected.height) }
          withClue(case) { orientedSamples(read, texels, stored) shouldBe expected.texels }
        }
      }
    }
  }

  // libheif applies every irot and imir an item lists, not only the first of each. Each run of two and of three is
  // checked against a grid turned and mirrored by each property in turn.
  @Test
  fun everyTurnAndMirrorAnItemListsFoldsInTheOrderItListsThem() {
    val stored = Size(3, 2)
    val texels = IntArray(6) { it + 1 }
    val transforms = (0..3).map { "irot" to it } + (0..1).map { "imir" to it }
    val runs = transforms.flatMap { first -> transforms.map { listOf(first, it) } }
    for (run in runs + runs.flatMap { pair -> transforms.map { pair + it } }) {
      var expected = Grid(stored.width, stored.height, texels)
      for ((type, value) in run) {
        when (type) {
          "imir" -> expected = expected.mirrored(value)
          else -> repeat(value) { expected = expected.turnedAnticlockwise() }
        }
      }
      val properties = run.map { (type, value) -> box(type, byteArrayOf(value.toByte())) }
      val read = pictureOrientationOf(heifItems(listOf(fullBox("ispe", 0, 0, ByteArray(8))) + properties))

      withClue("$run") { read.orientedSize(stored) shouldBe Size(expected.width, expected.height) }
      withClue("$run") { orientedSamples(read, texels, stored) shouldBe expected.texels }
    }
  }

  @Test
  fun aHeicThatListsItsMirrorBeforeItsTurnMirrorsFirst() {
    val heic = heifOriented(turns = 1, axis = 0, mirrorFirst = true, fileType = ftyp("heic", "mif1"))

    pictureOrientationOf(heic) shouldBe PictureOrientation.Transverse
    pictureOrientationOf(heifOriented(turns = 1, axis = 0)) shouldBe PictureOrientation.Transpose
  }

  @Test
  fun theReservedBitsOfTheTurnAndMirrorAreLeftOut() {
    pictureOrientationOf(heifOriented(turns = 0xFD, axis = null)) shouldBe PictureOrientation.Rotate270
    pictureOrientationOf(heifOriented(turns = null, axis = 0xFF)) shouldBe PictureOrientation.FlipHorizontal
  }

  @Test
  fun avifencFilesReadAsTheTurnAndMirrorTheyWereMadeWith() {
    pictureOrientationOf(AVIFENC_IROT_1_IMIR_0) shouldBe PictureOrientation.Transpose
    pictureOrientationOf(AVIFENC_IMIR_0) shouldBe PictureOrientation.FlipVertical
    pictureOrientationOf(AVIFENC_IMIR_1) shouldBe PictureOrientation.FlipHorizontal
  }

  // A copy cut to the prefix's length makes any read past its end throw out of the array rather than land on bytes
  // the prefix does not hold.
  @Test
  fun everyPrefixOfEveryContainerReadsWithoutGoingPastItsEnd() {
    val tagged =
      listOf(
        rotatedImageProbeBytes(6),
        pngWithExif(exifBlock(6)),
        webpWithExif(exifBlock(6)),
        exifBlock(6),
        exifBlock(6, big = true),
        heifOriented(turns = 3, axis = null),
        jxlCodestream(6),
        jxlBoxed(jxlCodestream(6), splitAt = 3),
      )
    for (bytes in tagged) {
      for (length in 0 until bytes.size) {
        val read = pictureOrientationOf(bytes.copyOf(length))
        withClue("$length bytes") {
          (read == PictureOrientation.Normal || read == PictureOrientation.Rotate90).shouldBeTrue()
        }
      }
    }
  }

  @Test
  fun aContainerCutInsideItsTagReadsAsNormal() {
    val png = pngWithExif(exifBlock(6))
    val exifEnd = PNG_EXIF_AT + 12 + exifBlock(6).size
    val webp = webpWithExif(exifBlock(6))
    val heif = heifOriented(turns = 3, axis = null)
    val codestream = jxlCodestream(6)

    pictureOrientationOf(png.copyOf(exifEnd - 1)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(png.copyOf(exifEnd)) shouldBe PictureOrientation.Rotate90
    pictureOrientationOf(webp.copyOf(webp.size - 1)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(exifBlock(6).copyOf(EXIF_VALUE_END - 1)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(exifBlock(6).copyOf(EXIF_VALUE_END)) shouldBe PictureOrientation.Rotate90
    pictureOrientationOf(heif.copyOf(heif.size - 1)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(codestream.copyOf(JXL_ORIENTATION_END - 1)) shouldBe PictureOrientation.Normal
    pictureOrientationOf(codestream.copyOf(JXL_ORIENTATION_END)) shouldBe PictureOrientation.Rotate90
  }

  @Test
  fun aJpegWhoseExifPointsPastItsEndReadsAsNormal() {
    val jpeg = rotatedImageProbeBytes(6)
    jpeg[JPEG_DIRECTORY_OFFSET] = 0x7F

    pictureOrientationOf(jpeg) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aPngWhoseExifChunkClaimsMoreThanTheFileHoldsReadsAsNormal() {
    val png = pngWithExif(exifBlock(6))
    png[PNG_EXIF_AT] = 0x7F

    pictureOrientationOf(png) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aPngWhoseExifChunkFailsItsCrcReadsAsNormal() {
    val png = pngWithExif(exifBlock(6))
    png[PNG_EXIF_AT + 8 + EXIF_VALUE_END - 1] = 3

    pictureOrientationOf(png) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aWebpWhoseLengthsLieReadsAsNormal() {
    val chunkSize = webpWithExif(exifBlock(6))
    chunkSize[chunkSize.size - exifBlock(6).size - 1] = 0x7F

    pictureOrientationOf(chunkSize) shouldBe PictureOrientation.Normal
    pictureOrientationOf(webpWithExif(exifBlock(6), riffSize = 30)) shouldBe PictureOrientation.Normal
    // A RIFF size past the end of the file is held to the file, which still holds the whole chunk.
    pictureOrientationOf(webpWithExif(exifBlock(6), riffSize = 0xFFFFFFF0)) shouldBe PictureOrientation.Rotate90
  }

  @Test
  fun aTiffWhoseDirectoryOrCountLiesReadsAsNormal() {
    val offset = exifBlock(6, little = true)
    offset[4] = 0x7F
    val bigOffset = exifBlock(6, little = true, big = true)
    bigOffset[14] = 0x7F
    val count = tiffWith(null, Triple(256, 3, 64), little = true)
    count[8] = 0xFF.toByte()
    count[9] = 0xFF.toByte()

    pictureOrientationOf(offset) shouldBe PictureOrientation.Normal
    pictureOrientationOf(bigOffset) shouldBe PictureOrientation.Normal
    pictureOrientationOf(count) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aBigtiffWhoseOffsetSizeIsNotEightReadsAsNormal() {
    val bigtiff = exifBlock(6, little = true, big = true)
    pictureOrientationOf(bigtiff) shouldBe PictureOrientation.Rotate90

    bigtiff[4] = 4
    pictureOrientationOf(bigtiff) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aHeifWhoseBoxesLieReadsAsNormal() {
    val meta = heifOriented(turns = 3, axis = null)
    val metaAt = ftyp("avif", "mif1").size
    meta[metaAt] = 0x7F
    val emptyTurn = heifItems(listOf(box("irot", ByteArray(0))))

    pictureOrientationOf(meta) shouldBe PictureOrientation.Normal
    pictureOrientationOf(emptyTurn) shouldBe PictureOrientation.Normal
  }

  @Test
  fun aJxlWhoseBoxesLieReadsAsNormal() {
    val signature = jxlBoxed(jxlCodestream(6)).copyOf(JXL_SIGNATURE_BOX)
    val tooShort = signature + byteArrayOf(0, 0, 0, 4) + asciiBytes("jxlc") + jxlCodestream(6)
    val noIndex = signature + byteArrayOf(0, 0, 0, 8) + asciiBytes("jxlp")
    val notACodestream = signature + box("jxlc", ByteArray(16))

    pictureOrientationOf(tooShort) shouldBe PictureOrientation.Normal
    pictureOrientationOf(noIndex) shouldBe PictureOrientation.Normal
    pictureOrientationOf(notACodestream) shouldBe PictureOrientation.Normal
    // A codestream box claiming more than the file holds is the head of a file, read as far as it goes.
    val cut = signature + bytes { u32(4096).ascii("jxlc") } + jxlCodestream(6)
    pictureOrientationOf(cut) shouldBe PictureOrientation.Rotate90
  }

  @Test
  fun aJxlBoxClaimingNearlyTwoToTheSixtyThreeBytesIsHeldToTheFile() {
    val signature = jxlBoxed(jxlCodestream(6)).copyOf(JXL_SIGNATURE_BOX)
    val file = signature + byteArrayOf(0, 0, 0, 1) + asciiBytes("jxlc") + hex("7FFFFFFFFFFFFFFF") + jxlCodestream(6)

    pictureOrientationOf(file) shouldBe PictureOrientation.Rotate90
  }

  private class Grid(
    val width: Int,
    val height: Int,
    val texels: IntArray,
  ) {
    fun at(
      x: Int,
      y: Int,
    ): Int = texels[y * width + x]

    // A stored column read bottom to top becomes a shown row, starting from the rightmost column.
    fun turnedAnticlockwise(): Grid =
      Grid(height, width, IntArray(texels.size) { at(width - 1 - it / height, it % height) })

    // Axis 0 swaps top and bottom, axis 1 swaps left and right and null leaves the grid as it is.
    fun mirrored(axis: Int?): Grid =
      when (axis) {
        null -> this
        0 -> Grid(width, height, IntArray(texels.size) { at(it % width, height - 1 - it / width) })
        else -> Grid(width, height, IntArray(texels.size) { at(width - 1 - it % width, it / width) })
      }
  }

  private companion object {
    val EXIF_IDENTIFIER = asciiBytes("Exif\u0000\u0000")
    const val TIFF_LONG_TYPE = 4

    /**
     * The high byte of the first directory's offset in the probe's big endian Exif block, four bytes into the TIFF
     * block that starts twelve bytes into the file.
     */
    const val JPEG_DIRECTORY_OFFSET = 12 + 4

    /**
     * The bytes a full size JPEG XL codestream needs before its orientation reads, the signature and 44 bits.
     */
    const val JXL_ORIENTATION_END = 2 + 6

    /**
     * The JPEG XL signature box, the first twelve bytes of a boxed file.
     */
    const val JXL_SIGNATURE_BOX = 12

    // The AVIFs below are a 2x2 black 8 bit greyscale PNG put through avifenc 1.4.2 with aom, using
    // `avifenc -l --irot 1 --imir 0 grey.png out.avif`, then `--imir 0` and `--imir 1` alone.
    val AVIFENC_IROT_1_IMIR_0 =
      hex(
        "0000001c667479706176696600000000617669666d6966316d696166000000fd6d657461000000000000002168646c72" +
          "000000000000000070696374000000000000000000000000000000000e7069746d0000000000010000001e696c6f6300" +
          "0000004400000100010000000100000121000000150000002869696e660000000000010000001a696e66650200000000" +
          "01000061763031436f6c6f72000000007c697072700000005b6970636f00000014697370650000000000000002000000" +
          "020000000e706978690000000001080000000c6176314381001c0000000013636f6c726e636c780001000d0006800000" +
          "000969726f740100000009696d6972000000001969706d6100000000000000010001060102830485860000001d6d6461" +
          "7412000a0718003618086835320810000018e1427210",
      )
    val AVIFENC_IMIR_0 =
      hex(
        "0000001c667479706176696600000000617669666d6966316d696166000000f36d657461000000000000002168646c72" +
          "000000000000000070696374000000000000000000000000000000000e7069746d0000000000010000001e696c6f6300" +
          "0000004400000100010000000100000117000000150000002869696e660000000000010000001a696e66650200000000" +
          "01000061763031436f6c6f72000000007269707270000000526970636f00000014697370650000000000000002000000" +
          "020000000e706978690000000001080000000c6176314381001c0000000013636f6c726e636c780001000d0006800000" +
          "0009696d6972000000001869706d61000000000000000100010501028304850000001d6d64617412000a071800361808" +
          "6835320810000018e1427210",
      )
    val AVIFENC_IMIR_1 =
      hex(
        "0000001c667479706176696600000000617669666d6966316d696166000000f36d657461000000000000002168646c72" +
          "000000000000000070696374000000000000000000000000000000000e7069746d0000000000010000001e696c6f6300" +
          "0000004400000100010000000100000117000000150000002869696e660000000000010000001a696e66650200000000" +
          "01000061763031436f6c6f72000000007269707270000000526970636f00000014697370650000000000000002000000" +
          "020000000e706978690000000001080000000c6176314381001c0000000013636f6c726e636c780001000d0006800000" +
          "0009696d6972010000001869706d61000000000000000100010501028304850000001d6d64617412000a071800361808" +
          "6835320810000018e1427210",
      )
  }
}
