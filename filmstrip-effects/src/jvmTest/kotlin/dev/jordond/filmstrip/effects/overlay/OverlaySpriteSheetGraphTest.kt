package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.StillRepeat
import dev.jordond.filmstrip.effects.FRAME
import dev.jordond.filmstrip.effects.attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.test.SpriteSheets
import dev.jordond.filmstrip.test.evaluateFrameTime
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The graph a sprite sheet overlay lowers to on the filter graph backend, and the limits it is refused at.
 */
class OverlaySpriteSheetGraphTest {
  // Both timelines are read on every slot of two seconds, so a run's first slot, the slots in the middle of it and the
  // slot a cell repeats on are all compared against the flipbook's own answer.
  @Test
  fun `the crop runs open on the cells the flipbook gives for every slot`() {
    for (sheet in listOf(even(), uneven())) {
      val fragment = sdrFragment(overlayOf(sheet))
      val crop =
        fragment.auxInputs
          .single()
          .chain
          .single { it.name == "crop" }
      val flipbook = flipbookOf(sheet)
      val first = outputSlotOf(SPAN.start, RATE)

      slotsOf(SPAN).forEach { index ->
        val cell = flipbook.cellOnSlot(SPAN.start, first + index, RATE)
        val t = index * (1.0 / RATE)
        withClue("slot $index of ${sheet.frameDurations.first()}") {
          evaluateFrameTime(crop.argument("x"), t) shouldBe cell.left.toDouble()
          evaluateFrameTime(crop.argument("y"), t) shouldBe cell.top.toDouble()
        }
      }
      crop.argument("w") shouldBe SpriteSheets.CELL.width.toString()
      crop.argument("h") shouldBe SpriteSheets.CELL.height.toString()
    }
  }

  @Test
  fun `the sheet is opened once and repeated by the graph`() {
    val fragment = sdrFragment(overlayOf(uneven()))
    val aux = fragment.auxInputs.single()
    val slots = slotsOf(SPAN).count()

    aux.repeat shouldBe StillRepeat.Graph
    aux.timeline.shouldNotBeNull()
    aux.chain.map { it.name }.take(3) shouldBe listOf("loop", "format", "crop")
    aux.chain.first() shouldBe FilterNode("loop", "loop" to (slots + 1).toString(), "size" to "1")
    aux.chain[1] shouldBe FilterNode("format", "pix_fmts" to "rgba")
  }

  @Test
  fun `a picture drawn whole is still repeated by the demuxer`() {
    val whole = ImageOverlay(ImageSource.ofBytes(SpriteSheets.png()), Corner.TopStart)

    sdrFragment(whole).auxInputs.single().repeat shouldBe StillRepeat.Demuxer
  }

  // The sheet is stored turned a quarter, so the cut has to come after the node that lays it out as shown.
  @Test
  fun `an oriented sheet is laid out before it is cut`() {
    val spec = ImageOverlay(ImageSource.ofBytes(SpriteSheets.orientedPng()), Corner.TopStart, sheet = uneven())
    val chain = sdrFragment(spec).auxInputs.single().chain

    chain.map { it.name }.take(4) shouldBe listOf("transpose", "loop", "format", "crop")
    chain.single { it.name == "crop" }.argument("w") shouldBe SpriteSheets.CELL.width.toString()
  }

  @Test
  fun `a sheet of one frame cuts the one cell on every slot`() {
    val one = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 100.milliseconds, frameCount = 1)
    val crop =
      sdrFragment(overlayOf(one))
        .auxInputs
        .single()
        .chain
        .single { it.name == "crop" }

    crop shouldBe
      FilterNode(
        "crop",
        "w" to SpriteSheets.CELL.width.toString(),
        "h" to SpriteSheets.CELL.height.toString(),
        "x" to "0",
        "y" to "0",
      )
  }

  @Test
  fun `a sheet is placed by the aspect of its cell`() {
    val spec = overlayOf(uneven())
    val cell = SpriteSheets.CELL
    val drawn = drawnRect(spec.placedOn(FRAME, cell), FRAME)
    val merge = sdrFragment(spec).merge.shouldNotBeNull()

    merge.argument("x") shouldBe drawn.x.toString()
    sdrFragment(spec)
      .auxInputs
      .single()
      .chain
      .single { it.name == "scale" }
      .let {
        it.argument("w") shouldBe drawn.width.toString()
        it.argument("h") shouldBe drawn.height.toString()
      }
  }

  @Test
  fun `a window cuts the sheet from where it opens`() {
    val window = 500.milliseconds..1500.milliseconds
    val spec =
      ImageOverlay(
        ImageSource.ofBytes(SpriteSheets.png()),
        Corner.TopStart,
        visibleDuring = TimeRange(window),
        sheet = uneven(),
      )
    val crop =
      sdrFragment(spec)
        .auxInputs
        .single()
        .chain
        .single { it.name == "crop" }
    val flipbook = flipbookOf(uneven())
    val drawnStart = spec.drawnRangeWithin(SPAN).start
    val first = outputSlotOf(SPAN.start, RATE)

    // Frame 0 starts on the window's first slot, and the middle of the window is a cell that is not frame 0.
    listOf(15, 21, 30, 44).forEach { index ->
      val cell = flipbook.cellOnSlot(drawnStart, first + index, RATE)
      withClue("slot $index") {
        evaluateFrameTime(crop.argument("x"), index * (1.0 / RATE)) shouldBe
          cell.left.toDouble()
      }
    }
  }

  @Test
  fun `on an HDR grade both branches are cut to the cell and repeated by the graph`() {
    for (transfer in listOf(HdrTransfer.Pq, HdrTransfer.Hlg)) {
      val fragment = hdrFragment(overlayOf(uneven()), transfer)
      val flipbook = flipbookOf(uneven())
      val first = outputSlotOf(SPAN.start, RATE)

      fragment.auxInputs shouldHaveSize 2
      fragment.auxInputs.forEach { aux ->
        withClue("$transfer") {
          aux.repeat shouldBe StillRepeat.Graph
          aux.timeline.shouldNotBeNull()
          aux.chain.map { it.name }.take(3) shouldBe listOf("loop", "format", "crop")
          aux.chain[1] shouldBe FilterNode("format", "pix_fmts" to "rgba64le")
          val crop = aux.chain[2]
          slotsOf(SPAN).forEach { index ->
            val cell = flipbook.cellOnSlot(SPAN.start, first + index, RATE)
            evaluateFrameTime(crop.argument("x"), index * (1.0 / RATE)) shouldBe cell.left.toDouble()
            evaluateFrameTime(crop.argument("y"), index * (1.0 / RATE)) shouldBe cell.top.toDouble()
          }
        }
      }
    }
  }

  @Test
  fun `a sheet needs a frame rate`() {
    refusal(overlayOf(uneven()), attributes = sdr(frameRate = null)) shouldBe FLIPBOOK_NO_GRID
    refusal(overlayOf(uneven()), attributes = sdr(frameRate = 0f)) shouldBe FLIPBOOK_NO_GRID
  }

  @Test
  fun `a sheet needs an end to its drawn range`() {
    refusal(overlayOf(uneven()), attributes = sdr(span = TimeRange.from(Duration.ZERO))) shouldBe FLIPBOOK_NO_END

    val window =
      ImageOverlay(
        ImageSource.ofBytes(SpriteSheets.png()),
        Corner.TopStart,
        visibleDuring = TimeRange.of(1.seconds, 2.seconds),
        sheet = uneven(),
      )
    refusal(window, attributes = sdr(span = TimeRange.from(Duration.ZERO))) shouldBe FLIPBOOK_NO_END
  }

  @Test
  fun `a picture the grid does not divide is refused by name`() {
    val spec = ImageOverlay(ImageSource.ofBytes(SpriteSheets.undivisiblePng()), Corner.TopStart, sheet = uneven())

    refusal(spec) shouldBe sheetNotDivisible(uneven(), SpriteSheets.UNDIVISIBLE)
  }

  // The ceiling is injected, so the sheet that is refused is the one that is an exact byte past it and the one that is
  // an exact byte under it draws.
  @Test
  fun `a sheet past the ceiling is refused by name`() {
    val bytes = SpriteSheets.SHEET.width.toLong() * SpriteSheets.SHEET.height * SDR_TEXEL_BYTES
    val spec = overlayOf(uneven())

    refusal(spec, ceiling = bytes - 1) shouldBe sheetTooLargeToDraw(SpriteSheets.SHEET, SDR_TEXEL_BYTES, bytes - 1)
    filterGraphOverlay(spec, sdr(), null, ceiling = bytes).shouldBeInstanceOf<EffectResolution.Resolved>()

    val wide = SpriteSheets.SHEET.width.toLong() * SpriteSheets.SHEET.height * HDR_TEXEL_BYTES
    val hdr = filterGraphOverlay(spec, attributes(HdrTransfer.Pq), null, ceiling = wide - 1)
    hdr.shouldBeInstanceOf<EffectResolution.Unsupported>().message shouldContain "too large"
    filterGraphOverlay(spec, attributes(HdrTransfer.Pq), null, ceiling = wide)
      .shouldBeInstanceOf<EffectResolution.Resolved>()
  }

  // The boundary measured with raw ffmpeg 8.0.1 and 9.0.2: these sizes open and the next row or column does not.
  @Test
  fun `ffmpeg's frame size limit is drawn up to and refused past the measured boundary`() {
    listOf(Size(16255, 16255), Size(8000, 32898), Size(4000, 64899), Size(30000, 8781)).forEach {
      withClue("$it") { sheetWithinFrameLimit(it) shouldBe true }
    }
    listOf(Size(16256, 16256), Size(8000, 32899), Size(4000, 64900), Size(30000, 8782)).forEach {
      withClue("$it") { sheetWithinFrameLimit(it) shouldBe false }
    }
  }

  // The limit is injected so a sheet of the fixture's size meets it, and the message says whose limit it is.
  @Test
  fun `a sheet past ffmpeg's frame size limit is refused by name`() {
    val spec = overlayOf(uneven())
    val area = (SpriteSheets.SHEET.width + 128L) * (SpriteSheets.SHEET.height + 128L)

    filterGraphOverlay(spec, sdr(), null, frameLimit = area + 1).shouldBeInstanceOf<EffectResolution.Resolved>()
    val refused =
      filterGraphOverlay(
        spec,
        sdr(),
        null,
        frameLimit = area,
      ).shouldBeInstanceOf<EffectResolution.Unsupported>()
    refused.message shouldBe sheetPastFrameLimit(SpriteSheets.SHEET)
    refused.message shouldContain "ffmpeg's frame size limit"
    filterGraphOverlay(spec, attributes(HdrTransfer.Pq), null, frameLimit = area)
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
  }

  private fun FilterNode.argument(key: String): String = arguments.single { it.key == key }.value

  private fun slotsOf(span: TimeRange): IntRange {
    val rate = RATE
    val end = checkNotNull(span.endExclusive)
    return 0 until (outputSlotOf(end, rate) - outputSlotOf(span.start, rate)).toInt()
  }

  private fun even(): SpriteSheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, 150.milliseconds)

  private fun uneven(): SpriteSheet = SpriteSheet(SpriteSheets.COLUMNS, SpriteSheets.ROWS, SpriteSheets.UNEVEN)

  private fun overlayOf(sheet: SpriteSheet): ImageOverlay =
    ImageOverlay(ImageSource.ofBytes(SpriteSheets.png()), Corner.TopStart, sheet = sheet)

  private fun flipbookOf(sheet: SpriteSheet): Flipbook =
    sheet.flipbookOver(SpriteSheets.SHEET).shouldBeInstanceOf<FlipbookRead.Drawn>().flipbook

  private fun sdrFragment(spec: ImageOverlay): FilterFragment =
    filterGraphOverlay(spec, sdr(), null).shouldBeInstanceOf<EffectResolution.Resolved>().effect.fragment

  private fun hdrFragment(
    spec: ImageOverlay,
    transfer: HdrTransfer,
  ): FilterFragment =
    filterGraphOverlay(spec, attributes(transfer), null).shouldBeInstanceOf<EffectResolution.Resolved>().effect.fragment

  private fun refusal(
    spec: ImageOverlay,
    attributes: Attributes = sdr(),
    ceiling: Long = Long.MAX_VALUE,
  ): String =
    filterGraphOverlay(spec, attributes, null, ceiling = ceiling)
      .shouldBeInstanceOf<EffectResolution.Unsupported>()
      .message

  private fun sdr(
    span: TimeRange = SPAN,
    frameRate: Float? = RATE.toFloat(),
  ): Attributes {
    val base = attributes(null)
    return Attributes(
      inputSize = FRAME,
      outputSize = base.outputSize,
      layoutSize = base.layoutSize,
      colorSpace = base.colorSpace,
      hdrTransfer = null,
      frameRate = frameRate,
      span = span,
    )
  }

  private companion object {
    const val RATE = 30
    const val SDR_TEXEL_BYTES = 4
    const val HDR_TEXEL_BYTES = 8
    val SPAN = TimeRange.of(Duration.ZERO, 2.seconds)
  }
}
