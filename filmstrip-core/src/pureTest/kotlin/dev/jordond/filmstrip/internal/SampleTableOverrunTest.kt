package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.lastSampleEndOf
import dev.jordond.filmstrip.test.MovieBoxes.ascii
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.fullBox
import dev.jordond.filmstrip.test.MovieBoxes.u32
import dev.jordond.filmstrip.test.MovieBoxes.u64
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The check every prober runs to refuse a file cut short after its `moov` was written, against files built box by box
 * so where each last sample ends is known.
 */
class SampleTableOverrunTest {
  @Test
  fun aWholeFileIsMissingNothing() =
    runTest {
      val file = PrimedAudio.mp4()

      lastSampleEndOf(file) shouldBe file.size.toLong()
      overrunOf(file).shouldBeNull()
    }

  @Test
  fun aFileOneByteShortIsMissingOneByte() =
    runTest {
      val file = PrimedAudio.mp4()

      overrunOf(file.copyOf(file.size - 1)) shouldBe 1L
    }

  @Test
  fun aFileCutInHalfIsMissingTheOtherHalf() =
    runTest {
      val file = PrimedAudio.mp4()
      val kept = file.size / 2

      overrunOf(file.copyOf(kept)) shouldBe (file.size - kept).toLong()
    }

  @Test
  fun aMovieAfterItsMediaStillFindsTheLastSample() =
    runTest {
      val file = PrimedAudio.mp4(moovLast = true)

      lastSampleEndOf(file) shouldBe (file.size - moovLength(file)).toLong()
    }

  @Test
  fun trailingBytesPastTheLastSampleAreNotAnOverrun() =
    runTest {
      overrunOf(PrimedAudio.mp4() + box("free", ByteArray(32))).shouldBeNull()
    }

  @Test
  fun sampleSizesListedOneByOneAddUpOverTheLastChunk() =
    runTest {
      val sizes = fullBox("stsz", 0, u32(0) + u32(5) + u32(10) + u32(20) + u32(30) + u32(40) + u32(50))
      val file = movie(track(sizes, runs(1 to 2, 2 to 3), offsets(1000, 4000)))

      lastSampleEndOf(file) shouldBe 4000L + 30 + 40 + 50
    }

  @Test
  fun wideChunkOffsetsAreRead() =
    runTest {
      val wide = 5L shl 32
      val chunks = fullBox("co64", 0, u32(1) + u64(wide))
      val file = movie(track(fullBox("stsz", 0, u32(8) + u32(4)), runs(1 to 4), chunks))

      lastSampleEndOf(file) shouldBe wide + 32
    }

  @Test
  fun compactSampleSizesOfEveryFieldWidthAreRead() =
    runTest {
      val nibbles = compactSizes(4, byteArrayOf(0x12, 0x34, 0x50))
      val bytes = compactSizes(8, byteArrayOf(1, 2, 3, 4, 5))
      val shorts = compactSizes(16, u16s(1, 2, 3, 4, 500))

      lastSampleEndOf(movie(track(nibbles, runs(1 to 3), offsets(100)))) shouldBe 100L + 3 + 4 + 5
      lastSampleEndOf(movie(track(bytes, runs(1 to 2), offsets(100)))) shouldBe 100L + 4 + 5
      lastSampleEndOf(movie(track(shorts, runs(1 to 2), offsets(100)))) shouldBe 100L + 4 + 500
    }

  @Test
  fun theFurthestTrackDecides() =
    runTest {
      val near = track(fullBox("stsz", 0, u32(10) + u32(2)), runs(1 to 2), offsets(100))
      val far = track(fullBox("stsz", 0, u32(10) + u32(3)), runs(1 to 3), offsets(900))

      lastSampleEndOf(movie(near + far)) shouldBe 930L
      lastSampleEndOf(movie(far + near)) shouldBe 930L
    }

  @Test
  fun aTrackWithNoSamplesIsSkipped() =
    runTest {
      val empty = track(fullBox("stsz", 0, u32(0) + u32(0)), fullBox("stsc", 0, u32(0)), fullBox("stco", 0, u32(0)))
      val full = track(fullBox("stsz", 0, u32(10) + u32(1)), runs(1 to 1), offsets(200))

      lastSampleEndOf(movie(empty)).shouldBeNull()
      lastSampleEndOf(movie(empty + full)) shouldBe 210L
    }

  @Test
  fun aTrackWhoseMediaSitsInAnotherFileIsSkipped() =
    runTest {
      val elsewhere = fullBox("url ", 0, ascii("other.mov") + byteArrayOf(0), flags = 0)
      val file = movie(track(fullBox("stsz", 0, u32(10) + u32(1)), runs(1 to 1), offsets(1 shl 30), elsewhere))

      lastSampleEndOf(file).shouldBeNull()
    }

  @Test
  fun anEndPastWhatALongHoldsIsNotTrusted() =
    runTest {
      val huge = fullBox("stsz", 0, u32(0xFFFFFFFFL) + u32(0xFFFFFFFFL))
      val wide = fullBox("co64", 0, u32(1) + u64(Long.MAX_VALUE - 10))

      lastSampleEndOf(movie(track(huge, runs(1 to -1), offsets(100)))).shouldBeNull()
      lastSampleEndOf(movie(track(fullBox("stsz", 0, u32(8) + u32(4)), runs(1 to 4), wide))).shouldBeNull()
      val negative = fullBox("co64", 0, u32(1) + u64(-1))
      lastSampleEndOf(movie(track(fullBox("stsz", 0, u32(8) + u32(4)), runs(1 to 4), negative))).shouldBeNull()
    }

  @Test
  fun aFileWithNoMovieHasNoLastSample() =
    runTest {
      lastSampleEndOf(box("ftyp", ascii("isom") + u32(0)) + box("mdat", ByteArray(64))).shouldBeNull()
    }

  @Test
  fun theOverrunIsTheBytesPastTheEnd() {
    sampleTableOverrun(lastSampleEnd = 1000, byteLength = 1000).shouldBeNull()
    sampleTableOverrun(lastSampleEnd = 1000, byteLength = 1200).shouldBeNull()
    sampleTableOverrun(lastSampleEnd = 1000, byteLength = 999) shouldBe 1L
    sampleTableOverrun(lastSampleEnd = 1000, byteLength = 400) shouldBe 600L
  }

  private suspend fun lastSampleEndOf(file: ByteArray): Long? = lastSampleEndOf(file.size.toLong(), file.reader())

  private suspend fun overrunOf(file: ByteArray): Long? = sampleTableOverrunOf(file.size.toLong(), file.reader())

  private fun ByteArray.reader(): suspend (Long, Int) -> ByteArray =
    { start, count -> copyOfRange(start.toInt(), minOf(size, start.toInt() + count)) }

  // Where the moov PrimedAudio writes last starts, read off its size field.
  private fun moovLength(file: ByteArray): Int {
    val moov = ascii("moov")
    val type = (0..file.size - 4).last { at -> moov.indices.all { file[at + it] == moov[it] } }
    return u32At(file, type - 4)
  }

  private fun u32At(
    bytes: ByteArray,
    at: Int,
  ): Int = (0 until 4).fold(0) { value, index -> (value shl 8) or (bytes[at + index].toInt() and 0xFF) }

  private fun movie(tracks: ByteArray): ByteArray = box("ftyp", ascii("isom") + u32(0)) + box("moov", tracks)

  private fun track(
    sizes: ByteArray,
    runs: ByteArray,
    chunks: ByteArray,
    reference: ByteArray = fullBox("url ", 0, ByteArray(0), flags = 1),
  ): ByteArray {
    val dinf = box("dinf", fullBox("dref", 0, u32(1) + reference))
    val stbl = box("stbl", fullBox("stsd", 0, u32(0)) + sizes + runs + chunks)
    return box("trak", box("mdia", box("minf", dinf + stbl)))
  }

  private fun runs(vararg firstChunkToSamples: Pair<Int, Int>): ByteArray =
    fullBox(
      "stsc",
      0,
      u32(firstChunkToSamples.size.toLong()) +
        firstChunkToSamples.fold(ByteArray(0)) { all, (first, samples) ->
          all + u32(first.toLong()) + u32(samples.toLong()) + u32(1)
        },
    )

  private fun offsets(vararg chunks: Long): ByteArray =
    fullBox("stco", 0, u32(chunks.size.toLong()) + chunks.fold(ByteArray(0)) { all, offset -> all + u32(offset) })

  private fun offsets(vararg chunks: Int): ByteArray = offsets(*chunks.map { it.toLong() }.toLongArray())

  // An stz2 of fieldSize bits per sample over the packed entries, holding five samples.
  private fun compactSizes(
    fieldSize: Int,
    packed: ByteArray,
  ): ByteArray = fullBox("stz2", 0, byteArrayOf(0, 0, 0, fieldSize.toByte()) + u32(5) + packed)

  private fun u16s(vararg values: Int): ByteArray =
    values.fold(ByteArray(0)) { all, value -> all + byteArrayOf((value shr 8).toByte(), value.toByte()) }
}
