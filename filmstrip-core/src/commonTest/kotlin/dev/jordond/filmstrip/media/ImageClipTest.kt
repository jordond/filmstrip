package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.CompositionBuilder
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import kotlinx.serialization.json.Json
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration.Companion.seconds

/**
 * The image arm on the model: what it persists as, what it makes a clip's duration, and what the
 * builders write when an edit names a photo.
 */
class ImageClipTest {
  @Test
  fun anImageSourceRoundTripsThroughTheWireFormat() {
    val source: MediaSource = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)

    val json = Json.encodeToString(MediaSource.serializer(), source)

    assertTrue(json.contains("\"image\""), "the arm persists under its own discriminator: $json")
    assertEquals(source, Json.decodeFromString(MediaSource.serializer(), json))
  }

  @Test
  fun anImageSourceHoldingTheSameStillAndTheSameLengthIsEqual() {
    val a = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)
    val b = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)

    assertEquals(a, b)
    assertEquals(a.hashCode(), b.hashCode())
  }

  // Sources key the probe cache, so two lengths of the same photo have to stay two entries.
  @Test
  fun twoLengthsOfTheSameStillAreTwoSources() {
    val short = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 2.seconds)
    val long = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)

    assertEquals(2, setOf<MediaSource>(short, long).size)
  }

  @Test
  fun anUntrimmedImageClipIsAsLongAsItsSourceSaysWithoutProbing() {
    val clip = Clip(MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds))

    assertEquals(5.seconds, clip.duration)
  }

  @Test
  fun aTrimOnAnImageClipWins() {
    val clip =
      Clip(
        source = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds),
        trim = TimeRange(1.seconds, 3.seconds),
      )

    assertEquals(2.seconds, clip.duration)
  }

  @Test
  fun anUntrimmedVideoClipStillNeedsAProbe() {
    val clip = Clip(MediaSource.of("/clips/a.mp4"))

    assertNull(clip.duration)
  }

  // An all-image edit folds its length up from the clips, so a slideshow reports its runtime with
  // nothing probed at all.
  @Test
  fun anAllImageCompositionKnowsHowLongItRunsBeforeAnythingIsProbed() {
    val edit =
      composition {
        image(ImageSource.of("/photos/one.jpg"), 3.seconds)
        image(ImageSource.of("/photos/two.jpg"), 4.seconds)
      }

    assertEquals(7.seconds, edit.duration)
  }

  @Test
  fun theCompositionBuilderWritesAnImageSourceOntoThePrimaryTrack() {
    val edit = composition { image(ImageSource.of("/photos/one.jpg"), 3.seconds) }

    val source = edit.clips.single().source
    assertEquals(MediaSource.Image(ImageSource.of("/photos/one.jpg"), 3.seconds), source)
  }

  @Test
  fun theImageBuilderTakesTheSameClipBlockEveryOtherClipTakes() {
    val edit =
      composition {
        image(ImageSource.of("/photos/one.jpg"), 5.seconds) { trim(1.seconds, 3.seconds) }
      }

    val clip = edit.clips.single()
    assertEquals(TimeRange(1.seconds, 3.seconds), clip.trim)
    assertEquals(2.seconds, clip.duration)
  }

  @Test
  fun theTrackBuilderWritesAnImageSourceOntoItsOwnTrack() {
    val edit =
      composition {
        clip(MediaSource.of("/clips/a.mp4"))
        track(TrackContent.Video) { image(ImageSource.of("/photos/logo.png"), 2.seconds) }
      }

    val overlay =
      edit.tracks
        .last()
        .clips
        .single()
    assertEquals(MediaSource.Image(ImageSource.of("/photos/logo.png"), 2.seconds), overlay.source)
    assertEquals(2.seconds, overlay.duration)
  }

  @Test
  fun anImageSourceDescribesItselfByTheStillItNames() {
    val path = MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 5.seconds)
    val bytes = MediaSource.Image(ImageSource.ofBytes(byteArrayOf(1, 2, 3)), 5.seconds)

    assertEquals("/photos/beach.jpg", path.describe())
    assertEquals("bytes[3]", bytes.describe())
  }

  private fun composition(block: CompositionBuilder.() -> Unit): EditComposition =
    CompositionBuilder().apply(block).build()
}
