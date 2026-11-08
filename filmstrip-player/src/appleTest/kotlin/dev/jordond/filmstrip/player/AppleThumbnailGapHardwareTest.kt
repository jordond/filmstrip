package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.avfoundation.avFoundationBackend
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.PlatformImage
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.AvPlayerEngine
import dev.jordond.filmstrip.player.internal.AvPreviewPlanner
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREEN
import dev.jordond.filmstrip.test.AuthoredColors.MID_GREY
import dev.jordond.filmstrip.test.PREVIEW_PARITY_EXPORT_BITRATE
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.suspendCancellableCoroutine
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGRectMake
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.coroutines.resume
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A strip frame and a preview frame read asked for inside a gap in the timeline, each of which has to be the frame the
 * export writes there.
 *
 * The export paints the composition's fill across a gap and draws the composition's overlays over it, so a frame there
 * reads the fill and the overlay rather than whichever clip sits nearest. Each frame is held to the export at the time
 * it came back, and read on its own against the colors the case authored, at positions taken off the overlay's own
 * placement. The authored fill and overlay are also read off the written file and the export's own readout, over a
 * still's frame as well as inside the gap.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleThumbnailGapHardwareTest {
  init {
    pumpMainRunLoopDuringContracts()
  }

  @Test
  fun `a thumbnail inside a leading gap matches the export`() =
    contractTest {
      val composition = lateComposition(listOf(badge()))
      val request = request(composition, REVISION + 2)
      val thumbnail = thumbnailSource(request).awaitThumbnail(request)

      try {
        thumbnail.presentationTime shouldBe IN_GAP
        assertFramesSimilar(
          expected = appleFixtureExportFrame(composition, thumbnail.presentationTime),
          actual = thumbnail.image.toTestFrame(),
          message = "the thumbnail and the export disagree inside the gap at ${thumbnail.presentationTime}",
        )
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a frame read inside a leading gap matches the export`() =
    contractTest { scope ->
      val composition = lateComposition(listOf(badge()))
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val read = engine.readback.awaitFrame(IN_GAP)
        read.presentationTime shouldBe IN_GAP
        assertFramesSimilar(
          expected = appleFixtureExportFrame(composition, read.presentationTime),
          actual = read.asTestFrame(),
          message = "the frame read and the export disagree inside the gap at ${read.presentationTime}",
        )
      }
    }

  @Test
  fun `a thumbnail inside a leading gap reads the fill`() =
    contractTest {
      val request = request(lateComposition(), REVISION)
      val thumbnail = thumbnailSource(request).awaitThumbnail(request)

      try {
        val frame = thumbnail.image.toTestFrame()
        frame.size shouldBe FIXTURE_FRAME
        thumbnail.presentationTime shouldBe IN_GAP

        frame.assertAuthored(HALF, HALF, MID_GREY, "the fill at the centre")
        frame.assertAuthored(CORNER_FRACTION, CORNER_FRACTION, MID_GREY, "the fill by the top start corner")
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a thumbnail inside a leading gap draws the composition overlay over the fill`() =
    contractTest {
      val mark = badge()
      val request = request(lateComposition(listOf(mark)), REVISION + 1)
      val thumbnail = thumbnailSource(request).awaitThumbnail(request)

      try {
        thumbnail.image.toTestFrame().assertBadgeOverFill(mark)
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun `a frame read inside a leading gap reads the fill`() =
    contractTest { scope ->
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(lateComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val read = engine.readback.awaitFrame(IN_GAP)
        val frame = read.asTestFrame()
        frame.size shouldBe FIXTURE_FRAME
        read.presentationTime shouldBe IN_GAP

        frame.assertAuthored(HALF, HALF, MID_GREY, "the fill at the centre")
        frame.assertAuthored(CORNER_FRACTION, CORNER_FRACTION, MID_GREY, "the fill by the top start corner")
      }
    }

  @Test
  fun `a frame read inside a leading gap draws the composition overlay over the fill`() =
    contractTest { scope ->
      val mark = badge()
      val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
      withEngine(engine) { recorder ->
        engine.awaitComposition(lateComposition(listOf(mark))).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        engine.readback
          .awaitFrame(IN_GAP)
          .asTestFrame()
          .assertBadgeOverFill(mark)
      }
    }

  // The fill, the badge and a portrait still of the badge's own image, read over the still's frame and inside the gap
  // off the written file's Y'CbCr codes, off the file the way the avfoundation authored color tests read it, off the
  // export's BGRA readout, off a thumbnail and off a frame read. Every reading is printed as a table, and the fill and
  // the badge land as their own codes on each.
  @Test
  fun `lands the fill and an overlay as their own codes on every read`() =
    contractTest(timeout = MEASURE_TIMEOUT) { scope ->
      val mark = badge()
      val composition = stillComposition(mark)
      val output = NSTemporaryDirectory() + "filmstrip-player-authored-reads.mp4"
      exported(composition, output)

      val badgeRect = mark.placedOn(FIXTURE_FRAME, BADGE).rectOn(FIXTURE_FRAME)
      val badgeAt = (badgeRect.left + badgeRect.right) / 2f to (badgeRect.top + badgeRect.bottom) / 2f
      val stillWidth = STILL.width.toFloat() / STILL.height * FIXTURE_FRAME.height / FIXTURE_FRAME.width
      val bar = (1f - stillWidth) / 2f
      val readings =
        listOf(
          Reading("fill", MID_GREY, "still's frame", IN_STILL, bar / 2f to HALF),
          Reading("fill", MID_GREY, "gap", IN_GAP, HALF to HALF),
          Reading("overlay", MID_GREEN, "still's frame", IN_STILL, badgeAt),
          Reading("overlay", MID_GREEN, "gap", IN_GAP, badgeAt),
          Reading(STILL_SUBJECT, MID_GREEN, "still's frame", IN_STILL, HALF to HALF),
        )

      val times = listOf(IN_GAP, IN_STILL)
      val exportFrames = times.associateWith { appleExportFrame(composition, it) }
      val thumbnails = times.associateWith { thumbnailAt(composition, it) }
      val frameReads = frameReadsOf(scope, composition, times)

      val read =
        readings.associateWith { reading ->
          val (x, y) = reading.at
          listOf(
            "file Y'CbCr" to fileCodesAt(output, reading.time, x, y),
            "file probe" to probeCodesAt(output, reading.time, x, y),
            "export BGRA" to exportFrames.getValue(reading.time).codesAt(x, y),
            "thumbnail" to thumbnails.getValue(reading.time).codesAt(x, y),
            "frame read" to frameReads.getValue(reading.time).codesAt(x, y),
          )
        }
      NSFileManager.defaultManager.removeItemAtPath(output, error = null)
      val table =
        read.entries.joinToString("\n") { (reading, surfaces) ->
          val label = "${reading.subject} ${AuthoredColors.channelsOf(reading.authored)} over the ${reading.region}"
          (listOf(label) + surfaces.map { (surface, codes) -> "$surface $codes" }).joinToString(" | ")
        }
      println("Authored colors on Apple, the badge at $badgeRect and the bars out to $bar\n$table")

      // A still is media and stays color managed, so only the fill and the overlay are held to their own codes.
      read.filterKeys { it.subject != STILL_SUBJECT }.forEach { (reading, surfaces) ->
        surfaces.forEach { (surface, codes) ->
          val label = "the ${reading.subject} over the ${reading.region} off the $surface"
          assertLandsAsAuthored(codes, reading.authored, label)
        }
      }
    }

  private fun assertLandsAsAuthored(
    codes: Codes,
    authored: Int,
    label: String,
  ) {
    val read = listOf(codes.red, codes.green, codes.blue)
    val expected = AuthoredColors.channelsOf(authored)
    withClue("$label read $codes, where $expected was authored") {
      read.indices.all { abs(read[it] - expected[it]) <= AuthoredColors.TOLERANCE }.shouldBeTrue()
    }
  }

  private fun thumbnailSource(request: ThumbnailRequest): ThumbnailSource =
    createThumbnailSource(request, CONTRACT_COMPONENTS) ?: fail("no thumbnail source claimed the fixture")

  private fun request(
    composition: EditComposition,
    revision: Long,
  ) = ThumbnailRequest(composition, IN_GAP, FIXTURE_FRAME.height, revision, precise = true)

  private suspend fun exported(
    composition: EditComposition,
    output: String,
  ) {
    NSFileManager.defaultManager.removeItemAtPath(output, error = null)
    val spec = ExportSpec(bitrate = PREVIEW_PARITY_EXPORT_BITRATE)
    val statuses = Filmstrip { avFoundationBackend() }.export(composition, spec, MediaSink.of(output)).toList()
    when (val finished = statuses.last()) {
      is ExportStatus.Failure -> fail(finished.error.message)
      else -> finished.shouldBeInstanceOf<ExportStatus.Success>()
    }
  }

  private suspend fun thumbnailAt(
    composition: EditComposition,
    position: Duration,
  ): TestFrame {
    val request = ThumbnailRequest(composition, position, FIXTURE_FRAME.height, REVISION + 3, precise = true)
    val thumbnail = thumbnailSource(request).awaitThumbnail(request)
    try {
      thumbnail.presentationTime shouldBe position
      return thumbnail.image.toTestFrame()
    } finally {
      thumbnail.image.close()
    }
  }

  private suspend fun frameReadsOf(
    scope: CoroutineScope,
    composition: EditComposition,
    positions: List<Duration>,
  ): Map<Duration, TestFrame> {
    val engine = AvPlayerEngine(scope, AvPreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig(), muted = true)
    val frames = mutableMapOf<Duration, TestFrame>()
    withEngine(engine) { recorder ->
      engine.awaitComposition(composition).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }
      positions.forEach { position ->
        val read = engine.readback.awaitFrame(position)
        read.presentationTime shouldBe position
        frames[position] = read.asTestFrame()
      }
    }
    return frames
  }

  // The fixture clip waits GAP_LENGTH on its track, so the video opens on a gap the fill covers.
  private fun lateComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH))),
            start = GAP_LENGTH,
          ),
        ),
      effects = effects,
      fill = Fill.Solid(MID_GREY),
    )

  // The late fixture clip, then a portrait still of the badge's own image that leaves the fill in the bars.
  private fun stillComposition(mark: ImageOverlay): EditComposition =
    EditComposition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
              Clip(MediaSource.Image(ImageSource.of(greenPhoto(STILL_NAME, STILL)), STILL_LENGTH)),
            ),
            start = GAP_LENGTH,
          ),
        ),
      effects = listOf(mark),
      fill = Fill.Solid(MID_GREY),
    )

  private fun badge(): ImageOverlay =
    ImageOverlay(ImageSource.of(greenPhoto(BADGE_NAME, BADGE)), Corner.BottomEnd, BADGE_MARGIN, BADGE_SCALE)

  private fun greenPhoto(
    name: String,
    size: Size,
  ): String {
    val (red, green, blue) = AuthoredColors.channelsOf(MID_GREEN)
    return photoWritten(name, size) { context ->
      CGContextSetRGBFillColor(c = context, red = red / FULL, green = green / FULL, blue = blue / FULL, alpha = 1.0)
      CGContextFillRect(context, CGRectMake(0.0, 0.0, size.width.toDouble(), size.height.toDouble()))
    }
  }

  private fun TestFrame.assertBadgeOverFill(mark: ImageOverlay) {
    val rect = mark.placedOn(size, BADGE).rectOn(size)
    val midX = (rect.left + rect.right) / 2f
    val midY = (rect.top + rect.bottom) / 2f

    assertAuthored(midX, midY, MID_GREEN, "the badge")
    assertAuthored(1f - midX, midY, MID_GREY, "the fill across from the badge")
    assertAuthored(HALF, HALF, MID_GREY, "the fill at the centre")
  }

  private class Reading(
    val subject: String,
    val authored: Int,
    val region: String,
    val time: Duration,
    val at: Pair<Float, Float>,
  )

  private companion object {
    const val REVISION = 200L

    val GAP_LENGTH = 1.seconds

    // Inside the gap and on the fixture's 30fps grid, rather than at either end of the gap.
    val IN_GAP = 400.milliseconds

    val STILL_LENGTH = 1.seconds

    // Inside the still that follows the fixture clip, on the same grid and clear of either end of its span.
    val IN_STILL = 3.seconds

    const val BADGE_NAME = "filmstrip-player-gap-badge.png"
    val BADGE = Size(64, 64)
    const val BADGE_MARGIN = 0.04f
    const val BADGE_SCALE = 0.25f

    const val STILL_NAME = "filmstrip-player-authored-still.png"
    const val STILL_SUBJECT = "still"
    val STILL = Size(180, 360)

    // An export, two renders of it, two thumbnails and a preview, where the other cases each take one or two.
    val MEASURE_TIMEOUT = 3.seconds * 60

    const val HALF = 0.5f

    // Close to the top start corner, clear of the edge row a resize can blend.
    const val CORNER_FRACTION = 0.05f

    const val FULL = 255.0
  }
}

/**
 * Asserts the color at a point, given as a fraction of this frame's width and height, sits within
 * [AuthoredColors.TOLERANCE] of [authored] on every channel.
 */
private fun TestFrame.assertAuthored(
  fx: Float,
  fy: Float,
  authored: Int,
  label: String,
) {
  val x = (fx * size.width).toInt().coerceIn(0, size.width - 1)
  val y = (fy * size.height).toInt().coerceIn(0, size.height - 1)
  val offset = (y * size.width + x) * CHANNELS
  val read = (0 until 3).map { pixels[offset + it].toInt() and BYTE_MASK }
  val expected = AuthoredColors.channelsOf(authored)
  for (channel in 0 until 3) {
    withClue("$label read $read at ($x, $y), where $expected was authored") {
      (abs(read[channel] - expected[channel]) <= AuthoredColors.TOLERANCE).shouldBeTrue()
    }
  }
}

private suspend fun ThumbnailSource.awaitThumbnail(request: ThumbnailRequest): ThumbnailResult.Success =
  awaitStep("a thumbnail at ${request.position}") {
    suspendCancellableCoroutine { continuation ->
      val handle: Cancellable =
        requestThumbnail(request) { result ->
          if (!continuation.isActive) return@requestThumbnail
          when (result) {
            is ThumbnailResult.Success -> continuation.resume(result)
            is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
          }
        }
      continuation.invokeOnCancellation { handle.cancel() }
    }
  }

private fun PlatformImage.toTestFrame(): TestFrame = TestFrame(toRgba8888(), Size(widthPx, heightPx))

private const val CHANNELS = 4
private const val BYTE_MASK = 0xFF
