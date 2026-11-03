package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
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
      layout.windowNodes(still, "", eachFrame = false) shouldBe
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

  @Test
  fun `a window a command moves is always cut and re-reads its padding each frame when driven`() {
    val still = PixelRect(x = 14, y = 6, width = 86, height = 58)
    val layout = hdrOverlayLayout(listOf(still), FRAME)

    val nodes = layout.windowNodes(still, INSTANCE, eachFrame = true)
    (
      nodes
        .first()
        .arguments
        .single { it.key == "eval" }
        .value
    ) shouldBe "frame"
    nodes.last() shouldBe FilterNode("crop$INSTANCE", "w" to "86", "h" to "58", "x" to "0", "y" to "0")
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
      val light = fragment.chain.takeLast(nodes)

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

  // A picture whose size or place is animated is cut from a canvas padded again on every frame, by its own commands.
  @Test
  fun `a driven picture is scaled and moved by its own commands on every frame`() {
    val (fragment, attributes) = gradedFragment(pictureHeld = false)

    val (picture, mask) = fragment.auxInputs
    picture.chain[0] shouldBe FilterNode("sendcmd", "f" to PICTURE_COMMANDS.placeholder)
    picture.chain[1] shouldBe FilterNode("scale$INSTANCE", "w" to "86", "h" to "58")
    (
      picture.chain
        .single { it.name == "pad" }
        .arguments
        .any { it.key == "eval" && it.value == "frame" }
    ) shouldBe true
    withClue("the picture's window cannot be moved") { picture.chain.any { it.name == "crop$INSTANCE" } shouldBe true }
    picture.timeline shouldBe attributes.auxTimeline()
    mask.chain[0] shouldBe FilterNode("sendcmd", "f" to MASK_COMMANDS.placeholder)
    mask.chain[1] shouldBe FilterNode("scale$INSTANCE$MASK_INSTANCE", "w" to "86", "h" to "58")
    mask.timeline shouldBe attributes.auxTimeline()
    withClue("a command file was dropped") {
      fragment.sidecars.containsAll(listOf(PICTURE_COMMANDS, MASK_COMMANDS)) shouldBe true
    }
  }

  // Only the opacity moves, so the picture is drawn at its first rectangle once and only its alpha follows commands.
  @Test
  fun `a held picture is read once at its first rectangle while its mask follows the commands`() {
    val (fragment, attributes) = gradedFragment(pictureHeld = true)

    val (picture, mask) = fragment.auxInputs
    withClue("the held picture still reads commands") { picture.chain.none { it.name == "sendcmd" } shouldBe true }
    picture.chain[0] shouldBe FilterNode("scale", "w" to "86", "h" to "58")
    withClue("the held picture was looped") { picture.timeline.shouldBeNull() }
    mask.chain[0] shouldBe FilterNode("sendcmd", "f" to MASK_COMMANDS.placeholder)
    mask.timeline shouldBe attributes.auxTimeline()
    withClue("the held picture's commands were still written") {
      (PICTURE_COMMANDS !in fragment.sidecars) shouldBe true
    }
    withClue("the mask's commands were dropped") { (MASK_COMMANDS in fragment.sidecars) shouldBe true }
  }

  private fun gradedFragment(pictureHeld: Boolean): Pair<FilterFragment, Attributes> {
    val spec = ImageOverlay(ImageSource.ofBytes(OrientedPictures.png()), Corner.TopStart)
    val raster = hdrOverlayRaster(storedRead(spec), HdrTransfer.Pq)
    val first = PixelRect(x = 14, y = 6, width = 86, height = 58)
    val layout = hdrOverlayLayout(listOf(first, PixelRect(x = 300, y = 120, width = 86, height = 58)), FRAME)
    val commands = Driven.Commands(PICTURE_COMMANDS, INSTANCE, MASK_COMMANDS, layout, first, pictureHeld)
    val attributes = attributes(HdrTransfer.Pq)

    val graded = GraphPicture.Graded(raster, HdrTransfer.Pq)
    val placed = PlacedPicture(spec, graded, spec.placedOn(FRAME, raster.size), spec.opacity)
    val fragment = placed.gradedOverlay(attributes, attributes.span, graded, commands, first)
    return fragment to attributes
  }

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
    const val INSTANCE = "@fs0"
    val PICTURE_COMMANDS = Sidecar("0.0 crop$INSTANCE x 14;".encodeToByteArray(), "cmd")
    val MASK_COMMANDS = Sidecar("0.0 colorchannelmixer$INSTANCE$MASK_INSTANCE aa 0.5;".encodeToByteArray(), "cmd")
    val ALPHA_TO_RGB =
      FilterNode("colorchannelmixer", "rr" to "0", "ra" to "1", "gg" to "0", "ga" to "1", "bb" to "0", "ba" to "1")
  }
}
