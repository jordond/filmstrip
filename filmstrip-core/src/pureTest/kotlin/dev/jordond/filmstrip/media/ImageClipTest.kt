package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The image arm on the model: what it makes a clip's duration, and what the builders write when an
 * edit names a photo.
 */
class ImageClipTest {
  @Test
  fun anImageSourceHoldingTheSameStillAndTheSameLengthIsEqual() {
    val a = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)
    val b = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)

    b shouldBe a
    b.hashCode() shouldBe a.hashCode()
  }

  // Sources key the probe cache, so two lengths of the same photo have to stay two entries.
  @Test
  fun twoLengthsOfTheSameStillAreTwoSources() {
    val short = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 2.seconds)
    val long = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)

    setOf<MediaSource>(short, long).size shouldBe 2
  }

  @Test
  fun anUntrimmedImageClipIsAsLongAsItsSourceSaysWithoutProbing() {
    val clip = Clip(MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds))

    clip.duration shouldBe 5.seconds
  }

  @Test
  fun aTrimOnAnImageClipWins() {
    val clip =
      Clip(
        source = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds),
        trim = TimeRange(1.seconds, 3.seconds),
      )

    clip.duration shouldBe 2.seconds
  }

  // What the planner lays for the same clip, so a ruler drawn off this and the exported file agree.
  @Test
  fun anOpenEndedTrimOnAnImageClipRunsToTheEndOfWhatItHolds() {
    val clip =
      Clip(
        source = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds),
        trim = TimeRange.from(1.seconds),
      )

    clip.duration shouldBe 4.seconds
  }

  @Test
  fun aTrimReachingPastAnImageClipStopsWhereTheStillDoes() {
    val clip =
      Clip(
        source = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds),
        trim = TimeRange(1.seconds, 9.seconds),
      )

    clip.duration shouldBe 4.seconds
  }

  @Test
  fun anUntrimmedVideoClipStillNeedsAProbe() {
    val clip = Clip(MediaSource.of("/clips/a.mp4"))

    clip.duration.shouldBeNull()
  }

  // An all-image edit folds its length up from the clips, so a slideshow reports its runtime with
  // nothing probed at all.
  @Test
  fun anAllImageCompositionKnowsHowLongItRunsBeforeAnythingIsProbed() {
    val edit =
      compositionOf {
        image(ImageSource.of("/photos/one.jpg"), 3.seconds)
        image(ImageSource.of("/photos/two.jpg"), 4.seconds)
      }

    edit.duration shouldBe 7.seconds
  }

  @Test
  fun theCompositionBuilderWritesAnImageSourceOntoThePrimaryTrack() {
    val edit = compositionOf { image(ImageSource.of("/photos/one.jpg"), 3.seconds) }

    val source = edit.clips.single().source
    source shouldBe MediaSource.Image(ImageSource.of("/photos/one.jpg"), 3.seconds)
  }

  @Test
  fun theImageBuilderTakesTheSameClipBlockEveryOtherClipTakes() {
    val edit =
      compositionOf {
        image(ImageSource.of("/photos/one.jpg"), 5.seconds) { trim(1.seconds, 3.seconds) }
      }

    val clip = edit.clips.single()
    clip.trim shouldBe TimeRange(1.seconds, 3.seconds)
    clip.duration shouldBe 2.seconds
  }

  @Test
  fun theTrackBuilderWritesAnImageSourceOntoItsOwnTrack() {
    val edit =
      compositionOf {
        clip(MediaSource.of("/clips/a.mp4"))
        track(TrackContent.Video) { image(ImageSource.of("/photos/logo.png"), 2.seconds) }
      }

    val overlay =
      edit.tracks
        .last()
        .clips
        .single()
    overlay.source shouldBe MediaSource.Image(ImageSource.of("/photos/logo.png"), 2.seconds)
    overlay.duration shouldBe 2.seconds
  }

  @Test
  fun anImageSourceDescribesItselfByTheStillItNames() {
    val path = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)
    val bytes = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3)), 5.seconds)

    path.describe() shouldBe "/photos/beach.jpg"
    bytes.describe() shouldBe "bytes[3]"
  }
}
