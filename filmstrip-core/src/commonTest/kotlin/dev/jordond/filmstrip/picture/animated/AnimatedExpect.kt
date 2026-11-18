package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Disposal
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

private const val SLOTS_PER_SECOND = 1000

internal fun animatedOf(bytes: ByteArray): AnimatedPicture =
  animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture

/**
 * The timeline a file storing [storedMillis] and [plays] must have, every delay passed through [shownFrameDelay].
 */
internal fun expectedTimeline(
  container: PictureContainer,
  storedMillis: List<Int>,
  plays: FramePlays,
): FrameTimeline = FrameTimeline(storedMillis.map { shownFrameDelay(container, it.milliseconds) }, plays)

/**
 * Holds [actual] to [expected] on its frame count, its plays, its cycle and the frame it shows on every millisecond of
 * its first two cycles, which reads the middle of every frame and not just where one starts.
 */
internal fun sameTimeline(
  actual: FrameTimeline,
  expected: FrameTimeline,
) {
  actual.frameCount shouldBe expected.frameCount
  actual.plays shouldBe expected.plays
  actual.cycle shouldBe expected.cycle
  val slots = expected.cycle.inWholeMilliseconds * 2 + 1
  for (slot in 0 until slots) {
    withClue("slot $slot") {
      actual.frameOnSlot(Duration.ZERO, slot, SLOTS_PER_SECOND) shouldBe
        expected.frameOnSlot(Duration.ZERO, slot, SLOTS_PER_SECOND)
    }
  }
}

/**
 * Holds every frame of [picture] to its operation in [ops]: the region, the blend and the disposal.
 */
internal fun sameFrames(
  picture: AnimatedPicture,
  ops: List<Op>,
  blends: Boolean = true,
) {
  picture.frames.size shouldBe ops.size
  ops.forEachIndexed { index, op ->
    val frame = picture.frames[index]
    withClue("frame $index of $op") {
      frame.region.left shouldBe op.x
      frame.region.top shouldBe op.y
      frame.region.size.width shouldBe op.tile.width
      frame.region.size.height shouldBe op.tile.height
      frame.disposal shouldBe
        when (op.disposal) {
          Disposal.None -> FrameDisposal.None
          Disposal.Background -> FrameDisposal.Background
          Disposal.Previous -> FrameDisposal.Previous
        }
      if (blends) {
        frame.blend shouldBe if (op.blend == Blend.Source) FrameBlend.Source else FrameBlend.Over
      }
    }
  }
}

/**
 * Holds the decoded samples of every frame of [picture] to the texels of its operation's tile, a sample being its 8-bit
 * channel times 257.
 */
internal fun sameSamples(
  picture: AnimatedPicture,
  ops: List<Op>,
  sixteenBit: Boolean = false,
) {
  ops.forEachIndexed { index, op ->
    val pixels = picture.frames[index].pixels.shouldBeInstanceOf<FramePixels.Samples>()
    pixels.sixteenBit shouldBe sixteenBit
    val width = op.tile.width
    withClue("frame $index of $op") {
      pixels.rgba.size shouldBe width * op.tile.height * 4
      for (y in 0 until op.tile.height) {
        for (x in 0 until width) {
          val texel = op.tile.texel(x, y)
          val at = (y * width + x) * 4
          val expected = listOf(texel shr 16, texel shr 8, texel, texel ushr 24).map { (it and 0xFF) * 257 }
          val actual = (0 until 4).map { pixels.rgba[at + it].toInt() and 0xFFFF }
          withClue("texel $x,$y") { actual shouldBe expected }
        }
      }
    }
  }
}

/**
 * Holds the decoded texels of every frame of [picture] to the texels of its operation's tile.
 */
internal fun sameTexels(
  picture: AnimatedPicture,
  ops: List<Op>,
) {
  ops.forEachIndexed { index, op ->
    val argb =
      picture.frames[index]
        .pixels
        .shouldBeInstanceOf<FramePixels.Texels>()
        .argb
    withClue("frame $index of $op") {
      argb.toList() shouldBe
        (0 until op.tile.width * op.tile.height).map { op.tile.texel(it % op.tile.width, it / op.tile.width) }
    }
  }
}

/**
 * The packed ARGB texels of [frame], taking the top byte of each sample of a frame stored as samples.
 */
internal fun argbOf(frame: StoredFrame): IntArray =
  when (val pixels = frame.pixels) {
    is FramePixels.Texels -> {
      pixels.argb
    }
    is FramePixels.Samples -> {
      IntArray(pixels.rgba.size / 4) {
        val channel = { offset: Int -> (pixels.rgba[it * 4 + offset].toInt() and 0xFFFF) / 257 }
        (channel(3) shl 24) or (channel(0) shl 16) or (channel(1) shl 8) or channel(2)
      }
    }
    is FramePixels.WebpStill -> {
      error("A WebP frame holds no texels.")
    }
  }

internal fun AnimatedRead.refusal(): String =
  shouldBeInstanceOf<AnimatedRead.Refused>().message.also {
    it.shouldNotBeNull()
  }
