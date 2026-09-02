package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSTemporaryDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Photos on the timeline, exported by AVFoundation onto a real file and read back frame by frame.
 *
 * A still holds no track of its own, so it takes its slot from a generated segment and the filter
 * handler draws the photo over it. The photo is a flat colour, which is what tells a frame drawn
 * from it apart from a frame drawn from a generated video fixture wherever it is sampled.
 *
 * Skipped where a case needs a video fixture and the build had no host ffmpeg to make one.
 */
@OptIn(ExperimentalForeignApi::class, ExperimentalFilmstripApi::class)
class AppleImageExportTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  // The case that fails outright without a segment of its own: a composition of nothing but empty
  // ranges has no duration and AVFoundation will not open it.
  @Test
  fun `holds an image-only composition for the length it declares`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("image-only")
      val success = exported(composition(photoClip(PHOTO)), ExportSpec(), output)

      success.info.duration shouldBeCloseTo PHOTO
      assertNotNull(success.info.video, "an image-only export wrote no video track")
      success.info.video?.displaySize shouldBe PHOTO_SIZE

      remove(output)
    }

  @Test
  fun `draws the photo over the whole frame of an image-only composition`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("image-only-pixels")
      exported(composition(photoClip(PHOTO)), ExportSpec(), output)

      val frame = frameOf(output, PHOTO * MIDDLE)
      assertClose(frame.average(0.5f, 0.5f), PHOTO_COLOR, "the photo's centre")
      assertClose(frame.average(0.1f, 0.1f), PHOTO_COLOR, "the photo's top left")
      assertClose(frame.average(0.9f, 0.9f), PHOTO_COLOR, "the photo's bottom right")

      remove(output)
    }

  // The other case the empty range loses: a trailing empty range is discarded, so the composition
  // used to end where the last video clip did and the photo's time went with it.
  @Test
  fun `keeps a photo's time when it is last in the sequence`() =
    runTest(timeout = TIMEOUT) {
      val clip = fixture("apple_export_a.mp4") ?: return@runTest
      val output = temporaryPath("image-last")

      val success = exported(composition(videoClip(clip), photoClip(PHOTO)), ExportSpec(), output)

      success.info.duration shouldBeCloseTo CLIP + PHOTO
      assertClose(
        frameOf(output, CLIP + PHOTO * MIDDLE).average(0.5f, 0.5f),
        PHOTO_COLOR,
        "the photo at the end of the timeline",
      )

      remove(output)
    }

  /**
   * The photo's own span, sampled inside it rather than only at its edges.
   *
   * A span laid an interval out still puts the right pixels on the first and last frame it covers,
   * so the middle is where one that drifted shows up. The frames either side are the control: the
   * photo has to be there and only there.
   */
  @Test
  fun `puts the photo in the middle of its own span between two clips`() =
    runTest(timeout = TIMEOUT) {
      val first = fixture("apple_export_a.mp4") ?: return@runTest
      val second = fixture("apple_export_b.mp4") ?: return@runTest
      val output = temporaryPath("video-photo-video")

      val edit = composition(videoClip(first), photoClip(PHOTO), videoClip(second))
      val success = exported(edit, ExportSpec(), output)

      success.info.duration shouldBeCloseTo CLIP + PHOTO + CLIP
      assertClose(frameOf(output, CLIP + EDGE).average(0.5f, 0.5f), PHOTO_COLOR, "the photo's opening")
      assertClose(frameOf(output, CLIP + PHOTO * MIDDLE).average(0.5f, 0.5f), PHOTO_COLOR, "the photo's middle")
      assertClose(frameOf(output, CLIP + PHOTO - EDGE).average(0.5f, 0.5f), PHOTO_COLOR, "the photo's close")

      assertNotPhoto(frameOf(output, CLIP / 2).average(0.5f, 0.5f), "the clip before the photo")
      assertNotPhoto(frameOf(output, CLIP + PHOTO + CLIP / 2).average(0.5f, 0.5f), "the clip after the photo")

      remove(output)
    }

  // The arm a title card and a freeze frame both arrive on, which names no file at all.
  @Test
  fun `exports a still handed over as bytes`() =
    runTest(timeout = TIMEOUT) {
      val bytes = readBytes(photoFile())

      val output = temporaryPath("image-bytes")
      val clip = MediaSource.Image(ImageSource.ofBytes(bytes), PHOTO)
      val success = exported(composition(clip), ExportSpec(), output)

      success.info.duration shouldBeCloseTo PHOTO
      assertClose(frameOf(output, PHOTO * MIDDLE).average(0.5f, 0.5f), PHOTO_COLOR, "the photo from bytes")

      remove(output)
    }

  // A still has no samples to clip, so the planner collapses a trim over one to a window opening at
  // zero and running for what the trim kept. This is the backend consuming that, not re-deriving it.
  @Test
  fun `holds a trimmed still for the length the trim kept`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("image-trimmed")
      val edit =
        compositionOf {
          image(ImageSource.of(photoFile()), 4.seconds) { trim(1.seconds, 3.seconds) }
        }

      val success = exported(edit, ExportSpec(), output)

      success.info.duration shouldBeCloseTo 2.seconds
      assertClose(frameOf(output, 1.seconds).average(0.5f, 0.5f), PHOTO_COLOR, "the trimmed photo")

      remove(output)
    }

  // A still longer than one cut from the seed takes several, and the last of them is a part cut.
  // A length that is a whole number of cuts would never exercise it.
  @Test
  fun `holds a still whose length is not a whole number of cuts`() =
    runTest(timeout = TIMEOUT) {
      val output = temporaryPath("image-part-cut")

      val success = exported(composition(photoClip(RAGGED_PHOTO)), ExportSpec(), output)

      success.info.duration shouldBeCloseTo RAGGED_PHOTO
      assertClose(
        frameOf(output, RAGGED_PHOTO - EDGE).average(0.5f, 0.5f),
        PHOTO_COLOR,
        "the end of the part cut",
      )

      remove(output)
    }

  // A still reports a codec no muxer can name, so a copy is off the table before anything asks
  // whether the rest of the composition would have taken one.
  @Test
  fun `never transmuxes a composition carrying a still`() =
    runTest(timeout = TIMEOUT) {
      val clip = fixture("apple_export_a.mp4") ?: return@runTest

      val alone = plan(compositionOf { clip(MediaSource.of(clip)) })
      alone.path shouldBe ExportPath.Transmux

      plan(composition(videoClip(clip), photoClip(PHOTO))).path shouldBe ExportPath.Transcode
    }

  private suspend fun plan(
    composition: EditComposition,
    spec: ExportSpec = ExportSpec(),
  ) = when (val verdict = withContext(Dispatchers.Default) { filmstrip.plan(composition, spec) }) {
    is Verdict.Capable -> verdict.plan
    is Verdict.Degraded -> verdict.plan
    is Verdict.Incapable -> error(verdict.reasons.joinToString { it.message })
  }

  private suspend fun exported(
    composition: EditComposition,
    spec: ExportSpec,
    output: String,
  ): ExportStatus.Success =
    withContext(Dispatchers.Default) {
      plan(composition, spec)

      val statuses = filmstrip.export(composition, spec, MediaSink.of(output)).toList()
      when (val finished = statuses.last()) {
        is ExportStatus.Failure -> error(finished.error.message)
        else -> assertIs<ExportStatus.Success>(finished)
      }
    }

  private fun composition(vararg sources: MediaSource): EditComposition =
    compositionOf {
      sources.forEach { source ->
        when (source) {
          is MediaSource.Image -> image(source.image, source.duration)
          else -> clip(source) { trim(Duration.ZERO, CLIP) }
        }
      }
    }

  private fun videoClip(path: String): MediaSource = MediaSource.of(path)

  private fun photoClip(duration: Duration): MediaSource = MediaSource.Image(ImageSource.of(photoFile()), duration)

  /**
   * The flat photo every case draws from, written into the temporary directory once.
   */
  private fun photoFile(): String = photoFixture("filmstrip-apple-photo", PHOTO_SIZE, PHOTO_COLOR)

  private fun fixture(name: String): String? {
    val directory = fixtures ?: return null
    val path = "$directory/$name"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-apple-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private fun assertNotPhoto(
    actual: Triple<Int, Int, Int>,
    label: String,
  ) {
    assertTrue(
      distance(actual, PHOTO_COLOR) > COLOR_TOLERANCE,
      "expected $label to be a video frame, but it read as the photo at $actual",
    )
  }

  private infix fun Duration.shouldBeCloseTo(expected: Duration) =
    assertTrue((this - expected).absoluteValue <= DURATION_TOLERANCE, "expected about $expected, got $this")

  private companion object {
    const val FIXTURES = "FILMSTRIP_FIXTURES"
    val TIMEOUT = 2.seconds * 60

    // What each video fixture is trimmed to, and how long the photo is held.
    val CLIP = 1.seconds
    val PHOTO = 2.seconds

    // Neither a whole number of seed cuts nor a whole number of output frames.
    val RAGGED_PHOTO = 1_733.milliseconds

    // Far enough inside a span that the frame the other side of a boundary is not what is sampled.
    val EDGE = 200.milliseconds

    // Neither end of the photo's span, and not its halfway point either, which a span laid an
    // interval out could still land on.
    const val MIDDLE = 0.4

    // The shape of the video fixtures, so no case turns on the output frame changing between clips.
    val PHOTO_SIZE = Size(640, 360)

    // A colour a fixture's own pattern never draws, and one a 4:2:0 encode carries back inside the
    // tolerance a flat patch is asserted at.
    val PHOTO_COLOR = Triple(0x11, 0xC2, 0xAA)

    // A trim lands on a frame boundary and a container's reported duration rounds.
    val DURATION_TOLERANCE = 150.milliseconds
  }
}
