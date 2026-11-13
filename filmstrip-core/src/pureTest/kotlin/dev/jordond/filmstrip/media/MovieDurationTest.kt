package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.picture.MAX_WALK_READS
import dev.jordond.filmstrip.test.MovieBoxes.ascii
import dev.jordond.filmstrip.test.MovieBoxes.bareTrack
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.fullBox
import dev.jordond.filmstrip.test.MovieBoxes.handler
import dev.jordond.filmstrip.test.MovieBoxes.mediaHeader
import dev.jordond.filmstrip.test.MovieBoxes.movieHeader
import dev.jordond.filmstrip.test.MovieBoxes.trackHeader
import dev.jordond.filmstrip.test.MovieBoxes.u32
import dev.jordond.filmstrip.test.MovieBoxes.u64
import dev.jordond.filmstrip.test.PrimedAudio
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.days
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.nanoseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The movie header read every probe takes an MP4's length from, against files built box by box so every tick and
 * timescale is known.
 */
class MovieDurationTest {
  @Test
  fun aTrackThatOpensOnAnEncoderDelayCountsOnlyWhatItsEditListPresents() =
    runTest {
      val duration = durationOf(PrimedAudio.mp4())

      duration shouldBe PrimedAudio.presentedLength
      duration shouldNotBe PrimedAudio.mediaLength
    }

  @Test
  fun aMovieHeaderAfterTheMediaDataIsStillFound() =
    runTest {
      durationOf(PrimedAudio.mp4(moovLast = true)) shouldBe PrimedAudio.presentedLength
    }

  // A tick count that does not divide its timescale, so a reading that dropped the remainder or rounded it the wrong
  // way would miss.
  @Test
  fun aLongHeaderCountsTicksOfItsOwnTimescale() =
    runTest {
      val ticks = 4_401_234L
      val file = movieFile(movieHeader(timescale = 600, duration = ticks, version = 1) + bareTrack(ticks))

      durationOf(file) shouldBe 7335.seconds + 390_000_000.nanoseconds
    }

  @Test
  fun aFractionOfANanosecondRoundsToTheNearest() =
    runTest {
      val file = movieFile(movieHeader(timescale = 3, duration = 2) + bareTrack(2))

      durationOf(file) shouldBe 666_666_667.nanoseconds
    }

  // The remainder times a billion only fits a Long because it is below the timescale, which is at most 32 bits.
  @Test
  fun theWidestTimescaleRoundsWithoutOverflowing() =
    runTest {
      val timescale = 0xFFFFFFFFL
      val ticks = 3 * timescale + 0x80000000L
      val file = movieFile(movieHeader(timescale, ticks, version = 1) + bareTrack(ticks, version = 1))

      durationOf(file) shouldBe 3.5.seconds
    }

  @Test
  fun aFragmentedFileHasNoLengthInItsHeader() =
    runTest {
      val file = movieFile(movieHeader(timescale = 1000, duration = 0) + box("mvex", ByteArray(0)))

      durationOf(file).shouldBeNull()
    }

  @Test
  fun aFragmentedFileIsCaughtWhenItsExtendsBoxComesFirst() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)
      val file = movieFile(box("mvex", ByteArray(0)) + header + track(2.seconds))

      durationOf(file).shouldBeNull()
    }

  @Test
  fun aHeaderThatDoesNotKnowItsDurationGivesNone() =
    runTest {
      durationOf(movieFile(movieHeader(timescale = 1000, duration = 0xFFFFFFFFL) + track(5.days))).shouldBeNull()
      durationOf(movieFile(movieHeader(timescale = 1000, duration = -1, version = 1) + track(5.days))).shouldBeNull()
      durationOf(movieFile(movieHeader(timescale = 0, duration = 1000) + track(5.days))).shouldBeNull()
    }

  @Test
  fun aMovieWithNoHeaderGivesNone() =
    runTest {
      durationOf(movieFile(track(2.seconds))).shouldBeNull()
    }

  @Test
  fun aMovieWithNoTrackToVouchForItsHeaderGivesNone() =
    runTest {
      durationOf(movieFile(movieHeader(timescale = 1000, duration = 2000))).shouldBeNull()
    }

  @Test
  fun aHeaderLongerThanItsLongestTrackGivesNone() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)

      durationOf(movieFile(header + track(1.seconds) + track(1500.milliseconds))).shouldBeNull()
      durationOf(movieFile(header + track(1.seconds) + track(2.seconds))) shouldBe 2.seconds
    }

  // A muxer can round the movie length and each track's length into the movie timescale apart, so a header a tick past
  // its longest track is what one writes, and two ticks past is not.
  @Test
  fun aHeaderRoundedUpByOneOfItsTicksIsKept() =
    runTest {
      val roundedUp = movieHeader(timescale = 1000, duration = 7334)
      val twoTicksOver = movieHeader(timescale = 1000, duration = 7335)

      durationOf(movieFile(roundedUp + bareTrack(7333))) shouldBe 7334.milliseconds
      durationOf(movieFile(twoTicksOver + bareTrack(7333))).shouldBeNull()
    }

  // The empty edit puts a second of nothing ahead of the track's two seconds of media, so the movie runs past every
  // track's samples and only the track header counts the delay.
  @Test
  fun aTrackAnEmptyEditDelaysCountsTheDelay() =
    runTest {
      val elst = fullBox("elst", 0, u32(2) + u32(1000) + u32(-1) + u32(RATE_ONE) + u32(2000) + u32(0) + u32(RATE_ONE))
      val trak = box("trak", trackHeader(3000) + box("edts", elst) + box("mdia", mediaHeader(1000, 2000)))

      durationOf(movieFile(movieHeader(timescale = 1000, duration = 3000) + trak)) shouldBe 3.seconds
    }

  @Test
  fun aHeaderLongerThanItsLongestTrackHeaderGivesNoneEvenWhenTheMediaRunsAsLong() =
    runTest {
      val trak = box("trak", trackHeader(2000) + box("mdia", mediaHeader(1000, 3000)))

      durationOf(movieFile(movieHeader(timescale = 1000, duration = 3000) + trak)).shouldBeNull()
    }

  @Test
  fun aLongTrackHeaderCountsItsDurationInFull() =
    runTest {
      val header = movieHeader(timescale = 48_000, duration = 0x1_0000_0000L, version = 1)

      durationOf(movieFile(header + bareTrack(0x1_0000_0000L, version = 1))) shouldBe
        89_478.seconds + 485_333_333.nanoseconds
      durationOf(movieFile(header + bareTrack(0x8000_0000L, version = 1))).shouldBeNull()
    }

  @Test
  fun aTrackHeaderThatDoesNotKnowItsDurationVouchesForNothing() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)

      durationOf(movieFile(header + bareTrack(0xFFFFFFFFL))).shouldBeNull()
      durationOf(movieFile(header + bareTrack(-1, version = 1))).shouldBeNull()
      durationOf(movieFile(header + bareTrack(0xFFFFFFFFL) + track(2.seconds))) shouldBe 2.seconds
    }

  @Test
  fun aHeaderPastTheCeilingGivesNone() =
    runTest {
      val atCeiling = MAX_MOVIE_LENGTH.inWholeSeconds
      val header = { seconds: Long -> movieHeader(timescale = 1, duration = seconds) }

      durationOf(movieFile(header(atCeiling) + bareTrack(atCeiling))) shouldBe MAX_MOVIE_LENGTH
      durationOf(movieFile(header(atCeiling + 1) + bareTrack(atCeiling + 1))).shouldBeNull()
    }

  @Test
  fun aBoxWithA64BitSizeAheadOfTheMovieIsSteppedOver() =
    runTest {
      val payload = ByteArray(24)
      val wide = u32(1) + ascii("free") + u64(16L + payload.size) + payload
      val file = movieFile(movieHeader(timescale = 1000, duration = 2000) + track(2.seconds), ahead = wide)

      durationOf(file) shouldBe 2.seconds
    }

  @Test
  fun aMovieOfSizeZeroRunsToTheEndOfTheFile() =
    runTest {
      val children = movieHeader(timescale = 1000, duration = 2000) + track(2.seconds)
      val file = FTYP + u32(0) + ascii("moov") + children

      durationOf(file) shouldBe 2.seconds
    }

  @Test
  fun aChildDeclaringMoreThanTheMovieHoldsGivesNone() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)
      val overlong = u32(header.size + 64L) + header.copyOfRange(4, header.size)

      durationOf(movieFile(overlong + track(2.seconds))).shouldBeNull()
    }

  @Test
  fun aChildWithA64BitSizeGivesNone() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)
      val wide = u32(1) + ascii("mvhd") + u64(16L + header.size - 8) + header.copyOfRange(8, header.size)

      durationOf(movieFile(wide + track(2.seconds))).shouldBeNull()
    }

  @Test
  fun aFileOfEndlessSmallBoxesStopsAtTheReadBudget() =
    runTest {
      val movie = movieFile(movieHeader(timescale = 1000, duration = 2000) + track(2.seconds))
      // Each filler box is longer than a walk reads ahead, so stepping over one costs a read of its own.
      val filler = 72
      val fillerEnd = filler.toLong() * (MAX_WALK_READS + 10)
      val size = fillerEnd + movie.size
      var reads = 0

      val duration =
        movieDurationOf(size) { start, count ->
          reads++
          ByteArray(minOf(count.toLong(), size - start).toInt()) { index ->
            val at = start + index
            if (at >= fillerEnd) movie[(at - fillerEnd).toInt()] else fillerByte(at % filler)
          }
        }

      duration.shouldBeNull()
      reads shouldBe MAX_WALK_READS
    }

  @Test
  fun aFileThatIsNotAMovieGivesNone() =
    runTest {
      durationOf(ascii("ID3") + ByteArray(64)).shouldBeNull()
      durationOf(PrimedAudio.mp4().copyOf(40)).shouldBeNull()
    }

  @Test
  fun theAudioTracksOfAMovieAreNamedByTheirTrackHeaders() =
    runTest {
      val file = movieFile(MOVIE_HEADER + media(1, "vide") + media(5, "soun") + media(9, "soun") + media(12, "soun"))

      audioIdsOf(file, count = 3).shouldContainExactly(AudioTrackId(5), AudioTrackId(9), AudioTrackId(12))
    }

  @Test
  fun theAudioTracksOfAFileThatIsNotAMovieAreNamedByTheirPlace() =
    runTest {
      val ids = audioIdsOf(ascii("ID3") + ByteArray(64), count = 3)

      ids.shouldContainExactly(AudioTrackId(1), AudioTrackId(2), AudioTrackId(3))
      ids shouldBe List(3) { audioTrackIdOf(isMovie = false, trackId = null, ordinal = it) }
    }

  // A reader that lists fewer audio tracks than the movie has sound headers is paired with the headers by place.
  @Test
  fun fewerTracksThanSoundHeadersTakeTheFirstHeaders() =
    runTest {
      val file = movieFile(MOVIE_HEADER + media(1, "vide") + media(5, "soun") + media(9, "soun") + media(12, "soun"))

      audioIdsOf(file, count = 2).shouldContainExactly(AudioTrackId(5), AudioTrackId(9))
    }

  @Test
  fun aTrackPastTheLastSoundHeaderIsNamedByItsPlace() =
    runTest {
      val file = movieFile(MOVIE_HEADER + media(1, "vide") + media(5, "soun") + media(9, "soun"))

      audioIdsOf(file, count = 3).shouldContainExactly(
        AudioTrackId(5),
        AudioTrackId(9),
        audioTrackIdOf(isMovie = true, trackId = null, ordinal = 2),
      )
    }

  @Test
  fun aSoundHeaderWithTheReservedIdZeroIsNamedByItsPlace() =
    runTest {
      val file = movieFile(MOVIE_HEADER + media(1, "vide") + media(5, "soun") + media(0, "soun") + media(12, "soun"))

      audioIdsOf(file, count = 3).shouldContainExactly(AudioTrackId(5), AudioTrackId(2), AudioTrackId(12))
    }

  private fun media(
    id: Long,
    type: String,
  ): ByteArray = box("trak", trackHeader(2000, id = id) + box("mdia", mediaHeader(1000, 2000) + handler(type)))

  private suspend fun audioIdsOf(
    file: ByteArray,
    count: Int,
  ): List<AudioTrackId> =
    movieAudioTrackIdsOf(
      file.size.toLong(),
      { start, length -> file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + length)) },
      count,
    )

  private fun track(length: Duration): ByteArray = bareTrack(length.inWholeMilliseconds)

  private fun movieFile(
    children: ByteArray,
    ahead: ByteArray = ByteArray(0),
  ): ByteArray = FTYP + ahead + box("moov", children)

  private suspend fun durationOf(file: ByteArray): Duration? =
    movieDurationOf(file.size.toLong()) { start, count ->
      file.copyOfRange(start.toInt(), minOf(file.size, start.toInt() + count))
    }

  private companion object {
    val FTYP = box("ftyp", ascii("isom") + u32(0) + ascii("isom"))
    val MOVIE_HEADER = movieHeader(timescale = 1000, duration = 2000)
    val FILLER_HEADER = u32(72) + ascii("free")
    const val RATE_ONE = 0x00010000L

    fun fillerByte(at: Long): Byte = FILLER_HEADER.getOrElse(at.toInt()) { 0 }
  }
}
