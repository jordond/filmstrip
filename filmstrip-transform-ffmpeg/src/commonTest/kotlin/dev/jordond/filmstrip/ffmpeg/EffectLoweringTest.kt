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
import dev.jordond.filmstrip.effects.overlay.TextOverlay
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
import java.util.Locale
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

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
  ): Attributes =
    Attributes(
      inputSize = inputSize,
      outputSize = outputSize,
      layoutSize = inputSize,
      colorSpace = if (hdrTransfer == null) ColorSpace.Bt709 else ColorSpace.Bt2020,
      hdrTransfer = hdrTransfer,
      frameRate = 30f,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
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
