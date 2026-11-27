package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.test.MovieBoxes.bareTrack
import dev.jordond.filmstrip.test.MovieBoxes.box
import dev.jordond.filmstrip.test.MovieBoxes.fullBox
import dev.jordond.filmstrip.test.MovieBoxes.handler
import dev.jordond.filmstrip.test.MovieBoxes.mediaHeader
import dev.jordond.filmstrip.test.MovieBoxes.movieHeader
import dev.jordond.filmstrip.test.MovieBoxes.trackExtends
import dev.jordond.filmstrip.test.MovieBoxes.trackFragment
import dev.jordond.filmstrip.test.MovieBoxes.trackHeader
import dev.jordond.filmstrip.test.MovieBoxes.u32
import dev.jordond.filmstrip.test.PrimedAudio
import dev.jordond.filmstrip.test.RunEntries
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The length a fragmented MP4 plays for, read off its fragments, against files built box by box so every tick and
 * default is known.
 */
class FragmentDurationTest {
  @Test
  fun aFragmentedFilePlaysForItsFragmentsLessItsStartEdit() =
    runTest {
      val duration = durationOf(PrimedAudio.fragmentedMp4())

      duration shouldBe PrimedAudio.presentedLength
      duration shouldNotBe PrimedAudio.mediaLength
    }

  @Test
  fun aFragmentedFileWithNoEditPlaysForEveryTickOfItsFragments() =
    runTest {
      durationOf(PrimedAudio.fragmentedMp4(edit = false)) shouldBe PrimedAudio.mediaLength
    }

  @Test
  fun aFragmentedFileInOneFragmentPlaysAsLongAsOneInMany() =
    runTest {
      durationOf(PrimedAudio.fragmentedMp4(fragments = 1)) shouldBe PrimedAudio.presentedLength
    }

  @Test
  fun aFragmentedFileWithNoFragmentsHasNoLength() =
    runTest {
      val file = fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 1000)), trexDuration = 100)

      durationOf(file).shouldBeNull()
    }

  @Test
  fun aFragmentedMovieHeaderLengthIsNotTaken() =
    runTest {
      val header = movieHeader(timescale = 1000, duration = 2000)
      val file = movieFile(box("mvex", ByteArray(0)) + header + track(2.seconds))

      durationOf(file).shouldBeNull()
    }

  @Test
  fun aFragmentedFilePlaysForItsLongestTrack() =
    runTest {
      val tracks = listOf(fragmentTrack(id = 1, timescale = 1000), fragmentTrack(id = 2, timescale = 48_000))
      val file =
        fragmentedFile(tracks, trexDuration = 0) +
          moof(trackFragment(1, baseTime = 0, runs = listOf(RunEntries(3, durations = listOf(1000, 1000, 1000))))) +
          moof(trackFragment(2, baseTime = 0, runs = listOf(RunEntries(2, durations = listOf(48_000, 48_000)))))

      durationOf(file) shouldBe 3.seconds
    }

  // Only the third fragment carries a tfdt, so a reading that took only the tfdts or only the running sum would miss.
  @Test
  fun aFragmentWithNoDecodeTimeCarriesOnFromTheOneBefore() =
    runTest {
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 1000)), trexDuration = 100) +
          moof(trackFragment(1, baseTime = null, runs = listOf(RunEntries(10)))) +
          moof(trackFragment(1, baseTime = null, runs = listOf(RunEntries(10)))) +
          moof(trackFragment(1, baseTime = 5000, runs = listOf(RunEntries(10)))) +
          moof(trackFragment(1, baseTime = null, runs = listOf(RunEntries(5))))

      durationOf(file) shouldBe 6.5.seconds
    }

  @Test
  fun aFragmentHeaderDurationOverridesTheTrackDefault() =
    runTest {
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 1000)), trexDuration = 100) +
          moof(trackFragment(1, baseTime = null, runs = listOf(RunEntries(10)))) +
          moof(trackFragment(1, baseTime = null, headerDuration = 50, runs = listOf(RunEntries(10))))

      durationOf(file) shouldBe 1.5.seconds
    }

  @Test
  fun anEmptyEditAheadOfAFragmentedTrackAddsItsDelay() =
    runTest {
      val edits = editList(500L to -1L, 0L to 0L)
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 2000, edits = edits)), trexDuration = 1000) +
          moof(trackFragment(1, baseTime = 0, runs = listOf(RunEntries(2))))

      durationOf(file) shouldBe 1.5.seconds
    }

  @Test
  fun aFragmentForATrackTheMovieDoesNotHaveIsSkipped() =
    runTest {
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 1000)), trexDuration = 100) +
          moof(trackFragment(1, baseTime = 0, runs = listOf(RunEntries(10)))) +
          moof(trackFragment(9, baseTime = 0, headerDuration = 1000, runs = listOf(RunEntries(60))))

      durationOf(file) shouldBe 1.seconds
    }

  // A count and a default of 2^32 - 1 multiply past a Long and wrap negative, and the next run's three samples bring
  // the sum back to one second, so a reading that only checked the total would take it.
  @Test
  fun aRunWhoseSamplesOverflowGivesNoLength() =
    runTest {
      val most = 0xFFFFFFFFL
      val runs = listOf(RunEntries(most.toInt()), RunEntries(3))
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = most)), trexDuration = most) +
          moof(trackFragment(1, baseTime = null, runs = runs))

      durationOf(file).shouldBeNull()
    }

  @Test
  fun anEditOpeningBeforeTheMediaGivesNoLength() =
    runTest {
      val file =
        fragmentedFile(
          listOf(fragmentTrack(id = 1, timescale = 1000, edits = editList(0L to -2L))),
          trexDuration = 100,
        ) +
          moof(trackFragment(1, baseTime = 0, runs = listOf(RunEntries(10))))

      durationOf(file).shouldBeNull()
    }

  @Test
  fun samplesInTheMovieCountAheadOfItsFragments() =
    runTest {
      val file =
        fragmentedFile(listOf(fragmentTrack(id = 1, timescale = 1000, mediaTicks = 1000)), trexDuration = 100) +
          moof(trackFragment(1, baseTime = null, runs = listOf(RunEntries(5))))

      durationOf(file) shouldBe 1.5.seconds
    }

  private fun track(length: Duration): ByteArray = bareTrack(length.inWholeMilliseconds)

  private fun fragmentTrack(
    id: Long,
    timescale: Long,
    mediaTicks: Long = 0,
    edits: ByteArray = ByteArray(0),
  ): ByteArray =
    box("trak", trackHeader(0, id = id) + edits + box("mdia", mediaHeader(timescale, mediaTicks) + handler("soun")))

  private fun fragmentedFile(
    tracks: List<ByteArray>,
    trexDuration: Long,
  ): ByteArray {
    val extends = tracks.indices.fold(ByteArray(0)) { all, index -> all + trackExtends(index + 1L, trexDuration) }
    return movieFile(
      movieHeader(timescale = 1000, duration = 0) + tracks.reduce(ByteArray::plus) + box("mvex", extends),
    )
  }

  private fun moof(vararg fragments: ByteArray): ByteArray =
    box("moof", fullBox("mfhd", 0, u32(1)) + fragments.reduce(ByteArray::plus))
}
