package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.PictureOrientation
import dev.jordond.filmstrip.media.probe.rotatedImageProbeBytes
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue
import kotlin.test.fail

class PictureOrientationFetchTest {
  @Test
  fun everyContainerFindsATagPastTheHeadWhereverItSits() =
    runTest {
      for ((name, picture) in lateTagged()) {
        for (exif in 1..8) {
          val file = picture(exif)
          val fetch = Fetch.of(file)

          assertEquals(PictureOrientation.ofExif(exif), pictureOrientationOf(file), "$name $exif read whole")
          assertEquals(PictureOrientation.ofExif(exif), fetch.orientation(), "$name $exif fetched")
          assertTrue(fetch.fetched < FETCH_BOUND, "$name fetched ${fetch.fetched} of ${file.size} bytes")
        }
      }
    }

  @Test
  fun aHeifTagPastTheHeadFoldsItsTurnAndMirrorAsTheWholeFileDoes() =
    runTest {
      for (turns in listOf(null, 0, 1, 2, 3)) {
        for (axis in listOf(null, 0, 1)) {
          val file = lateTaggedHeif(turns, axis)

          assertEquals(heifOrientationOf(turns ?: 0, axis), Fetch.of(file).orientation(), "turns $turns axis $axis")
        }
      }
    }

  @Test
  fun aFileCutShortOfItsTagReadsAsNormal() =
    runTest {
      for ((name, picture) in lateTagged()) {
        val cut = picture(6).copyOf(PAST_THE_HEAD)

        assertEquals(PictureOrientation.Normal, Fetch.of(cut).orientation(), name)
      }
    }

  // A file whose size is overstated by whoever holds it ends early on a read, and that reads as a cut file does.
  @Test
  fun aFileThatEndsBeforeItsSaidSizeReadsAsNormal() =
    runTest {
      for ((name, picture) in lateTagged()) {
        val cut = picture(6).copyOf(PAST_THE_HEAD)

        assertEquals(PictureOrientation.Normal, Fetch.of(cut, size = cut.size * 2L).orientation(), name)
      }
    }

  @Test
  fun aTagAtTheHeadIsStillReadThroughTheFetch() =
    runTest {
      for (file in early()) assertEquals(pictureOrientationOf(file), Fetch.of(file).orientation())
    }

  // Each byte of each container set to a few values, and each container cut at each length, reads the same from
  // memory and through ranged reads. This pins that the answer does not hang on where the walk's blocks end, where
  // the file ends or the bounds a ranged read is held to. It does not say whether that answer is right.
  @Test
  fun whereReadsEndOrTheFileIsCutNeverChangesTheOrientationOfAMutatedContainer() =
    runTest {
      for (file in early()) {
        for (at in file.indices) {
          for (value in MUTATIONS) {
            val mutated = file.copyOf().also { it[at] = (it[at].toInt() xor value).toByte() }

            assertEquals(pictureOrientationOf(mutated), Fetch.of(mutated).orientation(), "byte $at xor $value")
          }
          val cut = file.copyOf(at)
          assertEquals(pictureOrientationOf(cut), Fetch.of(cut).orientation(), "cut to $at")
        }
      }
    }

  // A PNG eXIf whose CRC does not hold is passed over for a later one, as the whole file read does.
  @Test
  fun aPngExifChunkWithABrokenCrcIsPassedOver() =
    runTest {
      val broken = bytes { pngChunk("eXIf", exifBlock(3)) }.also { it[it.size - 1] = (it.last() + 1).toByte() }
      val file = png(broken, afterImageData = bytes { pngChunk("eXIf", exifBlock(8)) })

      assertEquals(PictureOrientation.ofExif(8), Fetch.of(file).orientation())
    }

  // No entry count is clamped. The walk reads a directory as far as the file and its budget let it.
  @Test
  fun aBigTiffTagPastTheMostEntriesAClassicDirectoryCountsIsRead() =
    runTest {
      val file = bigTiffTaggedAtEntry(BIG_TIFF_TAG_ENTRY, orientation = 6)
      val fetch = Fetch.of(file)

      assertEquals(PictureOrientation.ofExif(6), pictureOrientationOf(file))
      assertEquals(PictureOrientation.ofExif(6), fetch.orientation())
      assertTrue(fetch.reads <= MAX_WALK_READS, "${fetch.reads} reads")
    }

  @Test
  fun aFormatThatCarriesNoTagFetchesOnlyItsOpening() =
    runTest {
      val fetch = Fetch.of("GIF89a".encodeToByteArray() + ByteArray(PAST_THE_HEAD))

      assertEquals(PictureOrientation.Normal, fetch.orientation())
      assertEquals(1, fetch.reads)
      assertTrue(fetch.fetched <= WALK_READ_AHEAD, "fetched ${fetch.fetched}")
    }

  // Files of a gigabyte made of nothing but the smallest step their container allows. Each would take millions of
  // reads to walk to its end, so each stops at the walk's budget instead.
  @Test
  fun aFileOfEndlessSmallStepsStopsAtTheReadBudget() =
    runTest {
      val endless =
        mapOf(
          "jpeg fill" to repeating(JPEG_START, byteArrayOf(0xFF.toByte())),
          "jpeg empty segments" to repeating(JPEG_START, hex("FFE40002")),
          "png empty chunks" to repeating(PNG_SIGNATURE, emptyPngChunk()),
          "webp empty chunks" to repeating(webpOpening(), bytes { ascii("JUNK").u32(0, little = true) }),
          "heif free boxes" to repeating(bytes { u32(8).ascii("ftyp") }, bytes { u32(8).ascii("free") }),
          "big tiff zero entries" to repeating(bigTiffHeader(count = 1L shl 40), ByteArray(1)),
          "jxl free boxes" to repeating(jxlOpening(), box("free")),
          "jxl empty jxlp boxes" to repeating(jxlOpening(), box("jxlp", bytes { u32(0) })),
        )
      for ((name, file) in endless) {
        val fetch = Fetch(GIGABYTE, file)

        assertEquals(PictureOrientation.Normal, fetch.orientation(), name)
        assertEquals(MAX_WALK_READS, fetch.reads, name)
        assertTrue(fetch.fetched <= MAX_WALK_BYTES, "$name fetched ${fetch.fetched} bytes")
      }
    }

  // Each jxlp box carries one byte of codestream, so the walk has the head it gathers after that many boxes and stops
  // there. The bytes are no codestream, so they read as Normal.
  @Test
  fun aJxlOfEndlessOneByteJxlpBoxesStopsOnceItHasTheCodestreamHead() =
    runTest {
      val fetch = Fetch(GIGABYTE, repeating(jxlOpening(), box("jxlp", bytes { u32(0) }, byteArrayOf(0x5A))))

      assertEquals(PictureOrientation.Normal, fetch.orientation())
      assertTrue(fetch.fetched < FETCH_BOUND, "fetched ${fetch.fetched}")
    }

  // An XMP segment of nearly 64 KiB ahead of the Exif one is stepped over on the strength of its opening bytes.
  @Test
  fun aJpegApp1ThatIsNotExifIsSteppedOverWithoutBeingRead() =
    runTest {
      val tagged = rotatedImageProbeBytes(6)
      val xmp = asciiBytes("http://ns.adobe.com/xap/1.0/\u0000") + ByteArray(0xFF00)
      val segment = bytes { u8(0xFF).u8(0xE1).u16(2 + xmp.size).raw(xmp) }
      val file = tagged.copyOf(2) + segment + tagged.copyOfRange(2, tagged.size)
      val fetch = Fetch.of(file)

      assertEquals(PictureOrientation.ofExif(6), fetch.orientation())
      assertTrue(fetch.fetched < FETCH_BOUND, "fetched ${fetch.fetched}")
    }

  // A PNG eXIf chunk, which is read whole for its CRC, and a HEIF meta box, which is read whole for its items, each
  // claiming more than a walk reads at once.
  @Test
  fun aTagBlockLongerThanTheWalkReadsAtOnceReadsAsNormalWithoutBeingRead() =
    runTest {
      val lying =
        mapOf(
          "png eXIf" to repeating(PNG_SIGNATURE + bytes { u32(MAX_WALK_BLOCK.toLong()).ascii("eXIf") }, ByteArray(1)),
          "heif meta" to
            repeating(bytes { u32(8).ascii("ftyp").u32(MAX_WALK_BLOCK + 1L).ascii("meta") }, ByteArray(1)),
        )
      for ((name, file) in lying) {
        val fetch = Fetch(GIGABYTE, file)

        assertEquals(PictureOrientation.Normal, fetch.orientation(), name)
        assertTrue(fetch.largest <= WALK_READ_AHEAD, "$name read ${fetch.largest} bytes at once")
      }
    }

  @Test
  fun aTagBlockThatFitsInOneReadIsReadWhole() =
    runTest {
      val padded = exifBlock(7) + ByteArray(MAX_WALK_BLOCK - 8 - exifBlock(7).size)

      assertEquals(PictureOrientation.ofExif(7), Fetch.of(pngHolding(padded)).orientation())
      assertEquals(PictureOrientation.Normal, Fetch.of(pngHolding(padded + ByteArray(1))).orientation())
    }

  private fun early(): List<ByteArray> =
    listOf(
      rotatedImageProbeBytes(5),
      pngWithExif(exifBlock(5)),
      webpWithExif(exifBlock(5)),
      exifBlock(5, big = true),
      exifBlock(5, little = true),
      exifBlock(5),
      heifOriented(turns = 1, axis = 0),
      jxlCodestream(5),
      jxlBoxed(jxlCodestream(5), splitAt = 3),
    )

  private fun lateTagged(): List<Pair<String, (Int) -> ByteArray>> =
    listOf(
      "png" to ::lateTaggedPng,
      "jpeg" to ::lateTaggedJpeg,
      "little tiff" to { lateTaggedTiff(it, little = true) },
      "big endian tiff" to { lateTaggedTiff(it, little = false) },
      "webp" to ::lateTaggedWebp,
      "boxed jxl" to { lateTaggedJxl(it) },
      "split jxl" to { lateTaggedJxl(it, splitAt = 3) },
    )

  // A file of [size] bytes that [held] hands out ranges of, as a seekable stream or a blob would, fewer bytes when it
  // ends before [size]. Any range outside the file, or longer than a walk may read at once, fails the test.
  private class Fetch(
    private val size: Long,
    private val held: (start: Long, count: Int) -> ByteArray,
  ) {
    var fetched = 0L
      private set
    var reads = 0
      private set
    var largest = 0
      private set

    suspend fun orientation(): PictureOrientation =
      pictureOrientationOf(size) { start, count ->
        if (start < 0 || count < 0 || start > size - count || count > MAX_WALK_BLOCK) {
          fail("read $count bytes at $start of $size")
        }
        reads++
        largest = maxOf(largest, count)
        held(start, count).also { fetched += it.size }
      }

    companion object {
      fun of(
        file: ByteArray,
        size: Long = file.size.toLong(),
      ): Fetch =
        Fetch(size) { start, count ->
          val from = minOf(start, file.size.toLong()).toInt()
          file.copyOfRange(from, minOf(file.size.toLong(), start + count).toInt())
        }
    }
  }

  // A PNG of nothing but an eXIf chunk holding [exif], laid out without the list backed builder since it is large.
  private fun pngHolding(exif: ByteArray): ByteArray {
    val typed = asciiBytes("eXIf") + exif
    return PNG_SIGNATURE + bytes { u32(exif.size.toLong()) } + typed + bytes { u32(crc32Of(typed)) } + IEND_CHUNK
  }

  private fun emptyPngChunk(): ByteArray {
    val type = asciiBytes("tEXt")
    return bytes { u32(0).raw(type).u32(crc32Of(type)) }
  }

  private fun webpOpening(): ByteArray =
    bytes {
      ascii("RIFF").u32(0xFFFFFFF0, little = true).ascii("WEBP")
      webpChunk("VP8X", ByteArray(10).also { it[0] = 0x08 })
    }

  // A little endian BigTIFF whose first directory holds [entry] entries of another tag and then the orientation.
  private fun bigTiffTaggedAtEntry(
    entry: Int,
    orientation: Int,
  ): ByteArray {
    val header = bigTiffHeader(count = entry + 1L)
    val file = header.copyOf(header.size + (entry + 1) * BIG_TIFF_ENTRY + 8)
    for (index in 0..entry) {
      val at = header.size + index * BIG_TIFF_ENTRY
      val tag = if (index == entry) EXIF_ORIENTATION_TAG else 1
      val value = if (index == entry) orientation else 1
      val fields =
        bytes {
          u16(tag, little = true).u16(3, little = true)
          u64(1, little = true)
          u16(value, little = true)
        }
      fields.copyInto(file, at)
    }
    return file
  }

  private companion object {
    // Headers and the tag's own block. Every late tagged file is larger than PAST_THE_HEAD, so this is well short of
    // its pixels.
    const val FETCH_BOUND = 4096
    const val BIG_TIFF_ENTRY = 20

    // One entry past the most a classic directory's two byte count can hold.
    const val BIG_TIFF_TAG_ENTRY = 0x10000
    const val GIGABYTE = 1L shl 30
    val JPEG_START = byteArrayOf(0xFF.toByte(), 0xD8.toByte())
    val IEND_CHUNK = hex("0000000049454E44AE426082")
    val MUTATIONS = intArrayOf(0x01, 0x80, 0xFF)
  }
}
