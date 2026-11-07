package dev.jordond.filmstrip.player

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.test.FrameCodes
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.eightBitFrameCodesAt
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeGreaterThanOrEqual
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import java.io.File
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The chroma a media3 file stores under a caption, held to the chroma of the preview around it.
 *
 * Every 2x2 block of a 4:2:0 file carries one Cb and one Cr, and an encoder picks them from the block by its own rule.
 * The bound takes the preview's chroma over the block and the pixels around it, which holds whether an encoder reads
 * one corner, a box average or MPEG-2 siting, and fails on a frame whose color is wrong. The contract in
 * [AndroidClipSpeedParityTest] reads luma, so this is what covers the chroma it leaves out.
 *
 * The software encoder's opening frames of any file sit further off than the bound, by up to 16 codes, and settle by
 * frame 8. That follows the frame's place in the file, at speed 1 as at any other, whatever the bitrate. So every edit
 * opens on [LEAD] at speed 1 and the frames read belong to the clip after it.
 */
class AndroidCaptionChromaTest {
  @Test
  fun aFileKeepsEachChromaBlockWithinThePreviewAroundItOnAFadeAndAPlateauAtEverySpeed() =
    contractTest(timeout = BODY_TIMEOUT) { scope ->
      val moving = MediaSource.of(movingFile().path)
      for ((speed, trim) in SPEEDS) {
        withClue("at $speed") { assertChromaBound(scope, moving, speed, trim) }
      }
    }

  private suspend fun assertChromaBound(
    scope: CoroutineScope,
    moving: MediaSource,
    speed: Double,
    trim: TimeRange,
  ) {
    val length = outputLengthOf(checkNotNull(trim.duration), speed)
    val clip = Clip(moving, trim, speed = speed)
    val edit = captionedEdit(moving, clip, length)
    val file = androidExportedFile(edit)
    val matrix = limitedRangeMatrixOf(file)
    val reads = listOf("plateau" to slotAt(LEAD + length / 2), "fade" to slotAt(LEAD + length / 4 + length / 16))
    val engine =
      Media3PlayerEngine(scope, contractContext(), Media3PreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())

    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine
        .awaitComposition(EditComposition(listOf(Track(listOf(leadOf(moving), clip)))))
        .shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the bare clip to be presentable") { recorder.lastState.hasComposition }
      val bare = reads.map { (_, position) -> engine.readback.awaitFrame(position).asTestFrame() }

      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }

      reads.forEachIndexed { index, (name, position) ->
        val shown = engine.readback.awaitFrame(position)
        val written = eightBitFrameCodesAt(file, shown.presentationTime)

        withClue("the $name at ${shown.presentationTime}") {
          largestMove(bare[index], shown.asTestFrame()) shouldBeGreaterThanOrEqual MIN_CAPTION_MOVE
          assertChromaWithin(written, shown.asTestFrame(), matrix)
        }
      }
    }
  }

  private fun slotAt(position: Duration): Duration =
    outputSlotTime(outputSlotOf(position, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

  // The furthest any channel of any pixel sits from the same pixel of the frame without the caption.
  private fun largestMove(
    bare: TestFrame,
    captioned: TestFrame,
  ): Int =
    bare.pixels.indices.maxOf { index ->
      abs((bare.pixels[index].toInt() and BYTE) - (captioned.pixels[index].toInt() and BYTE))
    }

  private fun assertChromaWithin(
    written: FrameCodes,
    shown: TestFrame,
    matrix: Int,
  ) {
    val cb = PlaneReading(written::cbAt) { x, y -> shown.cbCodeAt(x, y, matrix) }
    val cr = PlaneReading(written::crAt) { x, y -> shown.crCodeAt(x, y, matrix) }

    withClue("Cb") { cb.furthestPast(shown) shouldBeLessThanOrEqual CHROMA_MARGIN }
    withClue("Cr") { cr.furthestPast(shown) shouldBeLessThanOrEqual CHROMA_MARGIN }
  }

  // One chroma plane of the file beside the same plane of the preview, in whole codes on both sides.
  private class PlaneReading(
    val stored: (Int, Int) -> Int,
    val preview: (Int, Int) -> Int,
  ) {
    fun furthestPast(shown: TestFrame): Int {
      var furthest = Int.MIN_VALUE
      for (row in 0 until shown.size.height / 2) {
        for (column in 0 until shown.size.width / 2) {
          val around = aroundBlock(shown, column, row)
          val code = stored(column, row)
          furthest = maxOf(furthest, around.first - code, code - around.last)
        }
      }
      return furthest
    }

    // The lowest and highest preview code over the block at [column] and [row] and the ring of pixels around it.
    private fun aroundBlock(
      shown: TestFrame,
      column: Int,
      row: Int,
    ): IntRange {
      var low = Int.MAX_VALUE
      var high = Int.MIN_VALUE
      for (y in row * 2 - 1..row * 2 + 2) {
        for (x in column * 2 - 1..column * 2 + 2) {
          val code = preview(x.coerceIn(0, shown.size.width - 1), y.coerceIn(0, shown.size.height - 1))
          low = minOf(low, code)
          high = maxOf(high, code)
        }
      }
      return low..high
    }
  }

  // Runs of the fixture under a pan, long enough for the fast end of the range to hold a caption across several slots.
  private suspend fun movingFile(): File =
    androidExportedFile(
      EditComposition(
        listOf(Track(List(SOURCE_RUNS) { Clip(androidFixtureClip(), SOURCE, effects = listOf(FIXTURE_PAN)) })),
      ),
    )

  private fun leadOf(moving: MediaSource): Clip = Clip(moving, TimeRange.of(Duration.ZERO, LEAD))

  private fun captionedEdit(
    moving: MediaSource,
    clip: Clip,
    length: Duration,
  ): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(leadOf(moving), clip), fadeIn = LEAD + length / 4)),
      effects =
        listOf(
          TextOverlay(
            text = "Speed",
            anchor = Anchor.Center,
            visibleDuring = TimeRange.of(LEAD + length / 4, LEAD + length * 3 / 4),
            animation = fade(inDuration = length / 8),
          ),
        ),
    )

  // The H.273 matrix the file records, which is limited range or the codes here would mean something else.
  private fun limitedRangeMatrixOf(file: File): Int {
    val extractor = MediaExtractor().apply { setDataSource(file.path) }
    try {
      val format =
        (0 until extractor.trackCount)
          .map { extractor.getTrackFormat(it) }
          .first { it.getString(MediaFormat.KEY_MIME).orEmpty().startsWith("video/") }
      format.getInteger(MediaFormat.KEY_COLOR_RANGE) shouldBe MediaFormat.COLOR_RANGE_LIMITED
      return when (val standard = format.getInteger(MediaFormat.KEY_COLOR_STANDARD)) {
        MediaFormat.COLOR_STANDARD_BT709 -> BT709_MATRIX
        MediaFormat.COLOR_STANDARD_BT601_NTSC -> BT601_NTSC_MATRIX
        MediaFormat.COLOR_STANDARD_BT601_PAL -> BT601_PAL_MATRIX
        else -> error("$file records color standard $standard")
      }
    } finally {
      extractor.release()
    }
  }

  private companion object {
    const val CHROMA_MARGIN = 3
    const val MIN_CAPTION_MOVE = 16
    const val SOURCE_RUNS = 8
    const val BYTE = 0xFF
    const val BT601_NTSC_MATRIX = 6
    const val BT601_PAL_MATRIX = 5

    val SOURCE = TimeRange.of(Duration.ZERO, 1_500.milliseconds)
    val BODY_TIMEOUT = 5.minutes

    val LEAD = 500.milliseconds

    // Each trim carries on from the lead's last frame, so the join is no cut for the encoder to settle from. Each
    // length puts a frame inside the fade. At 1/32 a source frame holds the screen for about a second, and at 32 the
    // trim opens a quarter of a frame in, clear of a frame's own boundary.
    val SPEEDS =
      listOf(
        CLIP_SPEED_RANGE.start to TimeRange.of(LEAD, LEAD + 210.milliseconds),
        0.7 to TimeRange.of(LEAD, LEAD + 900.milliseconds),
        1.5 to TimeRange.of(LEAD, LEAD + 900.milliseconds),
        CLIP_SPEED_RANGE.endInclusive to
          TimeRange.of(LEAD + 1.seconds / 120, LEAD + 1.seconds / 120 + 10_240.milliseconds),
      )
  }
}
