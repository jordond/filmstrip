package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effect.RenderFeature
import dev.jordond.filmstrip.effects.Brightness
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.Crop
import dev.jordond.filmstrip.effects.CropRect
import dev.jordond.filmstrip.effects.Flip
import dev.jordond.filmstrip.effects.KenBurns
import dev.jordond.filmstrip.effects.Rotate
import dev.jordond.filmstrip.effects.Scale
import dev.jordond.filmstrip.effects.Text
import dev.jordond.filmstrip.effects.Watermark
import dev.jordond.filmstrip.ffmpeg.internal.render
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.brightnessDisplayGain
import dev.jordond.filmstrip.media.brightnessSceneGain
import io.kotest.matchers.shouldBe
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
    val spec = Watermark(ImageSource.of("logo.png"), Corner.BottomEnd, margin = 0.04f, scale = 0.2f, opacity = 0.8f)
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
    val resolution = resolver.resolve(Text("hello"), capabilities(), attributes())

    assertIs<EffectResolution.Unsupported>(resolution)
    resolution.message.contains("drawtext") shouldBe true
  }

  // Where drawtext does exist it still cannot wrap, and text layout is required to be exact.
  @Test
  fun `refuses text even where drawtext exists`() {
    val withText = capabilities(features = setOf(RenderFeature.TextRendering))
    val resolution = resolver.resolve(Text("hello"), withText, attributes())

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
    chainOf(Brightness(0.5f)) shouldBe lut("0.5")
  }

  // A factor above 1f brightens, and is spelled out the same way rather than clamped to a fade.
  @Test
  fun `lowers a brightening factor too`() {
    chainOf(Brightness(1.5f)) shouldBe lut("1.5")
  }

  // colorchannelmixer caps its gains at 2 and fails the whole graph above that rather than
  // clamping, so a bright enough factor has to lower to a lut instead.
  @Test
  fun `lowers a factor past what a channel mixer would take`() {
    chainOf(Brightness(3f)) shouldBe lut("3.0")
  }

  @Test
  fun `leaves an unchanged brightness out of the graph`() {
    chainOf(Brightness(1f)) shouldBe ""
  }

  @Test
  fun `reads a negative factor as black`() {
    chainOf(Brightness(-2f)) shouldBe lut("0.0")
  }

  @Test
  fun `reads a NaN factor as unchanged`() {
    chainOf(Brightness(Float.NaN)) shouldBe ""
  }

  // The lut is the same shape on a graded export, but the table now runs the transfer function
  // either side of the multiply, so the factor lands on light rather than on a PQ code value.
  @Test
  fun `takes a kept PQ grade through the transfer function`() {
    val chain = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Pq))

    chain.startsWith("format=pix_fmts=gbrp10le,lutrgb=") shouldBe true
    chain.contains("*${brightnessDisplayGain(0.5f)}") shouldBe true
    chain.channelsAgree() shouldBe true
  }

  // Only the inverse OETF runs here, so what is multiplied is scene light and the gain is the one
  // that belongs to it.
  @Test
  fun `takes a kept HLG grade through the scene gain`() {
    val chain = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Hlg))

    chain.startsWith("format=pix_fmts=gbrp10le,lutrgb=") shouldBe true
    chain.contains("*${brightnessSceneGain(0.5f)}") shouldBe true
    chain.channelsAgree() shouldBe true
  }

  @Test
  fun `the two grades do not share a gain`() {
    val pq = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Pq))
    val hlg = chainOf(Brightness(0.5f), attributes(hdrTransfer = HdrTransfer.Hlg))

    assertTrue(pq != hlg, "one expression served both transfer functions, so one of them is wrong")
  }

  @Test
  fun `leaves an unchanged brightness out of a graded graph too`() {
    chainOf(Brightness(1f), attributes(hdrTransfer = HdrTransfer.Pq)) shouldBe ""
  }

  // The three channels are one table, and a lut whose planes disagree tints the frame rather than
  // brightening it.
  private fun String.channelsAgree(): Boolean {
    val arguments = substringAfter("lutrgb=").split(":")
    val expressions = arguments.map { it.substringAfter("=") }

    return arguments.map { it.substringBefore("=") } == listOf("r", "g", "b") &&
      expressions.distinct().size == 1
  }

  private fun lut(factor: String): String =
    "lutrgb=r=clip(val*$factor\\,minval\\,maxval)" +
      ":g=clip(val*$factor\\,minval\\,maxval)" +
      ":b=clip(val*$factor\\,minval\\,maxval)"

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
