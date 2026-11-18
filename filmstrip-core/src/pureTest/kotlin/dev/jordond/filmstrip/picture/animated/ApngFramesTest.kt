package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.png.encodePng
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Fraction
import dev.jordond.filmstrip.test.CounterCells
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class ApngFramesTest {
  private val canvas = AnimatedPictures.CANVAS
  private val lists =
    listOf(AnimatedPictures.SHARED, AnimatedPictures.WITH_PREVIOUS, AnimatedPictures.WITH_SOURCE)

  @Test
  fun `every frame reads back its region disposal and blend`() {
    for (ops in lists) sameFrames(animatedOf(AnimatedApngs.bytes(canvas, ops)), ops)
  }

  @Test
  fun `every frame decodes to the texels of its tile`() {
    for (ops in lists) sameSamples(animatedOf(AnimatedApngs.bytes(canvas, ops)), ops)
  }

  @Test
  fun `a default image that is not a frame is left out`() {
    val ops = AnimatedPictures.SHARED
    val picture = animatedOf(AnimatedApngs.bytes(canvas, ops, defaultIsFrame = false))

    picture.frames.size shouldBe ops.size
    sameSamples(picture, ops)
  }

  @Test
  fun `a default image that is the first frame is that frame`() {
    val ops = AnimatedPictures.DEFAULT_IS_FRAME
    val picture = animatedOf(AnimatedApngs.bytes(canvas, ops, defaultIsFrame = true))

    sameFrames(picture, ops)
    sameSamples(picture, ops)
  }

  @Test
  fun `a sixteen bit file keeps its depth`() {
    val ops = AnimatedPictures.SHARED
    val picture = animatedOf(AnimatedApngs.bytes(canvas, ops, sixteenBit = true))

    sameSamples(picture, ops, sixteenBit = true)
  }

  @Test
  fun `the palette and transparency chunks are carried into every frame`() {
    for (ops in lists) sameSamples(animatedOf(AnimatedApngs.bytes(canvas, ops, indexed = true)), ops)
  }

  @Test
  fun `the stored delays map through the shown delay`() {
    val ops = AnimatedPictures.delayOps(AnimatedPictures.STORED_DELAYS)
    val picture = animatedOf(AnimatedApngs.bytes(canvas, ops, plays = 0))

    sameTimeline(
      picture.timeline,
      expectedTimeline(PictureContainer.Png, AnimatedPictures.STORED_DELAYS, FramePlays.Forever),
    )
  }

  @Test
  fun `a delay stored as a fraction reads as its numerator over its denominator`() {
    val fractions = AnimatedPictures.FRACTIONS
    val ops = AnimatedPictures.delayOps(fractions.map { 0 })
    val picture = animatedOf(AnimatedApngs.bytes(canvas, ops, fractions = fractions))

    sameTimeline(
      picture.timeline,
      expectedTimeline(PictureContainer.Png, fractions.map { it.storedMillis }, FramePlays.Forever),
    )
  }

  @Test
  fun `a zero denominator reads as hundredths and a zero numerator as the default`() {
    val fractions = listOf(Fraction(5, 0), Fraction(0, 0))
    val picture =
      animatedOf(AnimatedApngs.bytes(canvas, AnimatedPictures.delayOps(listOf(0, 0)), fractions = fractions))

    picture.timeline.cycle.inWholeMilliseconds shouldBe 150
  }

  @Test
  fun `the plays stored read as the plays of the table`() {
    val ops = AnimatedPictures.SHARED
    val stored = listOf(0, 1, 3)

    stored.map { animatedOf(AnimatedApngs.bytes(canvas, ops, plays = it)).timeline.plays } shouldBe
      listOf(FramePlays.Forever, FramePlays.Times(1), FramePlays.Times(3))
  }

  @Test
  fun `sixty frames each carry their index in squares`() {
    val picture = animatedOf(AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, AnimatedPictures.counterOps()))

    picture.frames.indices.map { CounterCells.indexOf(argbOf(picture.frames[it])) } shouldBe
      (0 until AnimatedPictures.COUNTER_FRAMES).toList()
  }

  @Test
  fun `a png with no animation is a still`() {
    animatedPictureOf(encodePng(2, 2, 6, 8, IntArray(16) { it })) shouldBe AnimatedRead.Still
  }

  @Test
  fun `an apng with one frame is a still`() {
    val bytes = AnimatedApngs.bytes(canvas, AnimatedPictures.DEFAULT_IS_FRAME.take(1), defaultIsFrame = true)

    animatedPictureOf(bytes) shouldBe AnimatedRead.Still
  }

  @Test
  fun `a file cut off inside a frame or ahead of its end is refused`() {
    val bytes = AnimatedApngs.bytes(canvas, AnimatedPictures.SHARED)

    animatedPictureOf(bytes.copyOf(bytes.size - 20)).refusal() shouldBe ANIMATED_UNREADABLE
    animatedPictureOf(bytes.copyOf(bytes.size - 12)).refusal() shouldBe ANIMATED_UNREADABLE
  }

  @Test
  fun `a frame outside the canvas is refused by its index`() {
    val bytes = AnimatedApngs.bytes(Size(16, 12), AnimatedPictures.SHARED)

    animatedPictureOf(bytes).refusal() shouldBe animatedFrameOutside(1)
  }

  @Test
  fun `a picture past the ceiling is refused and at it is read`() {
    val ops = AnimatedPictures.SHARED
    val bytes = AnimatedApngs.bytes(canvas, ops)
    val held = ops.sumOf { it.tile.width.toLong() * it.tile.height * 8 }

    animatedPictureOf(bytes, held - 1).refusal() shouldBe animatedPictureTooLarge(held, held - 1)
    animatedPictureOf(bytes, held).shouldBeInstanceOf<AnimatedRead.Animated>()
  }
}
