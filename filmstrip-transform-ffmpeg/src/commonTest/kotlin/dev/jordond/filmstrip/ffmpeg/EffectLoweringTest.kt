package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.HueRotate
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Flip
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.ffmpeg.internal.render
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.outputSlotOf
import dev.jordond.filmstrip.internal.outputSlotTime
import dev.jordond.filmstrip.internal.outputSlotsOf
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldStartWith
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.math.ceil
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The output frame rate the lowering samples an animation onto, matching what attributes() carries.
private const val RATE = 30.0

// The rate the grid tests sample at, whose 40 ms slot puts a half slot on a whole millisecond.
private const val GRID_RATE = 25

// Half way through a one second run, which is where a value a restarted branch would read differs
// from the one the run holds there.
private const val MIDWAY = 0.5

// The overlay pictures the animated lowerings are measured against.
private val SQUARE = Size(64, 64)
private val WIDE = Size(200, 50)

// The platform object is data here, so the whole catalogue is assertable with no ffmpeg installed.
class EffectLoweringTest {
  private val resolver = BuiltInEffectResolver()

  @Test
  fun `declines a backend it was not written for`() {
    val gl = capabilities(RenderApi.OpenGlEs)

    resolver.resolve(Rotate(90), gl, attributes()) shouldBe null
  }

  @Test
  fun `rotates counter-clockwise`() {
    chainOf(Rotate(90)) shouldBe "transpose=dir=cclock"
    chainOf(Rotate(270)) shouldBe "transpose=dir=clock"
    chainOf(Rotate(180)) shouldBe "hflip,vflip"
    chainOf(Rotate(0)) shouldBe ""
  }

  @Test
  fun `flips on the named axis`() {
    chainOf(Flip(FlipAxis.Horizontal)) shouldBe "hflip"
    chainOf(Flip(FlipAxis.Vertical)) shouldBe "vflip"
  }

  // The pixels are multiplied out here rather than left as an iw/ih expression, so the planner and
  // the export cannot round differently and disagree about the output frame.
  @Test
  fun `crops in real pixels from the top left`() {
    val rect = NormalizedRect(left = 0f, top = 0.5f, right = 0.5f, bottom = 1f)

    chainOf(CropRect(rect), attributes(Size(1920, 1080))) shouldBe "crop=w=960:h=540:x=0:y=540"
  }

  @Test
  fun `lowers an aspect crop through the same rectangle`() {
    chainOf(Crop(AspectRatio.Portrait), attributes(Size(1920, 1080))) shouldBe "crop=w=607:h=1080:x=656:y=0"
  }

  // The size stage is the tail the backend pins to the resolved output frame, so the effect that
  // decides that frame emits nothing of its own.
  @Test
  fun `scale claims the spec and emits nothing`() {
    val resolution = resolver.resolve(Scale(720), capabilities(), attributes())

    assertIs<EffectResolution.Resolved>(resolution)
    chainOf(Scale(720)) shouldBe ""
  }

  @Test
  fun `places a watermark against the frame variables`() {
    val spec = ImageOverlay(ImageSource.of("logo.png"), Corner.BottomEnd, margin = 0.04f, scale = 0.2f, opacity = 0.8f)
    val resolution = resolver.resolve(spec, capabilities(), attributes(Size(640, 360)))

    assertIs<EffectResolution.Resolved>(resolution)
    val fragment = resolution.effect.fragment
    fragment.auxInputs
      .single()
      .chain
      .render() shouldBe "scale=w=128:h=-1,format=pix_fmts=rgba,colorchannelmixer=aa=0.8"
    fragment.merge!!.render() shouldBe "overlay=x=W-w-14:y=H-h-14:format=auto:eof_action=pass"
  }

  // The merge reads the clip's clock, which a scrubbed preview opens at the seek. The overlay's own
  // branch is carried to that seek instead, so the window rides there and the merge carries no gate
  // of its own.
  @Test
  fun `gates the window on the overlay's branch rather than on the merge`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(1.seconds, 2.seconds),
      )
    val wholeComposition = attributes(Size(640, 360), span = TimeRange.of(Duration.ZERO, 6.seconds))
    val fragment = fragmentOf(spec, wholeComposition)

    fragment.auxInputs
      .single()
      .chain
      .render() shouldStartWith "scale=w=128:h=-1,format=pix_fmts=rgba,colorchannelmixer=aa=0:enable=not(gte(t"
    gatedFrames(fragment) shouldBe branchFrames(1.seconds, 2.seconds)
    fragment.merge!!.render() shouldBe "overlay=x=14:y=14:format=auto:eof_action=pass"
  }

  // A clip's branch counts from the clip's own start, and so does the image branch beside it, so a
  // window over 3 to 4 seconds of a clip occupying 2 to 6 is that clip's own 1 to 2.
  @Test
  fun `counts a clip's window from the clip rather than the composition`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(3.seconds, 4.seconds),
      )
    val onSecondClip = attributes(Size(640, 360), span = TimeRange.of(2.seconds, 6.seconds))

    gatedFrames(fragmentOf(spec, onSecondClip)) shouldBe branchFrames(1.seconds, 2.seconds)
  }

  // A composition effect's span opens at zero, so the window it lowers to is unchanged.
  @Test
  fun `leaves a composition window where it was written`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(1.seconds, 2.seconds),
      )
    val wholeComposition = attributes(Size(640, 360), span = TimeRange.of(Duration.ZERO, 6.seconds))

    gatedFrames(fragmentOf(spec, wholeComposition)) shouldBe branchFrames(1.seconds, 2.seconds)
  }

  // The window is taken from the shared run rather than read off the spec, so a window reaching
  // past the span is cut where the branch ends rather than gating past it.
  @Test
  fun `clips a window that outlasts the span`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(1.seconds, 9.seconds),
      )
    val wholeComposition = attributes(Size(640, 360), span = TimeRange.of(Duration.ZERO, 6.seconds))

    gatedFrames(fragmentOf(spec, wholeComposition)) shouldBe branchFrames(1.seconds, 6.seconds)
  }

  // A windowed overlay that is animated as well carries both, on nodes of their own: the gate is
  // ahead of the mixer the commands drive, so zeroing the alpha leaves what they are setting alone.
  @Test
  fun `gates an animated overlay's window as well as driving it`() {
    val spec =
      ImageOverlay(
        image = overlayImage(SQUARE),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(200.milliseconds, 800.milliseconds),
        animation = fadeIn(200.milliseconds),
      )

    val fragment = fragmentOf(spec, attributes(Size(640, 360)))

    val instance = instanceOf(fragment)
    gatedFrames(fragment) shouldBe branchFrames(200.milliseconds, 800.milliseconds)
    fragment.auxInputs
      .single()
      .chain
      .map { it.name } shouldBe
      listOf("sendcmd", "scale$instance", "format", "colorchannelmixer", "colorchannelmixer$instance", "scale")
  }

  // An interval opens at exactly the frame's own branch-local time, because a command written there
  // lands on that frame and one written half a frame late lands on the next. An option is written
  // only where its own value moves, so a fade writes the alpha per frame and the size once.
  @Test
  fun `samples an animated overlay onto the output frame grid`() {
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = fadeIn(1.seconds))
    val attributes = attributes(Size(640, 360))

    val lines = commandsOf(spec, attributes)

    val alpha = intervalsOf(lines, "aa")
    alpha.size shouldBe 30
    alpha.first() shouldBe "0.000000-0.033333"
    alpha.last() shouldBe "0.966667-1.000000"
    intervalsOf(lines, "w").single() shouldBe "0.000000-1.000000"
    intervalsOf(lines, "x").single() shouldBe "0.000000-1.000000"
  }

  // A clip's branch counts its frames from the output slot its span opens on. A span opening at 20 ms
  // at 25 fps opens on the slot at 40 ms, already inside a window opening at 30 ms, so the branch's
  // first frame is gated open, driven, and sampled at its own slot's time. Counting from the span's
  // exact start instead reads that frame as 10 ms in, ahead of the window.
  @Test
  fun `counts a clip's frames from the slot its span opens on`() {
    val window = TimeRange.of(30.milliseconds, 530.milliseconds)
    val spec =
      ImageOverlay(overlayImage(SQUARE), Corner.TopStart, visibleDuring = window, animation = fadeIn(200.milliseconds))
    val span = TimeRange.of(20.milliseconds, 1_020.milliseconds)
    val opens = outputSlotTime(outputSlotOf(span.start, GRID_RATE), GRID_RATE)
    assertTrue(opens >= window.start, "the span's first slot at $opens is ahead of the window")

    val fragment = fragmentOf(spec, attributes(Size(640, 360), span = span, frameRate = GRID_RATE.toFloat()))
    val lines = commandsOf(fragment)

    intervalsOf(lines, "aa").first() shouldStartWith "0.000000-"
    gatedFrames(fragment, GRID_RATE).first shouldBe 0
    val sampled = assertNotNull(spec.frameAt(opens, span)).opacity
    valuesOf(lines, "aa").first() shouldBe String.format(Locale.ROOT, "%.6f", sampled)
  }

  // Where the span's start and the window's edge both sit on the same side of half a slot, counting
  // from the span's slot and from its exact start read the same frame.
  @Test
  fun `opens a window on the same frame either way where both edges round alike`() {
    val window = TimeRange.of(500.milliseconds, 900.milliseconds)
    val spec =
      ImageOverlay(overlayImage(SQUARE), Corner.TopStart, visibleDuring = window, animation = fadeIn(200.milliseconds))
    val span = TimeRange.of(30.milliseconds, 1_030.milliseconds)
    val landed = outputSlotOf(window.start, GRID_RATE)
    assertTrue(outputSlotTime(landed, GRID_RATE) >= window.start, "the window's slot sits ahead of it")
    val frame = (landed - outputSlotOf(span.start, GRID_RATE)).toInt()

    val fragment = fragmentOf(spec, attributes(Size(640, 360), span = span, frameRate = GRID_RATE.toFloat()))

    intervalsOf(commandsOf(fragment), "aa").first() shouldStartWith
      String.format(Locale.ROOT, "%.6f-", frame.toDouble() / GRID_RATE)
    gatedFrames(fragment, GRID_RATE).first shouldBe frame
  }

  // A preview opens the overlay's branch wherever the scrub landed rather than at the top, so the
  // interval covering that frame has to say what every option is holding there. The probe is the
  // middle of the fade: at either end the value a restarted branch would read agrees anyway.
  @Test
  fun `says what every option holds part way through a run`() {
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = fadeIn(1.seconds))
    val attributes = attributes(Size(640, 360))

    val held = heldAt(commandsOf(spec, attributes), MIDWAY)

    held.keys shouldBe setOf("aa", "w", "h", "x", "y")
    val sampled = assertNotNull(spec.frameAt(MIDWAY.seconds, attributes.span)).opacity
    held.getValue("aa") shouldBe String.format(Locale.ROOT, "%.6f", sampled)
  }

  // The values are the shared sampler's, which has already folded the authored opacity into them,
  // so the node the commands drive opens at one rather than at what was authored.
  @Test
  fun `writes the alpha the shared sampler answers`() {
    val fade = fadeIn(1.seconds)
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, opacity = 0.5f, animation = fade)
    val attributes = attributes(Size(640, 360))

    val fragment = fragmentOf(spec, attributes)

    val instance = instanceOf(fragment)
    assertTrue(
      fragment.auxInputs
        .single()
        .chain
        .render()
        .contains("colorchannelmixer$instance=aa=1"),
    )
    valuesOf(commandsOf(spec, attributes), "aa").forEachIndexed { index, written ->
      val sampled = assertNotNull(spec.frameAt(outputSlotTime(index.toLong(), RATE.toInt()), attributes.span)).opacity
      written shouldBe String.format(Locale.ROOT, "%.6f", sampled)
    }
  }

  // The sendcmd node heads the aux chain, ahead of the scale it drives, and the three filters the
  // commands reach carry an instance name of their own so a second overlay's commands cannot
  // reach them.
  @Test
  fun `names the filters an animated overlay's commands drive`() {
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = fadeIn(1.seconds))

    val fragment = fragmentOf(spec, attributes(Size(640, 360)))

    val instance = instanceOf(fragment)
    val placeholder = fragment.sidecars.single().placeholder
    fragment.auxInputs
      .single()
      .chain
      .render() shouldBe
      "sendcmd=f=$placeholder,scale$instance=w=128:h=-1,format=pix_fmts=rgba," +
      "colorchannelmixer$instance=aa=1,scale=w=iw:h=ih:eval=frame"
    fragment.merge!!.name shouldBe "overlay$instance"
  }

  // colorchannelmixer takes its coefficients in -2..2, its own documented limit, and the shared
  // sampler holds an opacity in 0f..1f, so nothing an animation answers reaches the graph outside
  // the filter's range.
  @Test
  fun `writes every alpha inside colorchannelmixer's range`() {
    val wild = OverlayAnimation { time -> OverlayFrame(opacity = if (time.elapsed < 500.milliseconds) -8f else 8f) }
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = wild)

    val alphas = valuesOf(commandsOf(spec, attributes(Size(640, 360))), "aa").map { it.toFloat() }

    alphas.forEach { assertTrue(it in -2f..2f, "an alpha of $it reached the graph") }
    alphas.min() shouldBe 0f
    alphas.max() shouldBe 1f
  }

  // scale refuses a zero dimension, and the shared placement holds each side at a pixel. Both
  // sides are commanded, so the shorter one of a picture that is not square cannot round to nothing
  // while the longer one still has pixels left.
  @Test
  fun `holds both sides of a shrinking overlay at a pixel`() {
    val vanish = OverlayAnimation { time -> OverlayFrame(scale = 1f - (time.elapsed / 500.milliseconds).toFloat()) }
    val spec = ImageOverlay(overlayImage(WIDE), Corner.TopStart, animation = vanish)
    val lines = commandsOf(spec, attributes(Size(640, 360)))

    val widths = valuesOf(lines, "w").map { it.toInt() }
    val heights = valuesOf(lines, "h").map { it.toInt() }
    widths.min() shouldBe 1
    heights.min() shouldBe 1
    // The picture is four times as wide as it is tall, so the height is the side that reaches the
    // floor first and the one an aspect-derived height would have rounded away.
    heights.max() shouldBe widths.max() / 4
  }

  // The position comes out of the shared placement: the offset moves the frame anchor, and the
  // point inside the overlay that a bottom trailing corner holds is its own far corner, so both
  // sides of the drawn size come off the anchor.
  @Test
  fun `writes a bottom corner off the drawn size`() {
    val slide = slideIn(OverlayOffset(0.25f, 0f), 1.seconds)
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.BottomEnd, animation = slide)

    val opening = heldAt(commandsOf(spec, attributes(Size(640, 360))), 0.0)

    // 640 less a 14 pixel margin is 626, plus a quarter of the frame is 786, less the 128 the
    // overlay is drawn at. The vertical anchor is 346 less the same 128.
    opening.getValue("x") shouldBe "658"
    opening.getValue("y") shouldBe "218"
  }

  // The image is measured, not guessed, so a picture this process cannot open is refused by name
  // rather than lowered against a size that was never read.
  @Test
  fun `refuses an animated overlay it cannot measure`() {
    val spec = ImageOverlay(ImageSource.of("/no/such/logo.png"), Corner.TopStart, animation = fadeIn(1.seconds))

    val resolution = resolver.resolve(spec, capabilities(), attributes(Size(640, 360)))

    assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue(resolution.message.contains("header"), resolution.message)
  }

  // An unmeasurable image is only the animated path's problem. A still overlay hands the file
  // straight to ffmpeg, which reads formats the JDK does not.
  @Test
  fun `lowers a still overlay it cannot measure`() {
    val spec = ImageOverlay(ImageSource.of("/no/such/logo.png"), Corner.TopStart)

    assertIs<EffectResolution.Resolved>(resolver.resolve(spec, capabilities(), attributes(Size(640, 360))))
  }

  // The animation is sampled onto the output frame grid, so a composition that resolved no rate has
  // nothing to sample it onto and says so rather than drawing the overlay still.
  @Test
  fun `refuses an animated overlay with no frame rate`() {
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = fadeIn(1.seconds))

    val resolution = resolver.resolve(spec, capabilities(), attributes(Size(640, 360), frameRate = null))

    assertIs<EffectResolution.Unsupported>(resolution)
    assertTrue(resolution.message.contains("frame rate"), resolution.message)
  }

  // An open-ended window used to skip the gate altogether, which drew the overlay from the branch's
  // first frame. The run closes it against the span, and that is what the gate carries.
  @Test
  fun `gates an open-ended window on the run`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.from(1.seconds),
      )
    val wholeComposition = attributes(Size(640, 360), span = TimeRange.of(Duration.ZERO, 6.seconds))

    gatedFrames(fragmentOf(spec, wholeComposition)) shouldBe branchFrames(1.seconds, 6.seconds)
  }

  // A window outside the span draws nothing, and an animation over it has nothing to drive. It is
  // not a refusal: the same overlay without the animation lowers the same way.
  @Test
  fun `drives nothing when the window falls outside the span`() {
    val spec =
      ImageOverlay(
        image = overlayImage(SQUARE),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(10.seconds, 12.seconds),
        animation = fadeIn(1.seconds),
      )

    val fragment = fragmentOf(spec, attributes(Size(640, 360), span = TimeRange.of(Duration.ZERO, 6.seconds)))

    fragment.sidecars shouldBe emptyList()
    fragment.merge!!.name shouldBe "overlay"
    // The run collapses onto the span's end, which no frame of the branch reaches, so the gate is
    // shut across the whole of it.
    gatedFrames(fragment).isEmpty() shouldBe true
  }

  @Test
  fun `refuses text because the build has no drawtext`() {
    val resolution = resolver.resolve(TextOverlay("hello"), capabilities(), attributes())

    assertIs<EffectResolution.Unsupported>(resolution)
    resolution.message.contains("drawtext") shouldBe true
  }

  // Where drawtext does exist it still cannot wrap, and text layout is required to be exact.
  @Test
  fun `refuses text even where drawtext exists`() {
    val withText = capabilities(features = setOf(RenderFeature.TextRendering))
    val resolution = resolver.resolve(TextOverlay("hello"), withText, attributes())

    assertIs<EffectResolution.Unsupported>(resolution)
    resolution.message.contains("maxWidth") shouldBe true
  }

  // A crop is resolved to whole pixels once, so that the plan and the export cannot disagree about
  // the frame, and a pan moves the region on every frame. It is refused by name rather than lowered
  // to a crop that stands still, which is the way an effect this backend cannot draw becomes a
  // wrong render instead of a refusal.
  @Test
  fun `refuses a pan by name`() {
    val pan = KenBurns(NormalizedRect.Full, NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f))

    val resolution = resolver.resolve(pan, capabilities(), attributes())

    assertIs<EffectResolution.Unsupported>(resolution)
    resolution.specId shouldBe EffectIds.KEN_BURNS
  }

  @Test
  fun `lowers brightness onto a lookup table`() {
    chainOf(Brightness(0.5f)) shouldBe lutrgb(Brightness(0.5f))
  }

  // A factor above 1f brightens, and is spelled out the same way rather than clamped to a fade.
  @Test
  fun `lowers a brightening factor too`() {
    chainOf(Brightness(1.5f)) shouldBe lutrgb(Brightness(1.5f))
  }

  // colorchannelmixer caps its gains at 2 and fails the whole graph above that rather than
  // clamping, so a bright enough factor has to lower to a lut instead.
  @Test
  fun `lowers a factor past what a channel mixer would take`() {
    chainOf(Brightness(3f)) shouldBe lutrgb(Brightness(3f))
  }

  @Test
  fun `leaves an unchanged brightness out of the graph`() {
    chainOf(Brightness(1f)) shouldBe ""
  }

  @Test
  fun `reads a negative factor as black`() {
    chainOf(Brightness(-2f)) shouldBe lutrgb(Brightness(-2f))
  }

  @Test
  fun `reads a NaN factor as unchanged`() {
    chainOf(Brightness(Float.NaN)) shouldBe ""
  }

  // A contrast leaves each channel reading only itself, so it lowers to the same table a brightness
  // does. The bias is what pivots it on the middle of the range instead of on black, and it comes
  // off the shared matrix rather than a copy of it.
  @Test
  fun `lowers a contrast onto a lookup table that pivots on mid grey`() {
    chainOf(Contrast(1.5f)) shouldBe lutrgb(Contrast(1.5f))
  }

  // A saturation mixes the three channels, so there is no per-channel table to write it as and the
  // matrix travels as a file the graph names instead. The placeholder reaches the rendered text as
  // it was written, which is what lets the backend find it again and swap in the path.
  @Test
  fun `lowers a saturation onto a three-dimensional table`() {
    val spec = Saturation(0.5f)
    val fragment = fragmentOf(spec)
    val sidecar = fragment.sidecars.single()

    sidecar.extension shouldBe "cube"
    fragment.chain.render() shouldBe "lut3d=file=${sidecar.placeholder}"
    sidecar.bytes.decodeToString() shouldBe cube(spec)
  }

  // Claimed rather than declined, the way Scale is, because an unclaimed spec is refused by name at
  // plan time.
  @Test
  fun `an identity matrix claims the spec and emits nothing`() {
    val resolution = resolver.resolve(ColorMatrix.Identity, capabilities(), attributes())

    assertIs<EffectResolution.Resolved>(resolution)
    chainOf(ColorMatrix.Identity) shouldBe ""
    chainOf(Saturation(1f)) shouldBe ""
  }

  // A whole turn is the identity, and the trig it comes out of leaves cross terms around 1e-16. Read
  // as a mix it lowers to a cube, whose interpolation moves code values a no-op must not touch.
  @Test
  fun `a whole turn of hue lowers to nothing`() {
    chainOf(HueRotate(360f)) shouldBe ""
    fragmentOf(HueRotate(360f)).sidecars shouldBe emptyList()
  }

  @Test
  fun `a matrix with cross terms and a bias lowers to a file`() {
    val spec = ColorMatrix(rr = 0.8f, rg = 0.2f, rBias = 0.1f, gg = 0.9f, bb = 1.1f, bBias = -0.05f)
    val fragment = fragmentOf(spec)

    fragment.chain.render() shouldBe "lut3d=file=${fragment.sidecars.single().placeholder}"
    fragment.sidecars
      .single()
      .bytes
      .decodeToString() shouldBe cube(spec)
  }

  // The fold that merges a run of colour effects can land on a matrix whose off-diagonal entries
  // all cancelled, and that one is three independent lines like any other.
  @Test
  fun `a diagonal matrix lowers to the per-channel table rather than a file`() {
    val spec = ColorMatrix(rr = 1.2f, rBias = -0.1f, gg = 0.9f, gBias = 0.05f, bb = 1.1f, bBias = 0.2f)

    chainOf(spec) shouldBe lutrgb(spec)
    fragmentOf(spec).sidecars shouldBe emptyList()
  }

  // The table this spec's own matrix spells, so a lowering that reached for a different matrix
  // fails here rather than agreeing with a number copied out of the shared layer.
  private fun lutrgb(spec: EffectSpec): String {
    val matrix = checkNotNull(colorMatrixOf(spec))

    return "lutrgb=r=${channel(matrix.rr, matrix.rBias)}" +
      ":g=${channel(matrix.gg, matrix.gBias)}" +
      ":b=${channel(matrix.bb, matrix.bBias)}"
  }

  private fun channel(
    scale: Float,
    bias: Float,
  ): String = "clip(round(val*$scale+$bias*maxval)\\,minval\\,maxval)"

  // The eight corners of the unit cube, red varying fastest and blue slowest, each one the matrix
  // applied with nothing clamped so that a corner past white still lands on the line lut3d
  // interpolates along.
  private fun cube(spec: EffectSpec): String {
    val matrix = checkNotNull(colorMatrixOf(spec))

    return buildString {
      appendLine("LUT_3D_SIZE 2")
      for (blue in 0..1) {
        for (green in 0..1) {
          for (red in 0..1) {
            val r = matrix.rr * red + matrix.rg * green + matrix.rb * blue + matrix.rBias
            val g = matrix.gr * red + matrix.gg * green + matrix.gb * blue + matrix.gBias
            val b = matrix.br * red + matrix.bg * green + matrix.bb * blue + matrix.bBias
            appendLine("${entry(r)} ${entry(g)} ${entry(b)}")
          }
        }
      }
    }
  }

  private fun entry(value: Float): String = String.format(Locale.ROOT, "%.7f", value)

  private fun fragmentOf(
    spec: EffectSpec,
    attributes: Attributes = attributes(),
  ): FilterFragment {
    val resolution = resolver.resolve(spec, capabilities(), attributes)
    assertIs<EffectResolution.Resolved>(resolution)
    return resolution.effect.fragment
  }

  // The animated lowering reads the overlay image's header, since the drawn size is the picture's
  // own scaled by the animation, so a test hands it real bytes rather than a path nothing opens.
  private fun overlayImage(size: Size): ImageSource {
    val image = BufferedImage(size.width, size.height, BufferedImage.TYPE_INT_ARGB)
    val graphics = image.createGraphics()
    graphics.color = Color.WHITE
    graphics.fillRect(0, 0, size.width, size.height)
    graphics.dispose()

    return ImageSource.ofBytes(ByteArrayOutputStream().also { ImageIO.write(image, "png", it) }.toByteArray())
  }

  private fun commandsOf(
    spec: ImageOverlay,
    attributes: Attributes,
  ): List<String> = commandsOf(fragmentOf(spec, attributes))

  private fun commandsOf(fragment: FilterFragment): List<String> =
    fragment
      .sidecars
      .single()
      .bytes
      .decodeToString()
      .trim()
      .lines()

  // The instance name is a digest of the commands, so a test reads it off the graph rather than
  // spelling it out.
  private fun instanceOf(fragment: FilterFragment): String = fragment.merge!!.name.substringAfter("overlay")

  // The window, read off the node that carries it on the overlay's own branch. Nothing on the merge
  // gates the overlay any more, so a test asserting on one there would pass against an empty string.
  private fun gateOf(fragment: FilterFragment): String =
    fragment.auxInputs
      .single()
      .chain
      .single { node -> node.arguments.any { it.key == "enable" } }
      .render()
      .substringAfter("enable=")

  // The branch frames a window gate lets through at [rate], read off the bounds the graph carries. A
  // bound is compared against each frame's own time on the branch, so the first frame past it is the
  // first whose time reaches it.
  private fun gatedFrames(
    fragment: FilterFragment,
    rate: Int = RATE.toInt(),
  ): IntRange {
    val bounds = assertNotNull(Regex("""gte\(t\\,([^)]+)\)\*lt\(t\\,([^)]+)\)""").find(gateOf(fragment)))
    val (open, shut) = bounds.destructured
    return ceil(open.toDouble() * rate).toInt() until ceil(shut.toDouble() * rate).toInt()
  }

  // The branch frames a window of [from] to [until] covers, on the branch's own clock at the rate
  // attributes() carries, for a span that opens on a slot.
  private fun branchFrames(
    from: Duration,
    until: Duration,
  ): IntRange {
    val slots = outputSlotsOf(TimeRange.of(from, until), RATE.toInt())
    return slots.first.toInt()..slots.last.toInt()
  }

  // What one option was set to on each interval that set it, in the order they are written.
  private fun valuesOf(
    lines: List<String>,
    option: String,
  ): List<String> =
    lines.flatMap { line ->
      Regex("""@fs\w+ $option (\S+?)[,;]""").findAll(line).map { it.groupValues[1] }.toList()
    }

  // The window each of one option's commands is written over, as the `START-END` the line opens
  // with, in the order they are written.
  private fun intervalsOf(
    lines: List<String>,
    option: String,
  ): List<String> =
    lines.filter { Regex("""@fs\w+ $option \S+;""").containsMatchIn(it) }.map { it.substringBefore(' ') }

  // What the commands leave the driven filters holding at one branch-local time, which is what a
  // branch opened there reads off them. An option missing from the answer is one a scrubbed preview
  // would be left guessing at.
  //
  // sendcmd enters an interval on either of its bounds, not just its start, so a time sitting on a
  // boundary enters both the interval closing there and the one opening there. The later one wins,
  // the way it does on the frame itself, because the intervals are read in the order they are
  // written and they are written by the time they open on.
  private fun heldAt(
    lines: List<String>,
    seconds: Double,
  ): Map<String, String> =
    lines
      .mapNotNull { line ->
        val (from, until) = line.substringBefore(' ').split('-').map { it.toDouble() }
        if (seconds < from || seconds > until) return@mapNotNull null
        val command = line.substringAfter(' ').trimEnd(';').split(' ')
        command[1] to command[2]
      }.toMap()

  private fun chainOf(
    spec: EffectSpec,
    attributes: Attributes = attributes(),
  ): String {
    val resolution = resolver.resolve(spec, capabilities(), attributes)
    assertIs<EffectResolution.Resolved>(resolution)
    return resolution.effect.fragment.chain
      .render()
  }

  private fun attributes(
    inputSize: Size = Size(1920, 1080),
    outputSize: Size = inputSize,
    hdrTransfer: HdrTransfer? = null,
    span: TimeRange = TimeRange.of(Duration.ZERO, 1.seconds),
    frameRate: Float? = RATE.toFloat(),
  ): Attributes =
    Attributes(
      inputSize = inputSize,
      outputSize = outputSize,
      layoutSize = inputSize,
      colorSpace = if (hdrTransfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = hdrTransfer,
      frameRate = frameRate,
      span = span,
    )

  private fun capabilities(
    api: RenderApi = RenderApi.FilterGraph,
    features: Set<RenderFeature> = emptySet(),
  ): RenderCapabilities =
    RenderCapabilities(
      api = api,
      supportsFragmentShader = false,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = 16_384,
      features = features,
    )
}
