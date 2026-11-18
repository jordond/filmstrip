package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedWebps
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class WebpFramesTest {
  private val canvas = AnimatedPictures.CANVAS
  private val lists = listOf(AnimatedPictures.SHARED, AnimatedPictures.WITH_SOURCE, AnimatedPictures.LOSSY_FRAMES)

  @Test
  fun `every frame reads back its region disposal and blend`() {
    for (ops in lists) sameFrames(animatedOf(AnimatedWebps.bytes(canvas, ops)), ops)
  }

  @Test
  fun `every frame is a still holding the chunks of its tile`() {
    for (ops in lists) {
      val picture = animatedOf(AnimatedWebps.bytes(canvas, ops))
      ops.forEachIndexed { index, op ->
        val still =
          picture.frames[index]
            .pixels
            .shouldBeInstanceOf<FramePixels.WebpStill>()
            .bytes
        still.toList() shouldBe AnimatedWebps.still(AnimatedWebps.chunksOf(op), op.tile.width, op.tile.height).toList()
      }
    }
  }

  @Test
  fun `a lossy frame with alpha gets a header carrying the alpha flag and its size`() {
    val ops = AnimatedPictures.LOSSY_FRAMES
    val picture = animatedOf(AnimatedWebps.bytes(canvas, ops))
    val still =
      picture.frames[1]
        .pixels
        .shouldBeInstanceOf<FramePixels.WebpStill>()
        .bytes

    still.decodeToString(12, 16) shouldBe "VP8X"
    still[20].toInt() and 0x10 shouldBe 0x10
    still[24].toInt() shouldBe ops[1].tile.width - 1
    still[27].toInt() shouldBe ops[1].tile.height - 1
    still.decodeToString(30, 34) shouldBe "ALPH"
  }

  @Test
  fun `a lossless frame is its own chunk behind a riff header`() {
    val picture = animatedOf(AnimatedWebps.bytes(canvas, AnimatedPictures.SHARED))
    val still =
      picture.frames[0]
        .pixels
        .shouldBeInstanceOf<FramePixels.WebpStill>()
        .bytes

    still.decodeToString(0, 4) shouldBe "RIFF"
    still.decodeToString(8, 16) shouldBe "WEBPVP8L"
  }

  @Test
  fun `the stored delays map through the shown delay`() {
    val ops = AnimatedPictures.delayOps(AnimatedPictures.STORED_DELAYS)
    val picture = animatedOf(AnimatedWebps.bytes(canvas, ops))

    sameTimeline(
      picture.timeline,
      expectedTimeline(PictureContainer.Webp, AnimatedPictures.STORED_DELAYS, FramePlays.Forever),
    )
  }

  @Test
  fun `the loops stored read as the plays of the table`() {
    val ops = AnimatedPictures.SHARED
    val stored = listOf(0, 1, 3)

    stored.map { animatedOf(AnimatedWebps.bytes(canvas, ops, loops = it)).timeline.plays } shouldBe
      listOf(FramePlays.Forever, FramePlays.Times(1), FramePlays.Times(3))
  }

  @Test
  fun `sixty frames read in order with the chunks of their own counter`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animatedOf(AnimatedWebps.bytes(AnimatedPictures.COUNTER_CANVAS, ops))

    picture.frames.size shouldBe AnimatedPictures.COUNTER_FRAMES
    ops.forEachIndexed { index, op ->
      val still =
        picture.frames[index]
          .pixels
          .shouldBeInstanceOf<FramePixels.WebpStill>()
          .bytes
      still.toList() shouldBe AnimatedWebps.still(AnimatedWebps.chunksOf(op), op.tile.width, op.tile.height).toList()
    }
  }

  @Test
  fun `a webp with one frame is a still`() {
    animatedPictureOf(AnimatedWebps.bytes(canvas, AnimatedPictures.SHARED.take(1))) shouldBe AnimatedRead.Still
  }

  @Test
  fun `a webp that does not animate is a still`() {
    val ops = AnimatedPictures.SHARED

    animatedPictureOf(
      AnimatedWebps.still(AnimatedWebps.chunksOf(ops[0]), ops[0].tile.width, ops[0].tile.height),
    ) shouldBe
      AnimatedRead.Still
  }

  @Test
  fun `a file cut off inside a frame is refused`() {
    val bytes = AnimatedWebps.bytes(canvas, AnimatedPictures.SHARED)

    animatedPictureOf(bytes.copyOf(bytes.size - 5)).refusal() shouldBe ANIMATED_UNREADABLE
  }

  @Test
  fun `a frame outside the canvas is refused by its index`() {
    val bytes = AnimatedWebps.bytes(Size(16, 12), AnimatedPictures.SHARED)

    animatedPictureOf(bytes).refusal() shouldBe animatedFrameOutside(1)
  }
}
