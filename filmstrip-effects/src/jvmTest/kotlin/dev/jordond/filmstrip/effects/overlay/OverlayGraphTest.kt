package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.AuxTimeline
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.FRAME
import dev.jordond.filmstrip.effects.attributes
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.OrientedPictures
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

/**
 * The fragment [filterGraphOverlay] builds for an overlay on an SDR grade, and the nodes that lay a turned picture out.
 */
class OverlayGraphTest {
  @Test
  fun `a still overlay is scaled to its share of the frame and merged at the corner placedOn puts it`() {
    for (corner in Corner.entries) {
      val spec = ImageOverlay(PICTURE, corner, margin = MARGIN)
      val fragment = sdrFragment(spec)
      val drawn = drawnRect(spec.placedOn(FRAME, OrientedPictures.SIZE), FRAME)

      val image = fragment.auxInputs.single()
      withClue("$corner") { image.image shouldBe PICTURE }
      withClue("$corner") {
        image.chain shouldBe
          listOf(
            FilterNode("scale", "w" to drawn.width.toString(), "h" to drawn.height.toString()),
            FilterNode("format", "pix_fmts" to "rgba"),
          )
      }
      withClue("$corner") { fragment.merge shouldBe mergeAt(drawn) }
    }
  }

  // An odd frame width, where a scale of w and -1 and the shared placement round the height and the far edge apart.
  @Test
  fun `a still on an odd frame lands where the shared placement puts it`() {
    val frame = Size(ODD_WIDTH, FRAME.height)
    for (corner in Corner.entries) {
      val spec = ImageOverlay(PICTURE, corner, margin = ODD_MARGIN, scale = ODD_SCALE)
      val placed = spec.placedOn(frame, OrientedPictures.SIZE)
      val fragment = sdrFragment(spec, sdr(inputSize = frame))
      val drawn = drawnRect(placed, frame)

      val rect = placed.rectOn(frame)
      withClue("$corner") { drawn.x shouldBe (rect.left * frame.width).roundToInt() }
      withClue("$corner") { drawn.y shouldBe (rect.top * frame.height).roundToInt() }
      withClue("$corner") {
        fragment.auxInputs
          .single()
          .chain
          .first() shouldBe
          FilterNode("scale", "w" to placed.size.width.toString(), "h" to placed.size.height.toString())
      }
      withClue("$corner") { fragment.merge shouldBe mergeAt(drawn) }
    }
  }

  // The margin is a share of the shorter side, so a portrait frame measures it off its width.
  @Test
  fun `the margin is measured off the shorter side of the frame the overlay is drawn on`() {
    val portrait = Size(FRAME.height, FRAME.width)
    val spec = ImageOverlay(PICTURE, Corner.TopStart, margin = MARGIN)
    val fragment = sdrFragment(spec, sdr(inputSize = portrait))

    val expected = (portrait.width * MARGIN).roundToInt().toString()
    val merge = checkNotNull(fragment.merge).arguments
    merge[0] shouldBe FilterArgument("x", expected)
    merge[1] shouldBe FilterArgument("y", expected)
  }

  @Test
  fun `a still image is repeated at the output rate across the span it merges onto`() {
    val image = sdrFragment(ImageOverlay(PICTURE, Corner.TopStart)).auxInputs.single()

    image.timeline shouldBe AuxTimeline(RATE, SPAN_LENGTH)
  }

  @Test
  fun `an overlay below full opacity takes a mixer on its alpha and an opaque one takes none`() {
    val faded = sdrFragment(ImageOverlay(PICTURE, Corner.TopStart, opacity = HALF)).auxInputs.single()
    val opaque = sdrFragment(ImageOverlay(PICTURE, Corner.TopStart)).auxInputs.single()

    faded.chain.last() shouldBe FilterNode("colorchannelmixer", "aa" to HALF.toString())
    withClue("${opaque.chain}") { opaque.chain.none { it.name == "colorchannelmixer" } shouldBe true }
  }

  // Each bound sits half a frame ahead of the first frame at or after it, so a frame on either side of the bound reads
  // the right side of it however the division rounds.
  @Test
  fun `a window gates the branch half a frame ahead of the first frame at or after each bound`() {
    val spec = ImageOverlay(PICTURE, Corner.TopStart, visibleDuring = TimeRange.of(WINDOW_START, WINDOW_END))

    val gate =
      sdrFragment(spec)
        .auxInputs
        .single()
        .chain
        .last()
    val open = boundOf(WINDOW_START)
    val close = boundOf(WINDOW_END)
    gate shouldBe FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not(gte(t,$open)*lt(t,$close))")
    val frames = (0 until (SPAN_LENGTH.toDouble(DurationUnit.SECONDS) * RATE).toInt())
    val gated = frames.filter { (it / RATE.toDouble()).let { t -> t >= open && t < close } }
    val shown = frames.filter { (it * 1_000_000L / RATE.toLong()).microseconds in checkNotNull(spec.visibleDuring) }
    gated shouldBe shown
  }

  @Test
  fun `without an output rate a window is gated at its times on the branch's clock`() {
    val span = TimeRange.of(1.seconds, 3.seconds)
    val window = TimeRange.of(1500.milliseconds, 2500.milliseconds)
    val spec = ImageOverlay(PICTURE, Corner.TopStart, visibleDuring = window)

    val image = sdrFragment(spec, sdr(span = span, frameRate = null)).auxInputs.single()
    image.chain.last() shouldBe FilterNode("colorchannelmixer", "aa" to "0", "enable" to "not(gte(t,0.5)*lt(t,1.5))")
    withClue("a branch with no rate to repeat its still at was given a timeline") { image.timeline.shouldBeNull() }
  }

  // The commands resize the branch, and the converter ffmpeg puts ahead of the merge would undo that unless a scale of
  // the branch's own size re-reads it every frame.
  @Test
  fun `an animated overlay is driven by its commands and carries each resize past the merge`() {
    val spec = ImageOverlay(PICTURE, Corner.BottomEnd, animation = slideIn(OverlayOffset(0f, SLIDE), 1.seconds))
    val attributes = sdr()
    val fragment = sdrFragment(spec, attributes)
    val driven = fragment.sidecars.single()
    val instance = checkNotNull(fragment.merge).name.removePrefix("overlay")
    val placed = spec.placedOn(FRAME, OrientedPictures.SIZE).size

    (
      fragment.auxInputs
        .single()
        .chain
    ) shouldBe
      listOf(
        FilterNode("sendcmd", "f" to driven.placeholder),
        FilterNode("scale$instance", "w" to placed.width.toString(), "h" to placed.height.toString()),
        FilterNode("format", "pix_fmts" to "rgba"),
        FilterNode("colorchannelmixer$instance", "aa" to "1"),
        FilterNode("scale", "w" to "iw", "h" to "ih", "eval" to "frame"),
      )
    withClue("the merge is not the instance the commands drive") { instance.startsWith("@fs") shouldBe true }
  }

  @Test
  fun `a turned picture is laid out by its orientation ahead of the scale`() {
    for (orientation in PictureOrientation.entries) {
      val png = OrientedPictures.taggedPng(OrientedPictures.png(), orientation.exif)
      val chain = sdrFragment(ImageOverlay(ImageSource.ofBytes(png), Corner.TopStart)).auxInputs.single().chain

      val turn = orientationNodes(orientation)
      withClue("$orientation") { chain.take(turn.size) shouldBe turn }
      withClue("$orientation") { chain[turn.size].name shouldBe "scale" }
    }
  }

  // Each node is run here the way ffmpeg moves texels, so the nodes are held to the layout core gives every backend.
  @Test
  fun `the orientation nodes lay a picture out the way core orients its samples`() {
    val stored = OrientedPictures.samples()
    for (orientation in PictureOrientation.entries) {
      val (size, shown) =
        orientationNodes(orientation).fold(OrientedPictures.SIZE to stored) { (size, texels), node ->
          texels.through(node, size)
        }

      withClue("$orientation") { size shouldBe orientation.orientedSize(OrientedPictures.SIZE) }
      withClue("$orientation") { shown shouldBe orientedSamples(orientation, stored, OrientedPictures.SIZE) }
    }
  }

  @Test
  fun `a refusal carries the toolchain's reason after it when there is one`() {
    "Refused.".saying(null) shouldBe "Refused."
    "Refused.".saying("  ") shouldBe "Refused."
    "Refused.".saying("\nbad header \n") shouldBe "Refused. The toolchain said: bad header"
  }

  @Test
  fun `the output rate is read as the whole number the planner carries`() {
    sdr(frameRate = 29.97f).gridRate shouldBe 30
    sdr(frameRate = 25f).gridRate shouldBe 25
    sdr(frameRate = 0f).gridRate.shouldBeNull()
    sdr(frameRate = null).gridRate.shouldBeNull()
  }

  @Test
  fun `a still has no timeline to repeat over without a rate or a bounded span`() {
    sdr(frameRate = 0f).auxTimeline().shouldBeNull()
    sdr(span = TimeRange.from(Duration.ZERO)).auxTimeline().shouldBeNull()
    sdr(span = TimeRange.of(1.seconds, 2.seconds)).auxTimeline() shouldBe AuxTimeline(RATE, 1.seconds)
  }

  private fun sdrFragment(
    spec: ImageOverlay,
    attributes: Attributes = sdr(),
  ): FilterFragment =
    withClue("${spec.corner}") {
      filterGraphOverlay(spec, attributes, null).shouldBeInstanceOf<EffectResolution.Resolved>()
    }.effect.fragment

  private fun mergeAt(drawn: PixelRect): FilterNode =
    FilterNode("overlay", "x" to "${drawn.x}", "y" to "${drawn.y}", "format" to "auto", "eof_action" to "pass")

  private fun boundOf(time: Duration): Double = (frameIndexAt(time, SDR_SPAN, RATE.toInt()) - 0.5) / RATE.toInt()

  private fun sdr(
    inputSize: Size = FRAME,
    span: TimeRange = SDR_SPAN,
    frameRate: Float? = RATE,
  ): Attributes {
    val base = attributes(null)
    return Attributes(
      inputSize = inputSize,
      outputSize = base.outputSize,
      layoutSize = base.layoutSize,
      colorSpace = base.colorSpace,
      hdrTransfer = null,
      frameRate = frameRate,
      span = span,
    )
  }

  // The texels ffmpeg's hflip, vflip and transpose leave of a picture of size, with the size they leave.
  private fun IntArray.through(
    node: FilterNode,
    size: Size,
  ): Pair<Size, IntArray> {
    val (width, height) = size.width to size.height

    fun at(
      column: Int,
      row: Int,
    ): Int = this[row * width + column]
    return when (node.name) {
      "hflip" -> {
        size to IntArray(this.size) { at(width - 1 - it % width, it / width) }
      }
      "vflip" -> {
        size to IntArray(this.size) { at(it % width, height - 1 - it / width) }
      }
      "transpose" -> {
        val dir = node.arguments.single().value
        val turned = Size(height, width)
        // transpose reads row x and column y of its input for column x and row y of its output. Its clock forms
        // read the input's rows from the bottom, and its cclock forms write the output's rows from the bottom.
        val fromBottom = dir == "clock" || dir == "clock_flip"
        val toBottom = dir == "cclock" || dir == "clock_flip"
        turned to
          IntArray(this.size) {
            val x = it % turned.width
            val y = it / turned.width
            val row = if (fromBottom) height - 1 - x else x
            val column = if (toBottom) width - 1 - y else y
            at(column, row)
          }
      }
      else -> {
        error("${node.name} is not a node that lays a picture out")
      }
    }
  }

  private companion object {
    val PICTURE = ImageSource.ofBytes(OrientedPictures.png())
    const val RATE = 30f
    const val MARGIN = 0.1f
    const val HALF = 0.5f
    const val SLIDE = 0.25f
    const val ODD_WIDTH = 1918
    const val ODD_SCALE = 0.37f
    const val ODD_MARGIN = 0.05f
    val SPAN_LENGTH = 2.seconds
    val SDR_SPAN = TimeRange.of(Duration.ZERO, SPAN_LENGTH)

    // Off the frame grid, so each bound lands between two frames.
    val WINDOW_START = 510.milliseconds
    val WINDOW_END = 1490.milliseconds
  }
}
