package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.color.CodestreamSignal
import dev.jordond.filmstrip.picture.heif.SpsShape
import dev.jordond.filmstrip.picture.heif.hvccOf
import dev.jordond.filmstrip.picture.heif.vui
import dev.jordond.filmstrip.test.MovieBoxes.ascii
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.handler
import dev.jordond.filmstrip.test.MovieBoxes.mediaHeader
import dev.jordond.filmstrip.test.MovieBoxes.movieHeader
import dev.jordond.filmstrip.test.MovieBoxes.trackHeader
import dev.jordond.filmstrip.test.MovieBoxes.u32
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

/**
 * The track headers every MP4 prober reads a track's id and default flag from, against files built box by box.
 */
class TrackHeadersTest {
  @Test
  fun everyTrackIsListedInMovieOrderWithItsIdFlagAndHandler() =
    runTest {
      val file =
        movieFile(
          movieHeader(timescale = 1000, duration = 2000) +
            track(id = 1, enabled = true, media = "vide") +
            track(id = 2, enabled = false, media = "soun") +
            track(id = 3, enabled = true, media = "soun") +
            track(id = 4, enabled = false, media = "soun"),
        )

      val tracks = headersOf(file).shouldNotBeNull()

      tracks.map { it.id } shouldBe listOf(1L, 2L, 3L, 4L)
      tracks.map { it.isEnabled } shouldBe listOf(true, false, true, false)
      tracks.map { it.handler } shouldBe listOf(VIDE, SOUN, SOUN, SOUN)
    }

  @Test
  fun eachTrackReadsTheLanguageItsMediaHeaderPacks() =
    runTest {
      val file =
        movieFile(
          movieHeader(timescale = 1000, duration = 2000) +
            track(id = 2, enabled = false, media = "soun", language = "eng") +
            track(id = 3, enabled = true, media = "soun", language = "fra", version = 1) +
            track(id = 4, enabled = false, media = "soun", language = "und"),
        )

      headersOf(file).shouldNotBeNull().map { it.language } shouldBe listOf("eng", "fra", "und")
    }

  // QuickTime writes a Macintosh language number below 0x400 in place of a packed code.
  @Test
  fun aMacintoshLanguageNumberAndAMissingMediaHeaderReadNoLanguage() =
    runTest {
      val mac = box("trak", trackHeader(2000, id = 2) + box("mdia", mediaHeader(1000, 2000, languageCode = 0)))
      val bare = box("trak", trackHeader(2000, id = 3) + box("mdia", handler("soun")))

      headersOf(movieFile(movieHeader(1000, 2000) + mac + bare)).shouldNotBeNull().map { it.language } shouldBe
        listOf(null, null)
    }

  @Test
  fun aLongTrackHeaderReadsItsIdPastTheWideTimes() =
    runTest {
      val file =
        movieFile(movieHeader(timescale = 1000, duration = 2000) + box("trak", trackHeader(2000, version = 1, id = 7)))

      headersOf(file).shouldNotBeNull().single().id shouldBe 7L
    }

  @Test
  fun aTrackWithNoMediaBoxHasNoHandler() =
    runTest {
      val file = movieFile(movieHeader(timescale = 1000, duration = 2000) + box("trak", trackHeader(2000, id = 2)))

      headersOf(file)
        .shouldNotBeNull()
        .single()
        .handler
        .shouldBeNull()
    }

  @Test
  fun aTrackWithNoHeaderGivesNone() =
    runTest {
      val file = movieFile(movieHeader(timescale = 1000, duration = 2000) + box("trak", box("mdia", handler("soun"))))

      headersOf(file).shouldBeNull()
    }

  @Test
  fun aMovieOfMoreTracksThanTheCeilingGivesNone() =
    runTest {
      val one = box("trak", trackHeader(2000, id = 1))
      val atCeiling = movieFile(movieHeader(1000, 2000) + List(MAX_TRACK_HEADERS) { one }.flatten())
      val past = movieFile(movieHeader(1000, 2000) + List(MAX_TRACK_HEADERS + 1) { one }.flatten())

      headersOf(atCeiling).shouldNotBeNull() shouldHaveSize MAX_TRACK_HEADERS
      headersOf(past).shouldBeNull()
    }

  @Test
  fun aFileThatIsNotAMovieGivesNone() =
    runTest {
      headersOf(ascii("ID3") + ByteArray(64)).shouldBeNull()
    }

  @Test
  fun aVideoSampleEntryReadsTheColorItsColorBoxStates() =
    runTest {
      val colr = box("colr", ascii("nclx") + u16(9) + u16(16) + u16(9) + byteArrayOf(0))

      headersOf(movieFile(movieHeader(1000, 2000) + videoTrack(colr))).shouldNotBeNull().single().signal shouldBe
        CodestreamSignal(9, 16, 9, false)
    }

  @Test
  fun aVideoSampleEntryWithNoColorBoxReadsTheColorItsParameterSetStates() =
    runTest {
      val sps = SpsShape(vui = vui(fullRange = false, color = Triple(9, 18, 9))).nal()

      headersOf(movieFile(movieHeader(1000, 2000) + videoTrack(hvccOf(sps)))).shouldNotBeNull().single().signal shouldBe
        CodestreamSignal(9, 18, 9, false)
    }

  @Test
  fun aSampleEntryThatDoesNotParseStatesNoColorAndKeepsTheTrack() =
    runTest {
      val torn = box("colr", ascii("nclx") + u16(9))

      val track = headersOf(movieFile(movieHeader(1000, 2000) + videoTrack(torn))).shouldNotBeNull().single()

      track.id shouldBe 1L
      track.signal.shouldBeNull()
    }

  private fun videoTrack(vararg children: ByteArray): ByteArray {
    val entry = box("hvc1", ByteArray(VISUAL_ENTRY_FIELDS) + children.toList().flatten())
    val table = box("stbl", box("stsd", u32(0) + u32(1) + entry))
    return box(
      "trak",
      trackHeader(2000, id = 1) + box("mdia", mediaHeader(1000, 2000) + handler("vide") + box("minf", table)),
    )
  }

  private fun u16(value: Int): ByteArray = byteArrayOf((value shr 8).toByte(), value.toByte())

  private fun track(
    id: Long,
    enabled: Boolean,
    media: String,
    language: String = "und",
    version: Int = 0,
  ): ByteArray =
    box(
      "trak",
      trackHeader(2000, id = id, enabled = enabled) +
        box("mdia", mediaHeader(1000, 2000, version = version, language = language) + handler(media)),
    )

  private fun List<ByteArray>.flatten(): ByteArray = fold(ByteArray(0)) { all, one -> all + one }

  private fun movieFile(children: ByteArray): ByteArray = FTYP + box("moov", children)

  private suspend fun headersOf(file: ByteArray): List<TrackHeader>? =
    trackHeadersOf(file.size.toLong()) { start, count ->
      file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count))
    }

  private companion object {
    val FTYP = box("ftyp", ascii("isom") + u32(0) + ascii("isom"))
    val VIDE = ascii("vide").fold(0) { code, byte -> (code shl 8) or byte.toInt() }
    val SOUN = ascii("soun").fold(0) { code, byte -> (code shl 8) or byte.toInt() }
    const val VISUAL_ENTRY_FIELDS = 78
  }
}
