package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.ANIMATED_AVIF_REFUSED
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.AnimatedPicture
import dev.jordond.filmstrip.picture.animated.AnimatedRead
import dev.jordond.filmstrip.picture.animated.animatedPeakBytes
import dev.jordond.filmstrip.picture.animated.animatedPeakRefusal
import dev.jordond.filmstrip.picture.animated.animatedPictureOf
import dev.jordond.filmstrip.picture.animated.animatedSourceBytes
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class AnimatedFlipbookTest {
  private val delays = listOf(30, 70, 40, 120, 50, 90)
  private val ops = AnimatedPictures.SHARED.mapIndexed { index, op -> op.withDelay(delays[index]) }
  private val overlay = ImageOverlay(ImageSource.Path("/fixtures/a.gif"), Corner.TopStart)

  @Test
  fun aFlipbookCellOnEverySlotIsTheFrameTheTimelineNamesAcrossThreeCycles() {
    val drawn = drawn(loops = 0)
    val timeline = drawn.picture.timeline

    for (frameRate in listOf(24, 30, 60)) {
      val slots = (timeline.cycle * 3).inWholeMilliseconds * frameRate / 1000 + frameRate
      for (slot in 0..slots) {
        drawn.flipbook.cellOnSlot(Duration.ZERO, slot, frameRate) shouldBe
          drawn.grid.cells[timeline.frameOnSlot(Duration.ZERO, slot, frameRate)]
      }
    }
  }

  @Test
  fun theMiddleOfEachCycleShowsTheFrameThatCoversIt() {
    val drawn = drawn(loops = 0)
    val timeline = drawn.picture.timeline
    val frameRate = 60

    for (cycle in 0 until 3) {
      var start = timeline.cycle * cycle
      for ((index, delay) in delays.withIndex()) {
        val middle = start + delay.milliseconds / 2
        val slot = middle.inWholeMilliseconds * frameRate / 1000
        drawn.flipbook.cellAt(Duration.ZERO, middle, frameRate) shouldBe drawn.grid.cells[index]
        drawn.flipbook.cellOnSlot(Duration.ZERO, slot, frameRate) shouldBe drawn.grid.cells[index]
        start += delay.milliseconds
      }
    }
  }

  @Test
  fun threePlaysHoldTheLastFrameAfterwards() {
    val drawn = drawn(loops = 2)
    val timeline = drawn.picture.timeline

    timeline.plays shouldBe FramePlays.Times(3)
    drawn.flipbook.cellAt(Duration.ZERO, timeline.cycle * 2 + 10.milliseconds, 60) shouldBe drawn.grid.cells[0]
    drawn.flipbook.cellAt(Duration.ZERO, timeline.cycle * 3 + 500.milliseconds, 60) shouldBe drawn.grid.cells.last()
    drawn.flipbook.cellAt(Duration.ZERO, timeline.cycle * 40, 60) shouldBe drawn.grid.cells.last()
  }

  @Test
  fun aSheetOnAnAnimatedFileIsRefusedByName() {
    val sheeted = ImageOverlay(overlay.image, Corner.TopStart, sheet = SpriteSheet(2, 2, 100.milliseconds))

    animatedRead(
      bytes(0),
      AnimatedCopies.Media3,
      sheet = sheeted.sheet != null,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe
      SHEET_ON_ANIMATED
  }

  @Test
  fun aReadMadeBeforeTheOverlayIsKnownIsRefusedLaterWithTheSentencesOfTheFullRead() {
    val early = animatedRead(bytes(0), AnimatedCopies.Browser).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

    early.refusalFor(sheet = true, maxSide = null) shouldBe SHEET_ON_ANIMATED
    animatedRead(bytes(0), AnimatedCopies.Browser, sheet = true)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe early.refusalFor(sheet = true, maxSide = null)
    early.refusalFor(sheet = false, maxSide = 8) shouldBe animatedAtlasTooWide(early.grid.cells.size, 8)
    animatedRead(bytes(0), AnimatedCopies.Browser, maxSide = 8)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe early.refusalFor(sheet = false, maxSide = 8)
    early.refusalFor(
      sheet = false,
      maxSide =
        early.grid.size.width
          .coerceAtLeast(early.grid.size.height),
    ) shouldBe null
  }

  @Test
  fun aTurnedPictureIsLaidOutAtItsShownSize() {
    for (exif in listOf(1, 3, 6, 8)) {
      val drawn =
        animatedRead(OrientedAnimations.apng(exif), AnimatedCopies.Media3)
          .shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

      drawn.grid.cell shouldBe drawn.picture.shown
      drawn.flipbook.cell shouldBe PictureOrientation.ofExif(exif).orientedSize(OrientedAnimations.CANVAS)
    }
  }

  @Test
  fun theCeilingCountsTheCanvasATurnNeeds() {
    val upright = (animatedPictureOf(OrientedAnimations.apng(1)) as AnimatedRead.Animated).picture
    val turned = (animatedPictureOf(OrientedAnimations.apng(6)) as AnimatedRead.Animated).picture
    val ceiling =
      checkNotNull(
        animatedPeakBytes(
          upright.canvas,
          6,
          AnimatedCopies.Media3,
          source = source(upright, OrientedAnimations.apng(1)),
        ),
      )

    animatedRead(OrientedAnimations.apng(1), AnimatedCopies.Media3, ceiling = ceiling)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
    animatedRead(OrientedAnimations.apng(6), AnimatedCopies.Media3, ceiling = ceiling)
      .shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe
      animatedPeakRefusal(turned, AnimatedCopies.Media3, ceiling, OrientedAnimations.apng(6).size.toLong())
  }

  @Test
  fun anAnimatedPictureWithNoFrameRateIsRefusedWithTheSharedSentence() {
    val unset = attributes(frameRate = null)

    flipbookRefusal(unset, ANIMATED_NO_GRID) shouldBe ANIMATED_NO_GRID
    flipbookRefusal(attributes(frameRate = 30f), ANIMATED_NO_GRID) shouldBe null
    ANIMATED_NO_GRID.contains("sprite sheet") shouldBe false
  }

  @Test
  fun anAnimatedAvifIsRefusedWithCoreSentence() {
    animatedRead(
      avis(),
      AnimatedCopies.Media3,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldBe
      ANIMATED_AVIF_REFUSED
  }

  @Test
  fun anAtlasPastTheSideIsRefusedWithThatSide() {
    animatedRead(
      bytes(0),
      AnimatedCopies.Media3,
      maxSide = 8,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
      .message shouldContain
      "longest side is 8 pixels"
  }

  @Test
  fun aStillPictureIsNotAnimated() {
    animatedRead(AnimatedGifs.bytes(AnimatedPictures.CANVAS, ops.take(1)), AnimatedCopies.Media3) shouldBe
      AnimatedFlipbookRead.Still
  }

  @Test
  fun theFrameIndexOnASlotIsTheTimelinesWithNoAtlasInvolved() {
    val drawn = drawn(loops = 0)

    for (slot in 0L..200L) {
      drawn.flipbook.frameOnSlot(Duration.ZERO, slot, 30) shouldBe
        drawn.picture.timeline.frameOnSlot(Duration.ZERO, slot, 30)
    }
  }

  @Test
  fun theCeilingIsCountedForTheRowTheBackendHolds() {
    val picture = (animatedPictureOf(bytes(0)) as AnimatedRead.Animated).picture
    for (held in listOf(AnimatedCopies.Media3, AnimatedCopies.Apple)) {
      val peak =
        checkNotNull(animatedPeakBytes(picture.canvas, picture.frames.size, held, source = source(picture, bytes(0))))

      for (ceiling in listOf(
        peak,
        peak - 1,
        source(picture, bytes(0)) + (peak - source(picture, bytes(0))) / 2,
        peak + peak / 2,
      )) {
        val read = animatedRead(bytes(0), held, ceiling = ceiling)
        if (ceiling >= peak) {
          read.shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
        } else {
          read.shouldBeInstanceOf<AnimatedFlipbookRead.Refused>().message shouldBe
            animatedPeakRefusal(picture, held, ceiling, bytes(0).size.toLong())
        }
      }
    }
  }

  @Test
  fun aCeilingThatFitsOneRowAndNotTheOtherRefusesOnlyTheOther() {
    val picture = (animatedPictureOf(bytes(0)) as AnimatedRead.Animated).picture
    val media3 =
      checkNotNull(
        animatedPeakBytes(
          picture.canvas,
          picture.frames.size,
          AnimatedCopies.Media3,
          source = source(picture, bytes(0)),
        ),
      )
    val ffmpeg =
      checkNotNull(
        animatedPeakBytes(
          picture.canvas,
          picture.frames.size,
          AnimatedCopies.Ffmpeg,
          source = source(picture, bytes(0)),
        ),
      )
    val between = (media3 + ffmpeg) / 2

    animatedRead(
      bytes(0),
      AnimatedCopies.Media3,
      ceiling = between,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()
    animatedRead(
      bytes(0),
      AnimatedCopies.Ffmpeg,
      ceiling = between,
    ).shouldBeInstanceOf<AnimatedFlipbookRead.Refused>()
  }

  private fun attributes(frameRate: Float?): Attributes =
    Attributes(
      inputSize = Size(1280, 720),
      outputSize = Size(1280, 720),
      layoutSize = Size(1280, 720),
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = null,
      frameRate = frameRate,
      span = TimeRange.of(Duration.ZERO, 10.seconds),
    )

  private fun source(
    picture: AnimatedPicture,
    file: ByteArray,
  ): Long = checkNotNull(animatedSourceBytes(picture, file.size.toLong()))

  private fun drawn(loops: Int): AnimatedFlipbookRead.Drawn =
    animatedRead(bytes(loops), AnimatedCopies.Media3).shouldBeInstanceOf<AnimatedFlipbookRead.Drawn>()

  private fun bytes(loops: Int): ByteArray = AnimatedGifs.bytes(AnimatedPictures.CANVAS, ops, loops = loops)

  private fun AnimatedPictures.Op.withDelay(millis: Int) = AnimatedPictures.Op(x, y, tile, disposal, blend, millis)

  // An `ftyp` box whose major brand is `avis`.
  private fun avis(): ByteArray = byteArrayOf(0, 0, 0, 16) + "ftypavis".encodeToByteArray() + ByteArray(4)
}
