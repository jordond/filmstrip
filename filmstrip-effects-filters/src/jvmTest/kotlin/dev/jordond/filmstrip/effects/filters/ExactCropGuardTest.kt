package dev.jordond.filmstrip.effects.filters

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.camcorder.Camcorder
import dev.jordond.filmstrip.effects.filters.camcorder.OsdElement
import dev.jordond.filmstrip.effects.filters.crt.Crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.nightvision.NightVision
import dev.jordond.filmstrip.effects.filters.pixel.Pixel
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Pins that every `crop` an effect lowers to is cut exactly, on frames of an odd width, an odd height and both.
 *
 * A `crop` without `exact` rounds its size down to the chroma grid of a 4:2:0 frame, so a frame the planner sizes
 * as odd would come out a pixel short.
 */
class ExactCropGuardTest {
  @Test
  fun `no effect lowers to a crop that rounds to the chroma grid`() {
    for (spec in LOOKS) {
      for (frame in ODD_FRAMES) {
        for (opaque in listOf(NormalizedRect.Full, BARS)) {
          val fragment = fragmentOf(spec, frame, opaque)
          withClue("${spec::class.simpleName} at $frame with $opaque") {
            fragment.crops().forEach { crop -> crop.arguments.associate { it.key to it.value }["exact"] shouldBe "1" }
            fragment.crop?.exact?.shouldBe(true)
          }
        }
      }
    }
  }

  @Test
  fun `the guard sees the crops it is there to police`() {
    val crops = LOOKS.flatMap { fragmentOf(it, Size(321, 181)).crops() }

    (crops.size > 0) shouldBe true
  }

  // The chain a pad sits in takes the frame to full chroma first (4:4:4 or RGB), so the pad drops no chroma. A pad in
  // 4:4:4 goes back to the subsampled format after, so the picture is not left in a format the encoder was never asked
  // for. A chain that pads with no such format before the pad fails, and so does one that holds no pad at all.
  @Test
  fun `a look that pads an odd frame does so in full chroma and restores the subsampled format after`() {
    var padded = 0
    for (spec in LOOKS) {
      for (opaque in listOf(NormalizedRect.Full, BARS)) {
        for (frame in ODD_FRAMES) {
          for (chain in fragmentOf(spec, frame, opaque).chains().filter { chain -> chain.any { it.name == "pad" } }) {
            padded++
            val pad = chain.indexOfFirst { it.name == "pad" }
            val before = chain.take(pad).lastOrNull { it.name == "format" }?.formats()
            withClue("${spec::class.simpleName} at $frame with $opaque has no format ahead of its pad") {
              before.shouldNotBeNull()
            }
            withClue("${spec::class.simpleName} at $frame with $opaque pads in $before") {
              before.shouldNotBeNull().all { it.contains("444") || it.contains("rgb") || it.contains("gbr") } shouldBe
                true
            }
            if (before.orEmpty().all { it.contains("444") }) {
              val after = chain.drop(pad + 1).lastOrNull { it.name == "format" }?.formats()
              withClue("${spec::class.simpleName} at $frame with $opaque leaves 4:4:4 after its pad") {
                after.shouldNotBeNull().none { it.contains("444") } shouldBe true
              }
            }
          }
        }
      }
    }
    withClue("the guard saw no padded chain") { (padded > 0) shouldBe true }
  }

  @Test
  fun `a tape look pads an even frame as it is`() {
    for (opaque in listOf(NormalizedRect.Full, BARS)) {
      val even = fragmentOf(Vhs(), Size(320, 180), opaque).chains().flatten()
      even.none { node -> node.arguments.any { it.value.contains("444") } } shouldBe true
    }
  }

  private fun FilterNode.formats(): List<String> = arguments.single { it.key == "pix_fmts" }.value.split('|')

  private fun FilterFragment.chains(): List<List<FilterNode>> =
    listOf(chain, post) + auxInputs.map { it.chain } + branches.map { it.nodes } +
      live?.branches.orEmpty().map { it.nodes }

  private fun fragmentOf(
    spec: EffectSpec,
    frame: Size,
    opaque: NormalizedRect? = NormalizedRect.Full,
  ): FilterFragment {
    val attributes =
      Attributes(
        inputSize = frame,
        outputSize = frame,
        layoutSize = frame,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        frameRate = 30f,
        span = TimeRange.of(0.seconds, 2.seconds),
        opaque = opaque,
      )
    val resolution = FilterEffectResolver().resolve(spec, CAPABILITIES, attributes)
    resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    return (resolution.effect as PlatformEffect).fragment
  }

  private fun FilterFragment.crops(): List<FilterNode> =
    (
      chain + post + listOfNotNull(merge) + auxInputs.flatMap { it.chain } + branches.nodes() +
        live?.branches.orEmpty().nodes()
    ).filter { it.name == "crop" }

  private fun List<FilterBranch>.nodes(): List<FilterNode> = flatMap { it.nodes }

  private companion object {
    val BARS = NormalizedRect(0.2f, 0f, 0.8f, 1f)
    val ODD_FRAMES = listOf(Size(321, 180), Size(320, 181), Size(321, 181), Size(135, 241))

    val CAPABILITIES =
      RenderCapabilities(
        api = RenderApi.FilterGraph,
        supportsFragmentShader = false,
        supportsComputeShader = false,
        supportsHdr = true,
        colorSpaces = setOf(ColorSpace.Bt709, ColorSpace.Bt2020),
        maxTextureSize = 16_384,
        features = emptySet(),
      )

    val LOOKS: List<EffectSpec> =
      listOf(
        Vhs(),
        Betamax(),
        Hi8(),
        Camcorder(listOf(OsdElement.Rec(), OsdElement.Date(), OsdElement.Clock())),
        Film(),
        Crt(),
        NightVision(),
        Pixel(),
        Glitch(),
      )
  }
}
