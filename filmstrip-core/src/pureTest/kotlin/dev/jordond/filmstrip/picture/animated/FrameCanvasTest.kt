package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Disposal
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.AnimatedPictures.Tiles
import dev.jordond.filmstrip.test.AnimatedWebps
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlin.math.abs
import kotlin.test.Test

class FrameCanvasTest {
  private val canvas = AnimatedPictures.CANVAS

  private val blends =
    listOf(
      Op(0, 0, Tiles.RED),
      Op(4, 4, Tiles.BLUE_HALF),
      Op(2, 2, Tiles.BLUE_HALF, blend = Blend.Source),
      Op(6, 6, Tiles.BLUE_HALF),
      Op(0, 0, Tiles.GREEN_HOLE, Disposal.Background),
      Op(2, 2, Tiles.BLUE_HALF),
    )

  @Test
  fun `every frame of a gif equals the painter in every layout`() {
    val lists = listOf(AnimatedPictures.SHARED, AnimatedPictures.WITH_PREVIOUS, AnimatedPictures.DEFAULT_IS_FRAME)
    for (ops in lists) {
      for ((interlaced, local) in listOf(false to false, true to false, false to true, true to true)) {
        val bytes = AnimatedGifs.bytes(canvas, ops, interlaced = interlaced, localTables = local)
        sameAsPainter(animated(bytes), canvas, ops)
      }
    }
  }

  @Test
  fun `every frame of an apng equals the painter`() {
    val lists =
      listOf(
        AnimatedPictures.SHARED,
        AnimatedPictures.WITH_PREVIOUS,
        AnimatedPictures.WITH_SOURCE,
        AnimatedPictures.LOSSY_FRAMES,
        blends,
      )
    for (ops in lists) {
      sameAsPainter(animated(AnimatedApngs.bytes(canvas, ops)), canvas, ops)
    }
    val whole = AnimatedPictures.DEFAULT_IS_FRAME
    sameAsPainter(animated(AnimatedApngs.bytes(canvas, whole, defaultIsFrame = true)), canvas, whole)
    sameAsPainter(animated(AnimatedApngs.bytes(canvas, blends, indexed = true)), canvas, blends)
  }

  @Test
  fun `sixty counter frames of an apng each equal the painter`() {
    val ops = AnimatedPictures.counterOps()
    sameAsPainter(
      animated(AnimatedApngs.bytes(AnimatedPictures.COUNTER_CANVAS, ops)),
      AnimatedPictures.COUNTER_CANVAS,
      ops,
    )
  }

  @Test
  fun `a sixteen bit apng keeps its depth and lands within half a code of the painter`() {
    for (ops in listOf(AnimatedPictures.SHARED, AnimatedPictures.WITH_PREVIOUS, AnimatedPictures.WITH_SOURCE, blends)) {
      val picture = animated(AnimatedApngs.bytes(canvas, ops, sixteenBit = true))
      val frames = walk(picture) { null }
      frames.size shouldBe ops.size
      for ((index, expected) in AnimatedPictures.canvases(canvas, ops).withIndex()) {
        frames[index].sixteenBit shouldBe true
        for (texel in expected.indices) {
          for (channel in 0 until 4) {
            val sample = frames[index].rgba[texel * 4 + channel].toInt() and 0xFFFF
            val wanted = (expected[texel] ushr (SHIFTS[channel])) and 0xFF
            withClue("frame $index texel $texel channel $channel") {
              abs(sample - wanted * 257) shouldBeLessThanOrEqual HALF_CODE
            }
          }
        }
      }
    }
  }

  @Test
  fun `every frame of a webp equals the painter through a decoder over the known stills`() {
    val lists = listOf(AnimatedPictures.SHARED, AnimatedPictures.WITH_SOURCE, AnimatedPictures.LOSSY_FRAMES)
    for (ops in lists) sameAsPainter(animated(AnimatedWebps.bytes(canvas, ops)), canvas, ops, knownStills(ops))
    val counters = AnimatedPictures.counterOps()
    val counterCanvas = AnimatedPictures.COUNTER_CANVAS
    sameAsPainter(
      animated(AnimatedWebps.bytes(counterCanvas, counters)),
      counterCanvas,
      counters,
      knownStills(counters),
    )
  }

  @Test
  fun `a background disposal with no transparent flag reads zero in the middle of the region and on its edge`() {
    val ops = AnimatedPictures.SHARED
    AnimatedGifs.transparencyFlags(ops)[4] shouldBe false
    val frames = walk(animated(AnimatedGifs.bytes(canvas, ops))) { null }
    val painter = AnimatedPictures.canvases(canvas, ops)[5]

    for ((x, y) in listOf(13 to 12, 10 to 15, 10 to 8, 15 to 11)) {
      withClue("texel $x,$y") {
        painter[y * canvas.width + x] shouldBe 0
        samplesAt(frames[5], x, y) shouldBe listOf(0, 0, 0, 0)
      }
    }
    painter[15 * canvas.width + 9] ushr 24 shouldBe 255
    samplesAt(frames[5], 9, 15)[3] shouldBe 0xFFFF
  }

  @Test
  fun `a background disposal clears a region the file marks transparent the same way`() {
    val ops = AnimatedPictures.SHARED
    AnimatedGifs.transparencyFlags(ops)[2] shouldBe true
    val frames = walk(animated(AnimatedGifs.bytes(canvas, ops))) { null }

    for ((x, y) in listOf(26 to 9, 20 to 4, 31 to 15, 25 to 10)) {
      withClue("texel $x,$y") { samplesAt(frames[3], x, y) shouldBe listOf(0, 0, 0, 0) }
    }
  }

  @Test
  fun `the walk hands the same canvas to every frame`() {
    val ops = AnimatedPictures.SHARED
    val seen = mutableListOf<FrameSamples>()
    val result = compositeFrames(animated(AnimatedGifs.bytes(canvas, ops)), { null }) { _, frame -> seen += frame }

    result shouldBe CompositeResult.Done
    seen.size shouldBe ops.size
    seen.forEach { it shouldBeSameInstanceAs seen[0] }
  }

  @Test
  fun `a frame that does not decode ends the walk and names it`() {
    val ops = AnimatedPictures.SHARED
    val picture = animated(AnimatedWebps.bytes(canvas, ops))
    val known = knownStills(ops)
    var bad: ByteArray? = null
    var handed = 0
    val result =
      compositeFrames(
        picture,
        { bytes ->
          if (bad == null && handed == 2) bad = bytes
          if (bytes === bad) null else known(bytes)
        },
        { index, _ -> handed = index + 1 },
      )

    result.shouldBeInstanceOf<CompositeResult.Refused>().message shouldBe animatedFrameUndecoded(2)
    handed shouldBe 2
  }

  @Test
  fun `a decoded frame of the wrong size is refused`() {
    val ops = AnimatedPictures.SHARED
    val picture = animated(AnimatedWebps.bytes(canvas, ops))
    val result = compositeFrames(picture, { FrameSamples(1, 1, ShortArray(4), false) }) { _, _ -> }

    result.shouldBeInstanceOf<CompositeResult.Refused>().message shouldBe animatedFrameUndecoded(0)
  }

  @Test
  fun `a frame outside the canvas is refused by index`() {
    val ops = AnimatedPictures.SHARED
    val read = animated(AnimatedGifs.bytes(canvas, ops))
    val smaller = AnimatedPicture(Size(16, 12), read.frames, read.timeline)
    var handed = 0
    val result = compositeFrames(smaller, { null }) { _, _ -> handed++ }

    result.shouldBeInstanceOf<CompositeResult.Refused>().message shouldBe animatedFrameOutside(1)
    handed shouldBe 1
  }

  private fun animated(bytes: ByteArray): AnimatedPicture =
    animatedPictureOf(bytes).shouldBeInstanceOf<AnimatedRead.Animated>().picture

  private fun walk(
    picture: AnimatedPicture,
    decodeStill: (ByteArray) -> FrameSamples?,
  ): List<FrameSamples> {
    val frames = mutableListOf<FrameSamples>()
    val result =
      compositeFrames(picture, decodeStill) { _, frame ->
        frames += FrameSamples(frame.width, frame.height, frame.rgba.copyOf(), frame.sixteenBit)
      }
    result shouldBe CompositeResult.Done
    return frames
  }

  private fun sameAsPainter(
    picture: AnimatedPicture,
    size: Size,
    ops: List<Op>,
    decodeStill: (ByteArray) -> FrameSamples? = { null },
  ) {
    val frames = walk(picture, decodeStill)
    val expected = AnimatedPictures.canvases(size, ops)
    frames.size shouldBe expected.size
    for ((index, truth) in expected.withIndex()) {
      withClue("${ops[index]} as frame $index") {
        frames[index].sixteenBit shouldBe false
        for (texel in truth.indices) {
          val wanted =
            listOf(
              truth[texel] ushr 16 and 0xFF,
              truth[texel] ushr 8 and 0xFF,
              truth[texel] and 0xFF,
              truth[texel] ushr 24,
            )
          withClue("texel ${texel % size.width},${texel / size.width}") {
            samplesAt(frames[index], texel % size.width, texel / size.width) shouldBe wanted.map { it * 257 }
          }
        }
      }
    }
  }

  private fun samplesAt(
    frame: FrameSamples,
    x: Int,
    y: Int,
  ): List<Int> = (0 until 4).map { frame.rgba[(y * frame.width + x) * 4 + it].toInt() and 0xFFFF }

  private fun knownStills(ops: List<Op>): (ByteArray) -> FrameSamples? {
    val stills = ops.map { it to AnimatedWebps.still(AnimatedWebps.chunksOf(it), it.tile.width, it.tile.height) }
    return { bytes ->
      stills.firstOrNull { it.second.contentEquals(bytes) }?.first?.tile?.let { tile ->
        val rgba = ShortArray(tile.width * tile.height * 4)
        for (y in 0 until tile.height) {
          for (x in 0 until tile.width) {
            val argb = tile.texel(x, y)
            val at = (y * tile.width + x) * 4
            rgba[at] = ((argb ushr 16 and 0xFF) * 257).toShort()
            rgba[at + 1] = ((argb ushr 8 and 0xFF) * 257).toShort()
            rgba[at + 2] = ((argb and 0xFF) * 257).toShort()
            rgba[at + 3] = ((argb ushr 24) * 257).toShort()
          }
        }
        FrameSamples(tile.width, tile.height, rgba, false)
      }
    }
  }

  private companion object {
    val SHIFTS = intArrayOf(16, 8, 0, 24)

    // The painter rounds to 8 bits, so a 16-bit result may sit half a code, 257 over 2 rounded up, from its answer.
    const val HALF_CODE = (257 + 1) / 2
  }
}
