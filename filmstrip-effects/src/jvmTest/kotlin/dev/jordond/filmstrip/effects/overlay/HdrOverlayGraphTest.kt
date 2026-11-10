package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.FRAME
import dev.jordond.filmstrip.effects.attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Where an overlay on an HDR grade lands, how its picture is laid out to line up with the region it is blended on, and
 * the fragment that blends it.
 */
class HdrOverlayGraphTest {
  @Test
  fun `a still lands on the rectangle placedOn gives it in every corner`() {
    for (picture in listOf(OrientedPictures.SIZE, HALF_ROW)) {
      for (corner in Corner.entries) {
        val spec = ImageOverlay(ImageSource.ofBytes(ByteArray(0)), corner)
        val placed = spec.placedOn(FRAME, picture)
        val rect = placed.rectOn(FRAME)

        val still = drawnRect(placed, FRAME)
        withClue("$picture $corner") { Size(still.width, still.height) shouldBe placed.size }
        withClue("$picture $corner") { still.x shouldBe (rect.left * FRAME.width).roundToInt() }
        withClue("$picture $corner") { still.y shouldBe (rect.top * FRAME.height).roundToInt() }
      }
    }
  }

  @Test
  fun `a region is widened to even pixels and the picture sits on the canvas where the frame shows it`() {
    val still = PixelRect(x = 15, y = 7, width = 85, height = 57)
    val layout = hdrOverlayLayout(listOf(still), FRAME)

    assertRect(PixelRect(14, 6, 86, 58), layout.region)
    layout.canvas shouldBe Size(86, 58)
    (layout.left to layout.top) shouldBe (1 to 1)
    withClue("a window covering the whole canvas from its corner still took a crop") {
      layout.windowNodes(heldWindow(layout, still), RATE) shouldBe
        listOf(FilterNode("pad", "w" to "86", "h" to "58", "x" to "1", "y" to "1", "color" to "black@0"))
    }
  }

  @Test
  fun `every drawn position lines up with the region on a canvas that holds every window`() {
    val side = 85
    val drawn =
      listOf(-200, -40, 15, 301, 600, 900).map { x -> PixelRect(x, FRAME.height - side - x / 4, side, side) }
    val layout = hdrOverlayLayout(drawn, FRAME)
    val region = layout.region

    withClue("the region is cut off a chroma sample") { region.x % 2 shouldBe 0 }
    withClue("the region is cut off a chroma sample") { region.y % 2 shouldBe 0 }
    withClue("off the frame") {
      (region.x + region.width <= FRAME.width && region.y + region.height <= FRAME.height) shouldBe true
    }
    for (at in drawn) {
      val windowX = layout.windowX(at.x)
      val windowY = layout.windowY(at.y)
      withClue("${at.x} reads off the canvas") {
        (windowX >= 0 && windowX + region.width <= layout.canvas.width) shouldBe true
      }
      withClue("${at.y} reads off the canvas") {
        (windowY >= 0 && windowY + region.height <= layout.canvas.height) shouldBe true
      }
      withClue("the picture drawn at ${at.y} is not where the frame shows it") {
        windowY + at.y - region.y shouldBe layout.top
      }
      val onFrame = at.x + at.width > region.x && at.x < region.x + region.width
      if (onFrame) {
        withClue("the picture drawn at ${at.x} is not where the frame shows it") {
          windowX + at.x - region.x shouldBe layout.left
        }
      } else {
        withClue("${at.x} reads the picture") {
          (windowX >= layout.left + at.width || windowX + region.width <= layout.left) shouldBe true
        }
      }
    }
  }

  // The window holds at the canvas's corner, but it moves on every other frame, so it is cut on all of them.
  @Test
  fun `a window that moves is always cut and re-reads its padding each frame when the size moves`() {
    val still = PixelRect(x = 14, y = 6, width = 86, height = 58)
    val layout = hdrOverlayLayout(listOf(still), FRAME)
    val moving = FrameRuns(listOf(FrameRun(0, "0"), FrameRun(3, "2")))
    val window = HdrWindow(moving, heldAt(0), moving, heldAt(still.height))

    val nodes = layout.windowNodes(window, RATE)
    (
      nodes
        .first()
        .arguments
        .single { it.key == "eval" }
        .value
    ) shouldBe "frame"
    nodes.last() shouldBe
      FilterNode("crop", "w" to "86", "h" to "58", "x" to moving.on(branchFrameClock(RATE)), "y" to "0")
  }

  @Test
  fun `the fragment cuts the region out exactly and blends the picture by its own alpha`() {
    for (transfer in HdrTransfer.entries) {
      val spec = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.BottomEnd, opacity = HALF)
      val fragment = hdrFragment(spec, transfer)
      val placed = spec.placedOn(FRAME, OrientedPictures.SIZE)
      val region = hdrOverlayLayout(listOf(drawnRect(placed, FRAME)), FRAME).region

      val crop = fragment.crop.shouldNotBeNull()
      withClue("$transfer") {
        crop shouldBe
          FilterCrop(
            width = region.width.toString(),
            height = region.height.toString(),
            x = region.x.toString(),
            y = region.y.toString(),
            exact = true,
          )
      }
      withClue("$transfer") { fragment.merge shouldBe FilterNode("maskedmerge") }

      val (picture, mask) = fragment.auxInputs
      val raster = hdrOverlayRaster(storedRead(spec), transfer)
      withClue("$transfer") { picture.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe raster.png }
      withClue("$transfer") { mask.image.shouldBeInstanceOf<ImageSource.Bytes>().bytes shouldBe raster.png }
      withClue("the picture's color took the opacity") {
        picture.chain.none {
          it.arguments.any { arg ->
            arg.key == "aa"
          }
        } shouldBe true
      }
      withClue("the mask lost the opacity") {
        (FilterNode("colorchannelmixer", "aa" to HALF.toString()) in mask.chain) shouldBe true
      }
      withClue("the mask's color planes do not carry its alpha") { (ALPHA_TO_RGB in mask.chain) shouldBe true }
      withClue("a table is written twice") { fragment.sidecars.distinct().size shouldBe fragment.sidecars.size }
    }
  }

  // HLG's display light needs a gain read off all three channels, a geq lut1d's per-channel tables cannot apply.
  @Test
  fun `the picture is decoded to light the way the region it lands on is`() {
    for ((transfer, nodes) in listOf(HdrTransfer.Pq to 2, HdrTransfer.Hlg to 3)) {
      val fragment = hdrFragment(ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart), transfer)
      // The region's light is premultiplied by its alpha after it is decoded, by one geq.
      val light = fragment.chain.dropLast(1).takeLast(nodes)

      withClue("$transfer") { fragment.auxInputs[0].chain.takeLast(nodes) shouldBe light }
      withClue("$transfer") { (light.last().name == "geq") shouldBe (transfer == HdrTransfer.Hlg) }
      withClue("$transfer $light") { light.take(2).all { it.name == "lut1d" } shouldBe true }
    }
  }

  // maskedmerge holds a single frame under every frame of the region, so a branch nothing changes is read once.
  @Test
  fun `a still picture is read once and only a gated mask is looped`() {
    val image = ImageSource.ofBytes(OrientedPictures.png())
    val still = hdrFragment(ImageOverlay(image, Corner.TopStart), HdrTransfer.Pq).auxInputs
    val window = TimeRange.of(500.milliseconds, 1500.milliseconds)
    val gated = hdrFragment(ImageOverlay(image, Corner.TopStart, visibleDuring = window), HdrTransfer.Pq).auxInputs

    still[0].timeline.shouldBeNull()
    still[1].timeline.shouldBeNull()
    withClue("the gated overlay's color was looped") { gated[0].timeline.shouldBeNull() }
    gated[1].timeline shouldBe attributes(HdrTransfer.Pq).auxTimeline()
  }

  // A picture whose size or place is animated is cut from a canvas padded again on every frame, by expressions of the
  // branch's own time. The format sits ahead of a scale whose size moves, since a converter after it pins the size.
  @Test
  fun `a moving picture is scaled and cut by its own frame time on every frame`() {
    val growing =
      OverlayAnimation { time ->
        val ramp = (time.elapsed / RAMP).toFloat().coerceIn(0f, 1f)
        OverlayFrame(opacity = ramp, offset = OverlayOffset(ramp / 2, ramp / 4), scale = 1f + ramp)
      }
    val attributes = attributes(HdrTransfer.Pq)
    val fragment =
      hdrFragment(
        ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart, animation = growing),
        HdrTransfer.Pq,
      )
    val (picture, mask) = fragment.auxInputs
    val sized = Regex("^st\\(0,${Regex.escape(branchFrameClock(RATE))}\\);")

    picture.chain.map { it.name }.take(4) shouldBe listOf("format", "scale", "pad", "crop")
    picture.chain[1]
      .arguments
      .first { it.key == "w" }
      .value
      .contains(sized) shouldBe true
    picture.chain[1].arguments.last() shouldBe FilterArgument("eval", "frame")
    picture.chain[2].arguments.last() shouldBe FilterArgument("eval", "frame")
    withClue("the picture's window does not move") {
      picture.chain[3]
        .arguments
        .first { it.key == "x" }
        .value
        .contains(sized) shouldBe true
    }
    picture.timeline shouldBe attributes.auxTimeline()
    withClue("the fade does not run ahead of the size that moves") {
      mask.chain.map { it.name }.take(5) shouldBe listOf("format", "format", "geq", "format", "scale")
    }
    mask.chain[1] shouldBe FilterNode("format", "pix_fmts" to "gbrap16le")
    mask.timeline shouldBe attributes.auxTimeline()
    withClue("a branch was driven by a command file") { fragment.sidecars.none { it.extension == "cmd" } shouldBe true }
  }

  // Only the opacity moves, so the picture is drawn at its rectangle once and only its alpha reads the clock.
  @Test
  fun `a fading picture is read once while its mask fades after the scale`() {
    val attributes = attributes(HdrTransfer.Pq)
    val spec =
      ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart, animation = fade(inDuration = RAMP))
    val fragment = hdrFragment(spec, HdrTransfer.Pq)
    val still = drawnRect(spec.placedOn(FRAME, OrientedPictures.SIZE), FRAME)

    val (picture, mask) = fragment.auxInputs
    picture.chain[0] shouldBe FilterNode("scale", "w" to still.width.toString(), "h" to still.height.toString())
    withClue("the held picture was looped") { picture.timeline.shouldBeNull() }
    mask.chain.map { it.name }.take(5) shouldBe listOf("scale", "format", "format", "geq", "format")
    mask.chain[3]
      .arguments
      .first {
        it.key == "a"
      }.value
      .startsWith("alpha(X,Y)*(st(0,${branchPlaneClock(RATE)});") shouldBe
      true
    mask.timeline shouldBe attributes.auxTimeline()
  }

  // A window that holds its place and size, as the still placement draws it.
  private fun heldWindow(
    layout: HdrOverlayLayout,
    at: PixelRect,
  ): HdrWindow =
    HdrWindow(heldAt(layout.windowX(at.x)), heldAt(layout.windowY(at.y)), heldAt(at.width), heldAt(at.height))

  private fun hdrFragment(
    spec: ImageOverlay,
    transfer: HdrTransfer,
  ): FilterFragment =
    withClue("$transfer") {
      filterGraphOverlay(spec, attributes(transfer), null).shouldBeInstanceOf<EffectResolution.Resolved>()
    }.effect.fragment

  private fun storedRead(spec: ImageOverlay): StoredPicture =
    storedPicture(spec.image, null).shouldBeInstanceOf<StoredRead.Read>().picture

  private fun assertRect(
    expected: PixelRect,
    actual: PixelRect,
  ) {
    listOf(actual.x, actual.y, actual.width, actual.height) shouldBe
      listOf(expected.x, expected.y, expected.width, expected.height)
  }

  private companion object {
    // A width of 128 puts this picture's height at one and a half pixels, which both readings round the same way.
    val HALF_ROW = Size(256, 3)
    const val HALF = 0.5f
    const val RATE = 30
    val RAMP = 1.seconds
    val ALPHA_TO_RGB =
      FilterNode("colorchannelmixer", "rr" to "0", "ra" to "1", "gg" to "0", "ga" to "1", "bb" to "0", "ba" to "1")
  }
}
