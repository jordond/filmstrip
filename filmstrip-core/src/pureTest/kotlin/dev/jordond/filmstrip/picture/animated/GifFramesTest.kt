package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.CounterCells
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

class GifFramesTest {
  private val canvas = AnimatedPictures.CANVAS
  private val lists = mapOf("shared" to AnimatedPictures.SHARED, "previous" to AnimatedPictures.WITH_PREVIOUS)

  @Test
  fun `every frame reads back its region disposal and blend`() {
    for (ops in lists.values) {
      val picture = animatedOf(AnimatedGifs.bytes(canvas, ops))
      sameFrames(picture, ops)
      picture.canvas shouldBe canvas
    }
  }

  @Test
  fun `every frame decodes to the texels of its tile`() {
    for (ops in lists.values) sameTexels(animatedOf(AnimatedGifs.bytes(canvas, ops)), ops)
  }

  @Test
  fun `an interlaced frame decodes to the same texels`() {
    for (ops in lists.values) {
      sameTexels(animatedOf(AnimatedGifs.bytes(canvas, ops, interlaced = true)), ops)
    }
  }

  @Test
  fun `a local colour table decodes to the same texels`() {
    for (ops in lists.values) {
      sameTexels(animatedOf(AnimatedGifs.bytes(canvas, ops, localTables = true)), ops)
    }
    val shared = AnimatedPictures.SHARED
    sameTexels(animatedOf(AnimatedGifs.bytes(canvas, shared, interlaced = true, localTables = true)), shared)
  }

  @Test
  fun `the hostile set holds a background disposal with no transparent colour flag`() {
    val ops = AnimatedPictures.SHARED
    val flags = AnimatedGifs.transparencyFlags(ops)

    ops.indices.filter { ops[it].disposal == AnimatedPictures.Disposal.Background && !flags[it] }.size shouldBe 1
    ops.indices.filter { ops[it].disposal == AnimatedPictures.Disposal.Background && flags[it] }.size shouldBe 1
  }

  @Test
  fun `a frame disposing to the background reads as background whatever its flag`() {
    val ops = AnimatedPictures.SHARED
    val picture = animatedOf(AnimatedGifs.bytes(canvas, ops))

    picture.frames.map { it.disposal } shouldBe
      listOf(
        FrameDisposal.None,
        FrameDisposal.None,
        FrameDisposal.Background,
        FrameDisposal.None,
        FrameDisposal.Background,
        FrameDisposal.None,
      )
  }

  @Test
  fun `the stored delays map through the shown delay`() {
    val ops = AnimatedPictures.delayOps(AnimatedPictures.GIF_DELAYS)
    val picture = animatedOf(AnimatedGifs.bytes(canvas, ops, loops = 0))

    sameTimeline(
      picture.timeline,
      expectedTimeline(PictureContainer.Gif, AnimatedPictures.GIF_DELAYS, FramePlays.Forever),
    )
  }

  @Test
  fun `no loop block and a stored zero one and three read as the plays of the table`() {
    val ops = AnimatedPictures.SHARED
    val stored = listOf(null, 0, 1, 3)

    stored.map { animatedOf(AnimatedGifs.bytes(canvas, ops, loops = it)).timeline.plays } shouldBe
      stored.map { framePlays(PictureContainer.Gif, it) }
    stored.map { animatedOf(AnimatedGifs.bytes(canvas, ops, loops = it)).timeline.plays } shouldBe
      listOf(FramePlays.Once, FramePlays.Forever, FramePlays.Times(2), FramePlays.Times(4))
  }

  @Test
  fun `sixty frames each carry their index in squares`() {
    val ops = AnimatedPictures.counterOps()
    val picture = animatedOf(AnimatedGifs.bytes(AnimatedPictures.COUNTER_CANVAS, ops))

    picture.frames.size shouldBe AnimatedPictures.COUNTER_FRAMES
    picture.frames.indices.map { CounterCells.indexOf(argbOf(picture.frames[it])) } shouldBe
      (0 until AnimatedPictures.COUNTER_FRAMES).toList()
  }

  @Test
  fun `a stream long enough to fill the code table and clear it decodes to every texel`() {
    val noisy = AnimatedGifs.noisy(Size(128, 128), frames = 3)
    val picture = animatedOf(noisy.bytes)

    picture.frames.indices.forEach { argbOf(picture.frames[it]) shouldBe noisy.texels(it) }
  }

  @Test
  fun `a gif with one frame is a still`() {
    animatedPictureOf(AnimatedGifs.bytes(canvas, AnimatedPictures.SHARED.take(1))) shouldBe AnimatedRead.Still
  }

  @Test
  fun `a file cut off inside a frame is refused`() {
    val bytes = AnimatedGifs.bytes(canvas, AnimatedPictures.SHARED)

    animatedPictureOf(bytes.copyOf(bytes.size - 3)).refusal() shouldBe ANIMATED_UNREADABLE
  }

  @Test
  fun `a frame outside the canvas is refused by its index`() {
    val bytes = AnimatedGifs.bytes(Size(16, 12), AnimatedPictures.SHARED)

    animatedPictureOf(bytes).refusal() shouldBe animatedFrameOutside(1)
  }

  @Test
  fun `a picture past the ceiling is refused and at it is read`() {
    val ops = AnimatedPictures.SHARED
    val bytes = AnimatedGifs.bytes(canvas, ops)
    val held = ops.sumOf { it.tile.width.toLong() * it.tile.height * 4 }

    animatedPictureOf(bytes, held - 1).refusal() shouldBe animatedPictureTooLarge(held, held - 1)
    animatedPictureOf(bytes, held / 2).refusal() shouldBe animatedPictureTooLarge(held, held / 2)
    animatedPictureOf(bytes, held).shouldBeInstanceOf<AnimatedRead.Animated>()
  }
}
