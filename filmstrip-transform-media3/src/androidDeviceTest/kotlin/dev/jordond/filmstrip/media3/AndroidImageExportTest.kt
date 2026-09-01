package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.net.Uri
import android.os.Handler
import android.os.HandlerThread
import androidx.media3.common.C
import androidx.media3.common.MediaItem
import androidx.media3.transformer.Composition
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.ExportResult
import androidx.media3.transformer.Transformer
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Photos on the timeline, exported by media3 onto a real file and read back frame by frame.
 *
 * The still is a flat orange sheet, which is the one channel ordering no bar of the generated video
 * fixtures shares. Where a photo shares an export with those fixtures, what is measured is how much
 * more of a frame reads as the photo than of a frame from the clips either side, so whatever the
 * pattern was already drawing cancels out.
 *
 * Skipped where a case needs a video fixture and the build had no host ffmpeg to make one.
 */
class AndroidImageExportTest {
  private val context = InstrumentationRegistry.getInstrumentation().targetContext
  private val filmstrip = Filmstrip(context) { media3Backend() }

  @Test
  fun anImageOnlyCompositionIsHeldForTheDurationItDeclares() =
    runTest(timeout = TIMEOUT) {
      val still = stillSource(PHOTO)

      val written = exported(capablePlan(composition(Clip(still)), ExportSpec()))

      written.info.duration shouldBeCloseTo PHOTO
      assertNotNull(written.info.video, "an image-only export wrote no video track")
    }

  @Test
  fun anImageOnlyCompositionWritesThePhotoOverTheWholeFrame() =
    runTest(timeout = TIMEOUT) {
      val still = stillSource(PHOTO)

      val written = exported(capablePlan(composition(Clip(still)), ExportSpec()))

      frameOf(written.file, PHOTO / 2).photoCoverage() shouldBeAbove COVERED
    }

  /**
   * The photo's own span, sampled in the middle of it and not only at its edges.
   *
   * An off-by-one in where a still is laid still puts the right pixels at the first and last frame
   * of its span, so 40% through it is where a span that drifted shows up.
   */
  @Test
  fun aVideoPhotoVideoCompositionPutsThePhotoInTheMiddleOfItsOwnSpan() =
    runTest(timeout = TIMEOUT) {
      val first = fixture(CLIP_A) ?: return@runTest
      val last = fixture(CLIP_B) ?: return@runTest
      val edit = composition(Clip(first), Clip(stillSource(PHOTO)), Clip(last))

      val written = exported(capablePlan(edit, ExportSpec(targetHeight = 240)))

      written.info.duration shouldBeCloseTo CLIP + PHOTO + CLIP
      val opening = frameOf(written.file, CLIP + EDGE)
      val middle = frameOf(written.file, CLIP + PHOTO * MIDDLE)
      val closing = frameOf(written.file, CLIP + PHOTO - EDGE)
      val before = frameOf(written.file, CLIP / 2)
      val after = frameOf(written.file, CLIP + PHOTO + CLIP / 2)

      middle.gainedOver(before, WHOLE_FRAME) shouldBeAbove COVERED
      middle.gainedOver(after, WHOLE_FRAME) shouldBeAbove COVERED
      opening.gainedOver(before, WHOLE_FRAME) shouldBeAbove COVERED
      closing.gainedOver(after, WHOLE_FRAME) shouldBeAbove COVERED
    }

  // The arm a title card and a freeze frame both arrive on, which has no file for media3 to open
  // until the backend writes one.
  @Test
  fun aStillHandedOverAsBytesIsExportedTheSameAsOneOnDisk() =
    runTest(timeout = TIMEOUT) {
      val still = MediaSource.Image(ImageSource.ofBytes(photoFile().readBytes()), PHOTO)

      val written = exported(capablePlan(composition(Clip(still)), ExportSpec()))

      written.info.duration shouldBeCloseTo PHOTO
      frameOf(written.file, PHOTO / 2).photoCoverage() shouldBeAbove COVERED
    }

  // A still has no samples to clip, so a trim over one is a shorter hold and nothing else.
  @Test
  fun aTrimmedStillIsHeldForTheLengthTheTrimKept() =
    runTest(timeout = TIMEOUT) {
      val trimmed = Clip(stillSource(4.seconds), trim = TimeRange.of(1.seconds, 3.seconds))

      val written = exported(capablePlan(composition(trimmed), ExportSpec()))

      written.info.duration shouldBeCloseTo 2.seconds
      frameOf(written.file, 1.seconds).photoCoverage() shouldBeAbove COVERED
    }

  // A still reports a codec no muxer can name, so the copy path is off the table before anything
  // asks whether the rest of the composition would have taken it.
  @Test
  fun aCompositionCarryingAStillIsNeverTransmuxed() =
    runTest(timeout = TIMEOUT) {
      val source = fixture(CLIP_A) ?: return@runTest
      val alone = capablePlan(composition(Clip(source)), ExportSpec())
      assertTrue(alone.path == ExportPath.Transmux, "the video clip on its own was expected to copy across")

      val withStill = capablePlan(composition(Clip(source), Clip(stillSource(PHOTO))), ExportSpec())

      withStill.path shouldBe ExportPath.Transcode
    }

  /**
   * A still names its length twice on media3: whole milliseconds on the media item, microseconds on
   * the edited item. The backend fills both from one span, so the two can only ever differ by the
   * rounding between them and nothing it exports could say which one media3 laid the picture out by.
   * Set far apart here, the length of the file that comes out names it.
   */
  @Test
  fun aPhotoIsHeldForTheEditedItemsLengthAndNotTheImageFields() =
    runTest(timeout = TIMEOUT) {
      val mediaItem =
        MediaItem
          .Builder()
          .setUri(Uri.fromFile(photoFile()))
          .setMimeType("image/png")
          .setImageDurationMs(DISAGREEING.inWholeMilliseconds)
          .build()
      val item =
        EditedMediaItem
          .Builder(mediaItem)
          .setDurationUs(PHOTO.inWholeMicroseconds)
          .setFrameRate(FRAME_RATE)
          .build()
      val sequence = EditedMediaItemSequence.Builder(setOf(C.TRACK_TYPE_VIDEO)).addItem(item).build()

      val written = transformed(Composition.Builder(sequence).build())

      val probed = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(written.path))).info
      probed.duration shouldBeCloseTo PHOTO
    }

  /**
   * Runs [composition] straight on media3, without a plan in front of it, and returns what it wrote.
   */
  private suspend fun transformed(composition: Composition): File {
    val output = File(context.cacheDir, "still-duration-contract.mp4").apply { delete() }
    val thread = HandlerThread("still-duration-contract").apply { start() }
    val finished = CompletableDeferred<Unit>()

    try {
      Handler(thread.looper).post {
        try {
          Transformer
            .Builder(context)
            .setLooper(thread.looper)
            .addListener(
              object : Transformer.Listener {
                override fun onCompleted(
                  composition: Composition,
                  result: ExportResult,
                ) {
                  finished.complete(Unit)
                }

                override fun onError(
                  composition: Composition,
                  result: ExportResult,
                  exception: ExportException,
                ) {
                  finished.completeExceptionally(exception)
                }
              },
            ).build()
            .start(composition, output.path)
        } catch (rejected: RuntimeException) {
          finished.completeExceptionally(rejected)
        }
      }
      withContext(Dispatchers.Default) { finished.await() }
    } finally {
      thread.quitSafely()
    }

    return output
  }

  private suspend fun capablePlan(
    composition: EditComposition,
    spec: ExportSpec,
  ): ExportPlan =
    when (val verdict = filmstrip.plan(composition, spec)) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> throw AssertionError("refused: ${verdict.reasons.map { it.message }}")
    }

  /**
   * Runs [plan] and reads the file back the way a caller would.
   */
  private suspend fun exported(plan: ExportPlan): Written {
    val statuses = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.temporary()).toList() }

    val finished = statuses.last()
    if (finished is ExportStatus.Failure) throw AssertionError("export failed: ${finished.error.message}")
    val success = assertIs<ExportStatus.Success>(finished)
    val path = assertIs<MediaSink.Path>(success.output).path

    val probed = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info
    return Written(File(path), probed)
  }

  private fun composition(vararg clips: Clip) = EditComposition(listOf(Track(clips.toList())))

  private fun stillSource(duration: Duration) = MediaSource.Image(ImageSource.of(photoFile().path), duration)

  /**
   * A flat orange sheet the shape of the video fixtures, written into the cache once.
   */
  private fun photoFile(): File {
    val file = File(context.cacheDir, "image-export-photo.png")
    if (file.exists()) return file

    val bitmap = Bitmap.createBitmap(PHOTO_WIDTH, PHOTO_HEIGHT, Bitmap.Config.ARGB_8888)
    bitmap.eraseColor(BADGE_COLOR)
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    return file
  }

  /**
   * Copies a packaged fixture into the cache, or null when the build had no ffmpeg to make one.
   */
  private fun fixture(name: String): MediaSource? {
    val stream = javaClass.classLoader?.getResourceAsStream(name) ?: return null
    val file = File(context.cacheDir, name)
    stream.use { input -> file.outputStream().use(input::copyTo) }
    return MediaSource.of(file.path)
  }

  private fun Bitmap.photoCoverage(): Float = badgeFraction(WHOLE_FRAME)

  private infix fun Float.shouldBeAbove(floor: Float) =
    assertTrue(this > floor, "expected more than $floor of the frame to read as the photo, got $this")

  private infix fun Duration.shouldBeCloseTo(expected: Duration) =
    assertTrue((this - expected).absoluteValue <= DURATION_TOLERANCE, "expected about $expected, got $this")

  /**
   * A finished export, as the file it landed in and what a probe made of it.
   */
  private class Written(
    val file: File,
    val info: MediaInfo,
  )

  private companion object {
    val TIMEOUT = 5.minutes

    const val CLIP_A = "android_export_a.mp4"
    const val CLIP_B = "android_export_b.mp4"

    // What the fixture generator was asked for, so a change to it fails here rather than drifting.
    val CLIP = 2.seconds
    val PHOTO = 2.seconds

    // Nowhere near the length the item names, so which of the two media3 read is unmistakable in
    // the file that comes out.
    val DISAGREEING = 500.milliseconds
    const val FRAME_RATE = 30

    // Far enough inside a span that the frame either side of a boundary is not what gets sampled.
    val EDGE = 200.milliseconds

    // Neither end of the photo's span, and not the halfway point either, which a span laid an
    // interval out could still land on.
    const val MIDDLE = 0.4

    const val PHOTO_WIDTH = 640
    const val PHOTO_HEIGHT = 360
    const val PNG_QUALITY = 100

    val WHOLE_FRAME = Region(0f, 0f, 1f, 1f)

    // A trim lands on frame boundaries, and a container's reported duration rounds.
    val DURATION_TOLERANCE = 150.milliseconds
  }
}
