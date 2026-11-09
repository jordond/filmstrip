package dev.jordond.filmstrip.player

import android.media.MediaExtractor
import android.media.MediaFormat
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.sourceLengthOf
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
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.test.eightBitFrameCodesCovering
import dev.jordond.filmstrip.test.videoSampleTimesOf
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThan
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
 * [AndroidClipSpeedParityMediaTest] reads luma, so this is what covers the chroma it leaves out. The file is read at
 * the slot the preview is read at, as the sample a player shows there, since a slowed clip writes a frame only on the
 * first of the slots it holds.
 *
 * The software encoder's first [MEASURED_OPENING_FRAMES] frames of any file sit further off than that bound. That
 * follows the frame's place in the file, at speed 1 as at any other, whatever the bitrate or setting. So the edits held
 * to the bound open on [LEAD] at speed 1 and the frames read belong to the clip after it. The opening frames are read
 * by their own test with no lead. Their chroma is held to [OPENING_CHROMA_MARGIN], which a frame whose color is wrong
 * still breaks, and their luma has to sit closer to the right frame than to the source frame after it, which is what
 * tells a wrong frame there.
 */
class AndroidCaptionChromaMediaTest {
  @Test
  fun aFileKeepsEachChromaBlockWithinThePreviewAroundItOnAFadeAndAPlateauAtEverySpeed() =
    contractTest(timeout = BODY_TIMEOUT) { scope ->
      val moving = MediaSource.of(movingFile().path)
      for ((speed, trim) in SPEEDS) {
        withClue("at $speed") { assertChromaBound(scope, moving, speed, trim) }
      }
    }

  @Test
  fun aFileKeepsItsOpeningFramesWithinTheOpeningBoundAtEverySpeed() =
    contractTest(timeout = BODY_TIMEOUT) { scope ->
      val moving = MediaSource.of(movingFile().path)
      for ((speed, trim) in OPENING_SPEEDS) {
        withClue("at $speed over $trim") { assertOpeningFrames(scope, moving, speed, trim) }
      }
    }

  private suspend fun assertOpeningFrames(
    scope: CoroutineScope,
    moving: MediaSource,
    speed: Double,
    trim: TimeRange,
  ) {
    val edit = openingEdit(Clip(moving, trim, speed = speed))
    val later = TimeRange.of(trim.start + FIXTURE_FRAME_STEP, checkNotNull(trim.endExclusive) + FIXTURE_FRAME_STEP)
    val file = androidExportedFile(edit)
    val matrix = limitedRangeMatrixOf(file)
    val slots = 0L until MEASURED_OPENING_FRAMES
    val engine =
      Media3PlayerEngine(scope, contractContext(), Media3PreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())

    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine
        .awaitComposition(openingEdit(Clip(moving, later, speed = speed)))
        .shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit a source frame later to be presentable") { recorder.lastState.hasComposition }
      val wrong = slots.map { engine.readback.awaitFrame(outputSlotTime(it, FIXTURE_FRAME_RATE)).asTestFrame() }

      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }

      slots.forEach { slot ->
        val position = outputSlotTime(slot, FIXTURE_FRAME_RATE)
        val shown = engine.readback.awaitFrame(position)
        val luma = androidExportFrameCovering(edit, position).bt709Luma()

        withClue("frame $slot at ${shown.presentationTime}") {
          lumaDb(luma, shown.asTestFrame()) shouldBeGreaterThan lumaDb(luma, wrong[slot.toInt()])
          assertChromaWithin(
            eightBitFrameCodesCovering(file, position),
            shown.asTestFrame(),
            matrix,
            OPENING_CHROMA_MARGIN,
          )
        }
      }
    }
  }

  private fun lumaDb(
    written: TestFrame,
    preview: TestFrame,
  ): Double = compareFrames(written, preview.bt709Luma()).psnrDb

  // A caption fading in across the opening frames, over the clip alone.
  private fun openingEdit(clip: Clip): Composition =
    Composition(
      tracks = listOf(Track(listOf(clip))),
      effects =
        listOf(
          TextOverlay(
            text = "Speed",
            anchor = Anchor.Center,
            animation = fade(inDuration = outputSlotTime(MEASURED_OPENING_FRAMES, FIXTURE_FRAME_RATE)),
          ),
        ),
    )

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
    val reads = listOf("plateau" to slotAt(LEAD + length / 2), "fade" to heldInTheFade(file, length))
    val engine =
      Media3PlayerEngine(scope, contractContext(), Media3PreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())

    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine
        .awaitComposition(Composition(listOf(Track(listOf(leadOf(moving), clip)))))
        .shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the bare clip to be presentable") { recorder.lastState.hasComposition }
      val bare = reads.map { (_, position) -> engine.readback.awaitFrame(position).asTestFrame() }

      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }

      reads.forEachIndexed { index, (name, position) ->
        val shown = engine.readback.awaitFrame(position)
        val written = eightBitFrameCodesCovering(file, position)

        withClue("the $name at ${shown.presentationTime}") {
          largestMove(bare[index], shown.asTestFrame()) shouldBeGreaterThanOrEqual MIN_CAPTION_MOVE
          assertChromaWithin(written, shown.asTestFrame(), matrix, chromaMargin)
        }
      }
    }
  }

  private fun slotAt(position: Duration): Duration =
    outputSlotTime(outputSlotOf(position, FIXTURE_FRAME_RATE), FIXTURE_FRAME_RATE)

  // media3's limit: a slowed clip writes each source frame once and holds it up to the next one it writes, so the
  // caption steps at the samples the file writes. The fade is read off the middle of the slots holding the sample
  // written nearest its middle, where the file still holds the caption that sample was written with and the preview
  // has to draw that same one.
  private fun heldInTheFade(
    file: File,
    length: Duration,
  ): Duration {
    val opens = LEAD + length / 4
    val fade = opens..<opens + length / 8
    val middle = opens + length / 16
    val samples = videoSampleTimesOf(file)
    val written = samples.filter { it in fade }
    withClue("the file writes no sample inside the fade from $opens") { written.isNotEmpty().shouldBeTrue() }
    val held = written.minBy { (it - middle).absoluteValue }
    val first = outputSlotOf(held, FIXTURE_FRAME_RATE)
    val next = samples.firstOrNull { it > held }?.let { outputSlotOf(it, FIXTURE_FRAME_RATE) } ?: (first + 1)
    return outputSlotTime((first + next - 1) / 2, FIXTURE_FRAME_RATE)
  }

  // The bound past the opening frames, which only the encoder it was measured on holds.
  private val chromaMargin: Int get() = if (onMeasuredEncoder) MEASURED_CHROMA_MARGIN else ANY_ENCODER_CHROMA_MARGIN

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
    margin: Int,
  ) {
    val cb = PlaneReading(written::cbAt) { x, y -> shown.cbCodeAt(x, y, matrix) }
    val cr = PlaneReading(written::crAt) { x, y -> shown.crCodeAt(x, y, matrix) }

    withClue("Cb") { cb.furthestPast(shown) shouldBeLessThanOrEqual margin }
    withClue("Cr") { cr.furthestPast(shown) shouldBeLessThanOrEqual margin }
  }

  // Runs of the fixture under a pan, long enough for the fast end of the range to hold a caption across several slots.
  private suspend fun movingFile(): File =
    androidExportedFile(
      Composition(
        listOf(Track(List(SOURCE_RUNS) { Clip(androidFixtureClip(), SOURCE, effects = listOf(FIXTURE_PAN)) })),
      ),
    )

  private fun leadOf(moving: MediaSource): Clip = Clip(moving, TimeRange.of(Duration.ZERO, LEAD))

  private fun captionedEdit(
    moving: MediaSource,
    clip: Clip,
    length: Duration,
  ): Composition =
    Composition(
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
    // c2.android.avc.encoder's limit past its opening frames: the worst block was measured 4 codes past the preview
    // around it on API 30 and 36, where a wrong frame reads 11 and up.
    const val MEASURED_CHROMA_MARGIN = 5

    // Any other encoder's limit, which isn't measured: 6 codes over c2.android.avc.encoder's worst and 1 under the 11 a
    // wrong frame reads, so a wrong frame still fails.
    const val ANY_ENCODER_CHROMA_MARGIN = 10

    // c2.android.avc.encoder's limit over its opening frames: measured up to 13 codes past the preview on API 30 and
    // 36, and 16 in an earlier read. A wrong frame reads 11 to 16 on the SD tagged fixture, so this bound catches only
    // a wrong color. Any other encoder holds the same, the widest bound a wrong color still breaks.
    const val OPENING_CHROMA_MARGIN = 16
    const val MIN_CAPTION_MOVE = 16
    const val SOURCE_RUNS = 8
    const val BYTE = 0xFF
    const val BT601_NTSC_MATRIX = 6
    const val BT601_PAL_MATRIX = 5

    val SOURCE = TimeRange.of(Duration.ZERO, 1_500.milliseconds)
    val BODY_TIMEOUT = 5.minutes

    val LEAD = 500.milliseconds

    // Each trim carries on from the lead's last frame, so the join is no cut for the encoder to settle from. Each
    // length puts a written frame inside the fade. At 1/32 a source frame holds the screen for about a second from
    // half a frame ahead of its own time, so 160ms writes the second one on the fade's middle. At 32 the trim opens a
    // quarter of a frame in, clear of a frame's own boundary.
    val SPEEDS =
      listOf(
        CLIP_SPEED_RANGE.start to TimeRange.of(LEAD, LEAD + 160.milliseconds),
        0.7 to TimeRange.of(LEAD, LEAD + 900.milliseconds),
        1.5 to TimeRange.of(LEAD, LEAD + 900.milliseconds),
        CLIP_SPEED_RANGE.endInclusive to
          TimeRange.of(LEAD + 1.seconds / 120, LEAD + 1.seconds / 120 + 10_240.milliseconds),
      )

    // A quarter of a slot short of one slot past the opening frames.
    val SHORT_SLOW_LENGTH = outputSlotTime(MEASURED_OPENING_FRAMES + 1, FIXTURE_FRAME_RATE) - FIXTURE_FRAME_STEP / 4

    // The same speeds opening the file. The first is about 8 ms of source at 1/32, short enough that all but its last
    // frame are opening ones.
    val OPENING_SPEEDS =
      listOf(
        CLIP_SPEED_RANGE.start to
          TimeRange.of(
            Duration.ZERO,
            sourceLengthOf(SHORT_SLOW_LENGTH, CLIP_SPEED_RANGE.start),
          ),
        CLIP_SPEED_RANGE.start to TimeRange.of(Duration.ZERO, 210.milliseconds),
        1.5 to TimeRange.of(Duration.ZERO, 900.milliseconds),
        CLIP_SPEED_RANGE.endInclusive to TimeRange.of(1.seconds / 120, 1.seconds / 120 + 10_240.milliseconds),
      )
  }
}

// One chroma plane of the file beside the same plane of the preview, in whole codes on both sides.
internal class PlaneReading(
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
