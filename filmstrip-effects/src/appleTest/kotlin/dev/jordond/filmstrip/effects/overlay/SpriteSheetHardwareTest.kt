package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FrameInfo
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.createCodeSpace
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.ATTRIBUTES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.CAPABILITIES
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.FRAME_RECT
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.SPAN
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.at
import dev.jordond.filmstrip.effects.overlay.OverlayFixtures.pixels
import dev.jordond.filmstrip.effects.renderingContext
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.FramePlays
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.test.SpriteSheets
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceITUR_2100_HLG
import platform.CoreGraphics.kCGColorSpaceITUR_2100_PQ
import platform.CoreImage.CIColor
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.posix.memcpy
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * Sprite sheet overlays resolved for Core Image, with the cell each frame draws read back off the frame.
 *
 * Every cell paints its index as eight black or white squares, so a drawn frame is read by what its overlay shows. The
 * expected cell always comes from the timeline the sheet counts its frames on, at the output slot of the time the step
 * is handed. Each read covers the middle of a run and of a cycle as well as its ends, and the HDR cases step back and
 * forth over the cells so a cell whose floats were let go is made again.
 */
@OptIn(ExperimentalForeignApi::class)
class SpriteSheetHardwareTest {
  private val context = renderingContext()

  @Test
  fun `a sheet of one duration draws the timeline's cell on every slot`() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM)
    val spec = overlayOf(sheet)

    assertEverySlot(spec, stepFor(spec, ATTRIBUTES))
  }

  // The middle of the second cycle is in the run, since a cycle is a little over a second.
  @Test
  fun `a sheet of uneven durations draws the timeline's cell on every slot`() {
    val spec = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN))

    val read = assertEverySlot(spec, stepFor(spec, ATTRIBUTES))

    withClue("the run reads every cell of the sheet") { read.distinct().size shouldBe SpriteSheets.UNEVEN.size }
  }

  @Test
  fun `a sheet shown turned draws the cell an upright one does`() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)
    val spec = overlayOf(sheet, SpriteSheets.orientedPng())

    assertEverySlot(spec, stepFor(spec, ATTRIBUTES))
  }

  @Test
  fun `a sixteen bit sheet draws the timeline's cell on every slot`() {
    val spec = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM), SpriteSheets.sixteenBitPng())

    assertEverySlot(spec, stepFor(spec, ATTRIBUTES))
  }

  @Test
  fun `a sheet that plays twice holds its last cell`() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM, plays = FramePlays.Times(2))
    val spec = overlayOf(sheet)

    val read = assertEverySlot(spec, stepFor(spec, ATTRIBUTES), slots = 0 until PLAYED_TWICE_SLOTS)

    read.last() shouldBe sheet.frameCount - 1
  }

  @Test
  fun `a sheet inside a window opens on the window's first cell and draws nothing outside it`() {
    val window = TimeRange.of(1.seconds, 2.seconds)
    val spec = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN), window = window)
    val step = stepFor(spec, ATTRIBUTES)
    val frame = background()

    step.apply(frame, at(0.5.seconds)) shouldBeSameInstanceAs frame
    step.apply(frame, at(2.5.seconds)) shouldBeSameInstanceAs frame
    cellIndexOf(step.apply(frame, at(window.start))) shouldBe 0
    assertEverySlot(spec, step, slots = FIRST_WINDOW_SLOT until LAST_WINDOW_SLOT)
  }

  @Test
  fun `a sheet of one frame draws its only cell on every slot`() {
    val sheet = SpriteSheet(1, 1, UNIFORM)
    val spec = ImageOverlay(ImageSource.ofBytes(SHEET), Corner.TopStart, 0f, SHEET_WIDTH_SCALE, sheet = sheet)
    val step = stepFor(spec, ATTRIBUTES)

    listOf(0, 7, 23, 41).forEach { slot ->
      val drawn = cellIndexOf(step.apply(background(), at(outputSlotTime(slot.toLong(), RATE))))
      withClue("slot $slot") { drawn shouldBe 0 }
    }
  }

  // Both ends of a run agree whether the cell is counted from the slot or from a floor of the time, so the cells
  // are read going forward, then back through the middle of the run, then forward again.
  @Test
  fun `an HDR frame draws the timeline's cell going forward and back`() {
    val spec = overlayOf(SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN))

    HdrTransfer.entries.forEach { transfer ->
      val attributes = hdrAttributes(transfer)
      val step = stepFor(spec, attributes)
      val slots = (0 until RUN_SLOTS) + (RUN_SLOTS - 1 downTo 0 step BACK_STEP) + (0 until RUN_SLOTS step FORWARD_STEP)
      slots.forEach { slot ->
        val time = outputSlotTime(slot.toLong(), RATE)
        val expected = expectedCell(spec, time)
        val drawn = cellIndexOf(step.apply(background(), FrameInfo(attributes, time)), transfer)
        withClue("$transfer slot $slot") { drawn shouldBe expected }
      }
    }
  }

  // The cells are flat colours to their edges, so a texel drawn from beside a cell is a colour the cell does not hold.
  // Every drawn pixel is the cell's own colour over black at some coverage, and nothing else.
  @Test
  fun `a sheet drawn onto an SDR frame between whole texels holds no texel from its neighbours`() {
    val sheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM)
    val spec =
      ImageOverlay(
        ImageSource.ofBytes(SpriteSheets.flatPng()),
        Corner.TopStart,
        BETWEEN_MARGIN,
        BETWEEN_SCALE,
        sheet = sheet,
      )
    val step = stepFor(spec, ATTRIBUTES)

    BLEED_CELLS.forEach { index ->
      val slot = (0 until RUN_SLOTS).first { expectedCell(spec, outputSlotTime(it.toLong(), RATE)) == index }
      val time = outputSlotTime(slot.toLong(), RATE)
      val foreign = SpriteSheets.foreignTexels(texelsOf(pixels(step.apply(background(), at(time)), context)), index)
      withClue("cell $index drew $foreign texels of a neighbour's color") { foreign shouldBe 0 }
    }
  }

  @Test
  fun `a sheet whose width the grid does not divide is refused by name`() {
    val spec = ImageOverlay(ImageSource.ofBytes(SpriteSheets.undivisiblePng()), Corner.TopStart, sheet = fullSheet())

    val refused = resolution(spec, ATTRIBUTES).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldBe sheetNotDivisible(fullSheet(), SpriteSheets.UNDIVISIBLE)
  }

  @Test
  fun `a sheet with no output frame rate is refused by name`() {
    val spec = overlayOf(fullSheet())

    val refused = resolution(spec, attributesAt(frameRate = null)).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldBe FLIPBOOK_NO_GRID
  }

  @Test
  fun `cells that cannot keep two float images under the ceiling are refused by name`() {
    val pixels = decodedPicture(SHEET, asBitmap = true).orFail("the sheet")
    val crops = fullSheet().cropsOver(pixels.size)
    val least = SHEET_BYTES + BAND_BYTES + 2 * CELL_FLOATS

    val refused = pixels.cutInto(crops, drawnAtColor = true, ceiling = least - 1)

    refused.shouldBeInstanceOf<PictureRead.Unsupported>().message shouldBe
      sheetTooLargeToDraw(pixels.size, SpriteSheets.CELL, least - 1)
    val kept =
      pixels
        .cutInto(
          crops,
          drawnAtColor = true,
          ceiling = least,
        ).shouldBeInstanceOf<PictureRead.Read<SheetPixels>>()
    kept.value.capacity shouldBe 2
  }

  @Test
  fun `the count is what the float cache can reach when every cell fits and when only some do`() {
    val pixels = decodedPicture(SHEET, asBitmap = true).orFail("the sheet")
    val crops = fullSheet().cropsOver(pixels.size)
    val held = pixels.held.bytes
    val count = { ceiling: Long -> sheetPeakBytes(held, SpriteSheets.CELL, crops.size, true, ceiling = ceiling) }

    withClue("every cell fits, so the cache may hold all twelve") {
      count(PICTURE_PEAK_CEILING) shouldBe SHEET_BYTES + BAND_BYTES + crops.size * CELL_FLOATS
    }
    val three = SHEET_BYTES + BAND_BYTES + 3 * CELL_FLOATS
    withClue("room for three cells and a part of a fourth holds three") {
      count(three + CELL_FLOATS / 2) shouldBe three
      pixels.cutInto(crops, true, ceiling = three + CELL_FLOATS / 2).orFail("three").capacity shouldBe 3
    }
  }

  @Test
  fun `a budget that fits every cell converts each cell once going forward and back`() {
    val pixels = decodedPicture(SHEET, asBitmap = true).orFail("the sheet")
    val crops = fullSheet().cropsOver(pixels.size)
    val cells =
      pixels
        .cutInto(
          crops,
          true,
          ceiling = SHEET_BYTES + BAND_BYTES + crops.size * CELL_FLOATS,
        ).orFail("cells")

    (crops.indices + crops.indices.reversed() + crops.indices).forEach { index ->
      cellIndexOfCell(cells, crops[index]) shouldBe index
    }

    cells.conversions shouldBe crops.size
  }

  @Test
  fun `a budget of three cells never keeps more than three and still draws the right cells`() {
    val pixels = decodedPicture(SHEET, asBitmap = true).orFail("the sheet")
    val crops = fullSheet().cropsOver(pixels.size)
    val cells = pixels.cutInto(crops, true, ceiling = SHEET_BYTES + BAND_BYTES + 3 * CELL_FLOATS).orFail("cells")

    val order = crops.indices + crops.indices.reversed() + listOf(5, 6, 5, 7, 6, 5, 9, 2, 5)
    order.forEach { index ->
      cellIndexOfCell(cells, crops[index]) shouldBe index
      (cells.kept <= 3) shouldBe true
    }

    cells.kept shouldBe 3
    (cells.conversions > crops.size) shouldBe true
  }

  @Test
  fun `a sheet that never draws at color is not counted against floats it never makes`() {
    val pixels = decodedPicture(SHEET, asBitmap = true).orFail("the sheet")
    val crops = fullSheet().cropsOver(pixels.size)
    val held = checkNotNull(pixels.held.bytes)

    pixels.cutInto(crops, drawnAtColor = false, ceiling = held).shouldBeInstanceOf<PictureRead.Read<SheetPixels>>()
    pixels.cutInto(crops, drawnAtColor = true, ceiling = held).shouldBeInstanceOf<PictureRead.Unsupported>()
  }

  private fun assertEverySlot(
    spec: ImageOverlay,
    step: CoreImageEffect,
    slots: IntRange = 0 until RUN_SLOTS,
  ): List<Int> =
    slots.map { slot ->
      val time = outputSlotTime(slot.toLong(), RATE)
      val drawn = cellIndexOf(step.apply(background(), at(time)))
      withClue("slot $slot at $time") { drawn shouldBe expectedCell(spec, time) }
      drawn
    }

  // The cell the sheet's timeline puts on the slot that holds [time], counted the way every backend counts it.
  private fun expectedCell(
    spec: ImageOverlay,
    time: Duration,
  ): Int = checkNotNull(spec.sheet).timeline.frameAt(spec.drawnRangeWithin(SPAN).start, time, RATE)

  private fun SpriteSheet.cropsOver(shown: Size): List<PictureCrop> =
    flipbookOver(shown).shouldBeInstanceOf<FlipbookRead.Drawn>().flipbook.cells

  private fun fullSheet() = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, UNIFORM)

  private fun overlayOf(
    sheet: SpriteSheet,
    png: ByteArray = SHEET,
    window: TimeRange? = null,
  ) =
    ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart, 0f, ONE_TO_ONE_SCALE, visibleDuring = window, sheet = sheet)

  private fun resolution(
    spec: ImageOverlay,
    attributes: Attributes,
  ): EffectResolution = BuiltInEffectResolver().resolve(spec, CAPABILITIES, attributes)!!

  private fun stepFor(
    spec: ImageOverlay,
    attributes: Attributes,
  ): CoreImageEffect = resolution(spec, attributes).shouldBeInstanceOf<EffectResolution.Resolved>().effect.step

  private fun hdrAttributes(transfer: HdrTransfer) =
    Attributes(FRAME, FRAME, FRAME, ColorSpace.Bt2020, transfer, RATE.toFloat(), SPAN)

  private fun attributesAt(frameRate: Float?) = Attributes(FRAME, FRAME, FRAME, ColorSpace.Bt709, null, frameRate, SPAN)

  private fun background(): CIImage = CIImage(color = CIColor.blackColor).imageByCroppingToRect(FRAME_RECT)

  // The index in the cell drawn one to one at the frame's first corner, read in the codes of the frame it lands on.
  private fun cellIndexOf(
    frame: CIImage,
    transfer: HdrTransfer? = null,
  ): Int {
    val read = cornerCell(frame, transfer)
    val cell =
      IntArray(SpriteSheets.CELL.width * SpriteSheets.CELL.height) { index ->
        val offset = index * CHANNELS
        if ((read[offset] + read[offset + 1] + read[offset + 2]) / RGB > HALF) WHITE else BLACK
      }
    return SpriteSheets.indexOf(cell)
  }

  // The index in a cell's float image drawn onto an HDR frame at its first corner.
  private fun cellIndexOfCell(
    cells: SheetPixels,
    crop: PictureCrop,
  ): Int {
    val image = cells.enteringFrame(crop, HdrTransfer.Pq, ColorSpace.Bt2020)
    val placement = OverlayPlacement(cells.cell, Anchor(0f, 0f), Anchor(0f, 0f))
    val frame = image.compositedOnto(background(), placement, FRAME, 1f, HdrTransfer.Pq, ColorSpace.Bt2020)
    return cellIndexOf(frame, HdrTransfer.Pq)
  }

  // The texels of the frame's first cell, top row first, as red, green, blue and alpha floats.
  private fun cornerCell(
    frame: CIImage,
    transfer: HdrTransfer?,
  ): FloatArray {
    val cell = SpriteSheets.CELL
    val space =
      when (transfer) {
        null -> ColorSpace.Bt709.createCodeSpace()
        HdrTransfer.Pq -> CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_PQ)
        HdrTransfer.Hlg -> CGColorSpaceCreateWithName(kCGColorSpaceITUR_2100_HLG)
      }
    val floats = FloatArray(cell.width * cell.height * CHANNELS)
    floats.usePinned { pinned ->
      context.render(
        image = frame,
        toBitmap = pinned.addressOf(0),
        rowBytes = (cell.width * CHANNELS * Float.SIZE_BYTES).toLong(),
        bounds =
          CGRectMake(
            0.0,
            (FRAME.height - cell.height).toDouble(),
            cell.width.toDouble(),
            cell.height.toDouble(),
          ),
        format = kCIFormatRGBAf,
        colorSpace = space,
      )
    }
    CGColorSpaceRelease(space)
    return floats
  }

  // The frame's texels as packed ARGB codes, so the shared fixture's reader can say which hold a foreign color.
  private fun texelsOf(drawn: FloatArray): IntArray =
    IntArray(FRAME.width * FRAME.height) { texel ->
      val channel = { offset: Int -> (drawn[texel * CHANNELS + offset] * MAX_CODE).roundToInt() }
      (channel(0) shl RED_SHIFT) or (channel(1) shl GREEN_SHIFT) or channel(2)
    }

  private companion object {
    val SHEET = SpriteSheets.png()
    val UNIFORM = 40.milliseconds
    const val RATE = 30
    const val RUN_SLOTS = 60
    const val PLAYED_TWICE_SLOTS = 40
    const val FIRST_WINDOW_SLOT = 30
    const val LAST_WINDOW_SLOT = 60
    const val BACK_STEP = 3
    const val FORWARD_STEP = 5

    // The cell is 32 texels wide and a scale is a fraction of the frame's width.
    val ONE_TO_ONE_SCALE = SpriteSheets.CELL.width.toFloat() / FRAME.width
    val SHEET_WIDTH_SCALE = SpriteSheets.SHEET.width.toFloat() / FRAME.width

    const val SHEET_BYTES = 128L * 48 * 4
    const val BAND_BYTES = 32L * 16 * 48
    const val CELL_FLOATS = 32L * 16 * 16

    const val CHANNELS = 4
    const val RGB = 3
    const val HALF = 0.5f
    const val MAX_CODE = 255f
    const val BYTE = 0xFF
    const val BYTE_BITS = 8uL
    const val OPAQUE_BYTE: Byte = -1
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val WHITE = 0xFFFFFFFF.toInt()
    const val BLACK = 0xFF000000.toInt()
    const val BLEND_TOLERANCE = 3f
    const val SCALE_BETWEEN = 1.375f
    val BLEED_CELLS = listOf(5, 6, 9)
    const val BETWEEN_OFFSET = 33.3f
    val BETWEEN_SCALE = SpriteSheets.CELL.width * SCALE_BETWEEN / FRAME.width
    val BETWEEN_MARGIN = BETWEEN_OFFSET / FRAME.height
  }
}
