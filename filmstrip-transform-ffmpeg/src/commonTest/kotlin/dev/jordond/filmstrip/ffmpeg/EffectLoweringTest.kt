package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ExperimentalFilmstripApi
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
import dev.jordond.filmstrip.effects.color.Invert
import dev.jordond.filmstrip.effects.color.RgbAdjustment
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.color.Sepia
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
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_SCENE_TO_SDR_SIGNAL_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PQ_M2
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.SDR_SIGNAL_TO_HLG_SCENE_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling
import io.kotest.matchers.shouldBe
import java.awt.Color
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.Locale
import javax.imageio.ImageIO
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// The output frame rate the lowering samples an animation onto, matching what attributes() carries.
private const val RATE = 30.0

// The overlay pictures the animated lowerings are measured against.
private val SQUARE = Size(64, 64)
private val WIDE = Size(200, 50)

// The platform object is data here, so the whole catalogue is assertable with no ffmpeg installed.
@OptIn(ExperimentalFilmstripApi::class)
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

  // A clip's branch opens with setpts=PTS-STARTPTS, so t on it counts from the clip's own start.
  // A window written in composition time gates a clip occupying 2 to 6 seconds over the wrong two
  // of them.
  @Test
  fun `counts a clip's window from the clip rather than the composition`() {
    val spec =
      ImageOverlay(
        image = ImageSource.of("logo.png"),
        corner = Corner.TopStart,
        visibleDuring = TimeRange.of(3.seconds, 4.seconds),
      )
    val onSecondClip = attributes(Size(640, 360), span = TimeRange.of(2.seconds, 6.seconds))

    val merge = fragmentOf(spec, onSecondClip).merge!!.render()

    merge.substringAfter("enable=") shouldBe """between(t\,1.0\,2.0)"""
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

    val merge = fragmentOf(spec, wholeComposition).merge!!.render()

    merge.substringAfter("enable=") shouldBe """between(t\,1.0\,2.0)"""
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

    val merge = fragmentOf(spec, wholeComposition).merge!!.render()

    merge.substringAfter("enable=") shouldBe """between(t\,1.0\,6.0)"""
  }

  // One interval per output frame of the run, starting at exactly that frame's own branch-local
  // time, because a command written there lands on that frame and one written half a frame late
  // lands on the next.
  @Test
  fun `samples an animated overlay onto the output frame grid`() {
    val spec = ImageOverlay(overlayImage(SQUARE), Corner.TopStart, animation = fadeIn(1.seconds))
    val attributes = attributes(Size(640, 360))

    val lines = commandsOf(spec, attributes)

    lines.size shouldBe 30
    assertTrue(lines.first().startsWith("0.000000-0.033333 "), lines.first())
    assertTrue(lines.last().startsWith("0.966667-1.000000 "), lines.last())
    // The first frame sets everything the commands drive and the rest only the alpha, since nothing
    // else moves under a fade.
    lines.first().count { it == ',' } shouldBe 4
    lines[1].count { it == ',' } shouldBe 0
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
      val sampled = assertNotNull(spec.frameAt((index / RATE).seconds, attributes.span)).opacity
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

    val opening = commandsOf(spec, attributes(Size(640, 360))).first()

    // 640 less a 14 pixel margin is 626, plus a quarter of the frame is 786, less the 128 the
    // overlay is drawn at. The vertical anchor is 346 less the same 128.
    assertTrue(opening.contains(" x 658"), opening)
    assertTrue(opening.contains(" y 218"), opening)
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

    val merge = fragmentOf(spec, wholeComposition).merge!!.render()

    merge.substringAfter("enable=") shouldBe """between(t\,1.0\,6.0)"""
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
    fragment.merge!!.render().substringAfter("enable=") shouldBe """between(t\,6.0\,6.0)"""
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

  // The lut is the same shape on a graded export, but the table now runs the transfer function
  // either side of the multiply, so the factor lands on the SDR signal the light decodes to rather
  // than on a PQ code value, with the display gamma on both sides of it.
  @Test
  fun `takes a kept PQ grade through the transfer function`() {
    val chain = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Pq))

    chain.startsWith("format=pix_fmts=gbrp10le,lutrgb=") shouldBe true
    chain.contains("max(0.5*") shouldBe true
    chain.runsThroughSdrSignal(HdrTransfer.Pq) shouldBe true
    chain.channelsAgree() shouldBe true
  }

  // Only the inverse OETF runs here, so what is decoded is scene light, and the per-channel
  // opto-optical transfer sits between it and the SDR signal.
  @Test
  fun `takes a kept HLG grade through the scene light`() {
    val chain = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Hlg))

    chain.startsWith("format=pix_fmts=gbrp10le,lutrgb=") shouldBe true
    chain.contains("max(0.5*") shouldBe true
    chain.runsThroughSdrSignal(HdrTransfer.Hlg) shouldBe true
    chain.channelsAgree() shouldBe true
  }

  @Test
  fun `the two grades do not share a table`() {
    listOf(Brightness(0.5f), Contrast(1.5f)).forEach { spec ->
      val pq = chainOf(spec, attributes(hdrTransfer = HdrTransfer.Pq))
      val hlg = chainOf(spec, attributes(hdrTransfer = HdrTransfer.Hlg))

      assertTrue(pq != hlg, "one expression served both transfer functions, so one of them is wrong")
    }
  }

  @Test
  fun `leaves an unchanged brightness out of a graded graph too`() {
    chainOf(Brightness(1f), attributes(hdrTransfer = HdrTransfer.Pq)) shouldBe ""
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

  // Every matrix lowers on a grade, through the graded form of whichever filter its shape takes.
  // A diagonal one is the per-channel table a brightness takes. A mix is the file, run between a
  // decode and an encode of the transfer function at sixteen bits.
  @Test
  fun `lowers every colour matrix on a kept grade`() {
    val diagonal = listOf(RgbAdjustment(red = 1.2f), Contrast(1.5f), Invert())
    val mixing = listOf(Saturation(0.5f), HueRotate(90f), Sepia(), ColorMatrix(rg = 0.5f))

    listOf(HdrTransfer.Pq, HdrTransfer.Hlg).forEach { transfer ->
      diagonal.forEach { spec ->
        val fragment = fragmentOf(spec, attributes(hdrTransfer = transfer))

        fragment.chain.map { it.name } shouldBe listOf("format", "lutrgb")
        fragment.chain.first().render() shouldBe "format=pix_fmts=gbrp10le"
        fragment.sidecars shouldBe emptyList()
      }
      mixing.forEach { spec ->
        val fragment = fragmentOf(spec, attributes(hdrTransfer = transfer))

        fragment.chain.map { it.name } shouldBe listOf("format", "lutrgb", "lut3d", "lutrgb", "format")
        fragment.chain.first().render() shouldBe "format=pix_fmts=rgb48le"
        fragment.chain.last().render() shouldBe "format=pix_fmts=gbrp10le"
        fragment.chain[2].render() shouldBe "lut3d=file=${fragment.sidecars.single().placeholder}"
      }
    }
  }

  // The line runs on the SDR signal scaled so the format's peak is one, so the bias that pivots a
  // contrast on mid grey is scaled by the same ceiling while the factor itself is untouched.
  @Test
  fun `a graded contrast pivots on mid grey scaled to the format's ceiling`() {
    val spec = Contrast(1.5f)
    val bias = checkNotNull(colorMatrixOf(spec)).rBias

    listOf(HdrTransfer.Pq, HdrTransfer.Hlg).forEach { transfer ->
      val chain = chainOf(spec, attributes(hdrTransfer = transfer))

      chain.contains("max(1.5*") shouldBe true
      chain.contains("+${bias / transfer.sdrSignalCeiling}\\,0)") shouldBe true
      chain.runsThroughSdrSignal(transfer) shouldBe true
      chain.channelsAgree() shouldBe true
    }
  }

  // The file a graded mix travels as is written against the same scaled signal, so a bias in it is
  // divided by the format's ceiling and the two formats do not share a file. A mix with no bias
  // reads the same whatever white is, so its file is the SDR one.
  @Test
  fun `a graded mix is written against the format's ceiling`() {
    val biased = ColorMatrix(rr = 0.8f, rg = 0.2f, rBias = 0.1f, gg = 0.9f, bb = 1.1f, bBias = -0.05f)

    val pq = cubeOf(biased, HdrTransfer.Pq)
    val hlg = cubeOf(biased, HdrTransfer.Hlg)

    pq shouldBe cube(biased, HdrTransfer.Pq.sdrSignalCeiling)
    hlg shouldBe cube(biased, HdrTransfer.Hlg.sdrSignalCeiling)
    assertTrue(pq != cube(biased), "the graded file carried the SDR bias")
    assertTrue(pq != hlg, "one file served both transfer functions, so one of them is wrong")
    cubeOf(Saturation(0.5f), HdrTransfer.Pq) shouldBe cube(Saturation(0.5f))
  }

  // lutrgb's maxval is 255 shifted up to the format's depth, so on gbrp10le it reads 1020 and on
  // rgb48le it is the format's own peak. The table is scaled by the peak either way, or a graded
  // code value lands about a code low across the whole range, and the clip stays at the filter's
  // ceiling, which is the one thing it cannot write past.
  @Test
  fun `a graded table is scaled by the format's own peak`() {
    val diagonal = chainOf(Contrast(1.5f), attributes(hdrTransfer = HdrTransfer.Pq))
    val mixing = fragmentOf(Sepia(), attributes(hdrTransfer = HdrTransfer.Pq)).chain

    diagonal.contains("val/1023") shouldBe true
    diagonal.contains("round(1023*") shouldBe true
    diagonal.contains("\\,minval\\,maxval)") shouldBe true
    mixing[1].render().contains("round(65535*") shouldBe true
    mixing[3].render().contains("val/65535") shouldBe true
  }

  // A whole turn is the identity, and the trig it comes out of leaves cross terms around 1e-16. Read
  // as a mix it lowers to a cube, whose interpolation moves code values a no-op must not touch.
  @Test
  fun `a whole turn of hue lowers to nothing`() {
    chainOf(HueRotate(360f)) shouldBe ""
    fragmentOf(HueRotate(360f)).sidecars shouldBe emptyList()
  }

  // The tables either side of the file are the decode and the encode on their own, the same on
  // every channel, each carrying the transfer's own constants and the display gamma.
  @Test
  fun `a graded mix runs between a decode and an encode of the transfer`() {
    listOf(HdrTransfer.Pq, HdrTransfer.Hlg).forEach { transfer ->
      val chain = fragmentOf(Sepia(), attributes(hdrTransfer = transfer)).chain
      val decode = chain[1].render()
      val encode = chain[3].render()

      decode.channelsAgree() shouldBe true
      encode.channelsAgree() shouldBe true
      decode.decodesToSdrSignal(transfer) shouldBe true
      encode.encodesFromSdrSignal(transfer) shouldBe true
    }
  }

  @Test
  fun `an identity matrix claims the spec and emits nothing on a grade too`() {
    val graded = attributes(hdrTransfer = HdrTransfer.Pq)
    val resolution = resolver.resolve(ColorMatrix.Identity, capabilities(), graded)

    assertIs<EffectResolution.Resolved>(resolution)
    chainOf(ColorMatrix.Identity, graded) shouldBe ""
    chainOf(Saturation(1f), attributes(hdrTransfer = HdrTransfer.Hlg)) shouldBe ""
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

  // The three channels are one table, and a lut whose planes disagree tints the frame rather than
  // brightening it.
  private fun String.channelsAgree(): Boolean {
    val arguments = substringAfter("lutrgb=").split(":")
    val expressions = arguments.map { it.substringAfter("=") }

    return arguments.map { it.substringBefore("=") } == listOf("r", "g", "b") &&
      expressions.distinct().size == 1
  }

  // The pieces a graded table is built from, off the shared constants: the transfer's own decode
  // and encode, and the display gamma between each of them and the SDR signal.
  private fun String.decodesToSdrSignal(transfer: HdrTransfer): Boolean =
    when (transfer) {
      HdrTransfer.Pq -> contains("\\,${1.0 / PQ_M2})") && contains("\\,${1.0 / SDR_DISPLAY_GAMMA})")
      HdrTransfer.Hlg -> contains("/$HLG_A)") && contains("\\,$HLG_SCENE_TO_SDR_SIGNAL_GAMMA)")
    }

  private fun String.encodesFromSdrSignal(transfer: HdrTransfer): Boolean =
    when (transfer) {
      HdrTransfer.Pq -> contains("\\,$PQ_M2)") && contains("\\,$SDR_DISPLAY_GAMMA)")
      HdrTransfer.Hlg -> contains("$HLG_A*log(") && contains("\\,$SDR_SIGNAL_TO_HLG_SCENE_GAMMA)")
    }

  private fun String.runsThroughSdrSignal(transfer: HdrTransfer): Boolean =
    decodesToSdrSignal(transfer) && encodesFromSdrSignal(transfer)

  private fun cubeOf(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): String =
    fragmentOf(spec, attributes(hdrTransfer = transfer))
      .sidecars
      .single()
      .bytes
      .decodeToString()

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
  // interpolates along. On a grade the table reads a signal scaled to the format's ceiling, so the
  // bias is divided by it.
  private fun cube(
    spec: EffectSpec,
    ceiling: Float = 1f,
  ): String {
    val matrix = checkNotNull(colorMatrixOf(spec))

    return buildString {
      appendLine("LUT_3D_SIZE 2")
      for (blue in 0..1) {
        for (green in 0..1) {
          for (red in 0..1) {
            val r = matrix.rr * red + matrix.rg * green + matrix.rb * blue + matrix.rBias / ceiling
            val g = matrix.gr * red + matrix.gg * green + matrix.gb * blue + matrix.gBias / ceiling
            val b = matrix.br * red + matrix.bg * green + matrix.bb * blue + matrix.bBias / ceiling
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
  ): List<String> =
    fragmentOf(spec, attributes)
      .sidecars
      .single()
      .bytes
      .decodeToString()
      .trim()
      .lines()

  // The instance name is a digest of the commands, so a test reads it off the graph rather than
  // spelling it out.
  private fun instanceOf(fragment: FilterFragment): String = fragment.merge!!.name.substringAfter("overlay")

  // What one option was set to on each interval that set it, in the order they are written.
  private fun valuesOf(
    lines: List<String>,
    option: String,
  ): List<String> =
    lines.flatMap { line ->
      Regex("""@fs\w+ $option (\S+?)[,;]""").findAll(line).map { it.groupValues[1] }.toList()
    }

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
