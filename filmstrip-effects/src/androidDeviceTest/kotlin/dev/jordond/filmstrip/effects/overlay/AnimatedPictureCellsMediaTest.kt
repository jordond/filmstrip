package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.FrameSamples
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedWebps
import dev.jordond.filmstrip.test.LossyWebpStills
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The frames media3 holds of an animated WebP, read off the bitmaps rather than off an export.
 *
 * An export is yuv420p and moves every code, so the texels the overlay is handed are read here, against the canvas the
 * fixture's painter draws for each frame. Each frame is checked, so frames in the middle of a run are read as well as
 * its ends. Soft alpha, a hole that `Source` replaces, a frame that disposes to the background and lossy frames with
 * alpha of odd sizes are in the lists.
 */
class AnimatedPictureCellsMediaTest {
  @Test
  fun everyFrameOfTheSharedFramesIsWithinACodeOfThePainter() = framesMatchThePainter(AnimatedPictures.SHARED)

  @Test
  fun everyFrameWithSourceBlendsIsWithinACodeOfThePainter() = framesMatchThePainter(AnimatedPictures.WITH_SOURCE)

  @Test
  fun everyFrameWithLossyStillsIsWithinACodeOfThePainter() = framesMatchThePainter(AnimatedPictures.LOSSY_FRAMES)

  @Test
  fun everyFrameIsItsOwnBitmapOfTheCanvasSize() {
    val made = madeFrom(AnimatedPictures.SHARED)

    made.cells.bitmaps.size shouldBe AnimatedPictures.SHARED.size
    for (bitmap in made.cells.bitmaps.values) {
      (bitmap.width to bitmap.height) shouldBe (AnimatedPictures.CANVAS.width to AnimatedPictures.CANVAS.height)
    }
  }

  // Each frame is mapped on its own for an HDR frame, so the multiplier media3 reads it through is the frame's, and the
  // overlay answers the one of the frame it draws at each time, the second play included.
  @Test
  fun theOverlayAnswersTheLuminanceOfTheFrameItDraws() {
    val ops = AnimatedPictures.SHARED
    val drawn = readOf(ops).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
    val made = drawn.cellsFor(HdrTransfer.Pq, Duration.ZERO, RATE).shouldBeInstanceOf<AnimatedCellsRead.Made>()
    val luminance =
      drawn.grid.cells
        .withIndex()
        .associate { (index, cell) -> cell to 1f + index }
    val cells = CellBitmaps(made.cells.bitmaps, drawn.grid.cell, drawn.flipbook, Duration.ZERO, RATE, luminance)
    val spec = ImageOverlay(ImageSource.ofBytes(ByteArray(0)), Corner.TopStart)
    val span = TimeRange.of(Duration.ZERO, drawn.picture.timeline.cycle * 3)
    val overlay =
      RasterOverlay(
        made.cells.bitmaps.getValue(made.first),
        spec,
        spec.placedOn(Size(320, 180), drawn.grid.cell),
        drawn.grid.cell,
        spec.drawnRangeWithin(span),
        made.firstLuminance,
        cells,
      )

    var millis = 0L
    while (millis < (span.endExclusive ?: Duration.ZERO).inWholeMilliseconds) {
      val frame = drawn.flipbook.frameAt(Duration.ZERO, millis.milliseconds, RATE)
      withClue("at $millis ms") { overlay.getOverlaySettings(millis * 1000).hdrLuminanceMultiplier shouldBe 1f + frame }
      millis += STEP_MILLIS
    }
    overlay.release()
  }

  // A frame of a split WebP is decoded by Android as a still, read unpremultiplied so the color under soft alpha is
  // kept. Every lossy still lands within a code of what libwebp writes, the one of odd width on its last column too.
  @Test
  fun everySplitLossyWebpStillIsWithinACodeOfItsTruth() {
    for (still in LossyWebpStills.ALL) {
      val bytes = AnimatedWebps.still(still.frameChunks(), still.width, still.height)
      val truth = still.truth()
      val samples = checkNotNull(stillWebpSamples(bytes)) { still.name }

      withClue(still.name) {
        (samples.width to samples.height) shouldBe (still.width to still.height)
        for (at in 0 until still.width * still.height) {
          val want = IntArray(4) { truth[at * 4 + it].toInt() and BYTE }
          withClue("texel ${at % still.width},${at / still.width}") { samples.shouldBeWithinOne(at, want) }
        }
      }
    }
  }

  @Test
  fun everySplitLosslessWebpStillIsWithinACodeOfItsTile() {
    val tiles = with(AnimatedPictures.Tiles) { listOf(RED, GREEN, YELLOW, BLUE_HALF, GREEN_HOLE) }
    for (tile in tiles) {
      val op = Op(0, 0, tile)
      val samples =
        checkNotNull(stillWebpSamples(AnimatedWebps.still(AnimatedWebps.chunksOf(op), tile.width, tile.height)))

      withClue(tile.name) {
        (samples.width to samples.height) shouldBe (tile.width to tile.height)
        for (at in 0 until tile.width * tile.height) {
          val texel = tile.texel(at % tile.width, at / tile.width)
          val want =
            intArrayOf(
              texel shr RED_SHIFT and BYTE,
              texel shr GREEN_SHIFT and BYTE,
              texel and BYTE,
              texel ushr ALPHA_SHIFT,
            )
          withClue("texel $at") { samples.shouldBeWithinOne(at, want) }
        }
      }
    }
  }

  // Samples hold eight bits scaled by 257, and a color under zero alpha is no one's to read.
  private fun FrameSamples.shouldBeWithinOne(
    at: Int,
    want: IntArray,
  ) {
    val got = IntArray(4) { (rgba[at * 4 + it].toInt() and 0xFFFF) / 257 }
    abs(got[3] - want[3]) shouldBeLessThanOrEqualTo 1
    if (want[3] != 0) for (channel in 0 until 3) abs(got[channel] - want[channel]) shouldBeLessThanOrEqualTo 1
  }

  private fun framesMatchThePainter(ops: List<Op>) {
    val made = madeFrom(ops)
    val drawn = readOf(ops).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
    bitmapsMatch(made, drawn, AnimatedPictures.canvases(AnimatedPictures.CANVAS, ops))
  }

  // Each cell's bitmap compared texel by texel, within one code, against the canvas the painter gives for that frame.
  private fun bitmapsMatch(
    made: AnimatedCellsRead.Made,
    drawn: AnimatedFlipbookRead.Drawn,
    canvases: List<IntArray>,
  ) {
    for ((index, expected) in canvases.withIndex()) {
      val bitmap = made.cells.bitmaps.getValue(drawn.grid.cells[index])
      val actual = IntArray(expected.size)
      bitmap.getPixels(actual, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
      withClue("frame $index of ${canvases.size}") {
        for ((at, texel) in actual.withIndex()) {
          val want = expected[at]
          withClue("texel $at is ${texel.toUInt().toString(16)}, wanted ${want.toUInt().toString(16)}") {
            abs((texel ushr ALPHA_SHIFT) - (want ushr ALPHA_SHIFT)) shouldBeLessThanOrEqualTo 1
            if (want ushr ALPHA_SHIFT != 0) {
              for (shift in intArrayOf(RED_SHIFT, GREEN_SHIFT, 0)) {
                abs((texel shr shift and BYTE) - (want shr shift and BYTE)) shouldBeLessThanOrEqualTo 1
              }
            }
          }
        }
      }
    }
  }

  @Test
  fun everyFrameOfATaggedPictureIsABitmapTurnedTheWayItsTagSays() {
    val canvas = OrientedAnimations.CANVAS
    val truth = AnimatedPictures.canvases(canvas, OrientedAnimations.OPS)
    for (exif in 1..8) {
      val orientation = PictureOrientation.ofExif(exif)
      val bytes = OrientedAnimations.apng(exif)
      val drawn = animatedRead(bytes, AnimatedCopies.Media3).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
      val made = drawn.cellsFor(null, Duration.ZERO, RATE).shouldBeInstanceOf<AnimatedCellsRead.Made>()

      withClue("exif $exif") {
        made.cells.cell shouldBe orientation.orientedSize(canvas)
        bitmapsMatch(made, drawn, truth.map { orientedSamples(orientation, it, canvas) })
      }
    }
  }

  @Test
  fun twoOverlaysOfOnePictureHoldOneBitmapForEveryFrame() {
    val bytes = AnimatedWebps.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED)
    val image = ImageSource.ofBytes(bytes)
    val first = ImageOverlay(image, Corner.TopStart)
    val second = ImageOverlay(image, Corner.BottomEnd)
    val animations = SharedAnimations()
    animations.readFor(Composition(tracks = listOf(Track(emptyList())), effects = listOf(first, second)))
    val attributes = attributes()

    val a =
      first
        .sharedAnimation(
          animations,
          bytes,
          MAX_SIDE,
          attributes,
        ).cells
        .shouldBeInstanceOf<AnimatedCellsRead.Made>()
    val b =
      second
        .sharedAnimation(
          animations,
          bytes,
          MAX_SIDE,
          attributes,
        ).cells
        .shouldBeInstanceOf<AnimatedCellsRead.Made>()

    b shouldBeSameInstanceAs a
    for ((crop, bitmap) in a.cells.bitmaps) b.cells.bitmaps.getValue(crop) shouldBeSameInstanceAs bitmap
    a.cells.timedAs(Duration.ZERO, RATE).bitmaps shouldBeSameInstanceAs a.cells.bitmaps
  }

  private fun attributes(): Attributes =
    Attributes(
      inputSize = Size(320, 180),
      outputSize = Size(320, 180),
      layoutSize = Size(320, 180),
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = RATE.toFloat(),
      span = TimeRange.of(Duration.ZERO, 3.seconds),
    )

  private fun madeFrom(ops: List<Op>): AnimatedCellsRead.Made =
    readOf(ops)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
      .cellsFor(transfer = null, drawnStart = Duration.ZERO, rate = RATE)
      .shouldBeInstanceOf<AnimatedCellsRead.Made>()

  private fun readOf(ops: List<Op>): AnimatedFlipbookRead {
    val bytes = AnimatedWebps.bytes(AnimatedPictures.CANVAS, ops)
    return animatedRead(bytes, AnimatedCopies.Media3)
  }

  private companion object {
    const val RATE = 30
    const val MAX_SIDE = 4096
    const val STEP_MILLIS = 10L
    const val ALPHA_SHIFT = 24
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val BYTE = 0xFF
  }
}
