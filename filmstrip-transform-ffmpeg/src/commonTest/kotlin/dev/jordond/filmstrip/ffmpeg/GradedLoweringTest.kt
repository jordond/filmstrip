package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.color.sdrSignalCeiling
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
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
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

// What a color effect on a kept grade lowers to: the frame's codes onto float planes one plane at a time, a table and
// mixer rows that decode them to the signal, a warp on PQ and the curve to the scaled SDR signal, the matrix, a warp
// and the curve back onto the signal, mixer rows and a table that round it to whole codes, and the way back into the
// grade's codes.
private val FRONT = listOf("setrange", PlaneLanes.NAME, "lut1d", "colorchannelmixer")
private val TAIL = listOf("lut1d", "lut1d", "colorchannelmixer", "lut1d", "format", "mergeplanes", "format")

/**
 * How a color effect on a kept HDR grade lowers onto lut1d and colorchannelmixer, each table a sidecar the graph
 * names.
 *
 * The figures the tables hold are checked in the effects module against core's own. Here the chain's shape, the tables
 * it reads and the bias it carries are read off the fragment.
 */
class GradedLoweringTest {
  private val resolver = BuiltInEffectResolver()

  // PQ's light climbs steeply off black, so a warp spreads the bottom of the signal ahead of the curve.
  @Test
  fun `takes a kept PQ grade through a warp and the transfer's curve`() {
    val fragment = fragmentOf(Brightness(HALF), HdrTransfer.Pq)

    fragment.chain.map { it.name } shouldBe FRONT + listOf("lut1d", "lut1d") + TAIL
    fragment.readsEveryTableAsASidecar()
  }

  @Test
  fun `takes a kept HLG grade through the transfer's curve alone`() {
    val fragment = fragmentOf(Brightness(HALF), HdrTransfer.Hlg)

    fragment.chain.map { it.name } shouldBe FRONT + listOf("lut1d") + TAIL
    fragment.readsEveryTableAsASidecar()
  }

  // A mix without a bias runs on colorchannelmixer after the curve, and one with a bias on a two-point lut3d, since
  // colorchannelmixer has no bias.
  @Test
  fun `a mix runs after the curve on the mixer or on a two-point table`() {
    val mixed = fragmentOf(Saturation(HALF), HdrTransfer.Hlg).chain.map { it.name }
    val biased = fragmentOf(BIASED_MIX, HdrTransfer.Hlg).chain.map { it.name }

    mixed shouldBe FRONT + listOf("lut1d", "colorchannelmixer") + TAIL
    biased shouldBe FRONT + listOf("lut1d", "lut3d") + TAIL
  }

  @Test
  fun `the two grades do not share a table`() {
    listOf(Brightness(HALF), Contrast(STRETCH), Saturation(HALF)).forEach { spec ->
      val pq = fragmentOf(spec, HdrTransfer.Pq).sidecars.toSet()
      val hlg = fragmentOf(spec, HdrTransfer.Hlg).sidecars.toSet()

      withClue("one set of tables served both transfer functions, so one of them is wrong") {
        (pq != hlg) shouldBe true
      }
    }
  }

  // The planes and alpha are carried onto float one at a time, the first and last tables read the entry nearest a code
  // and every table between them interpolates.
  @Test
  fun `lowers every color matrix on a kept grade`() {
    val specs =
      listOf(RgbAdjustment(red = 1.2f), Contrast(STRETCH), Invert()) +
        listOf(Saturation(HALF), HueRotate(QUARTER_TURN), Sepia(), ColorMatrix(rg = HALF), BIASED_MIX)

    HdrTransfer.entries.forEach { transfer ->
      specs.forEach { spec ->
        val fragment = fragmentOf(spec, transfer)
        val tables = fragment.chain.filter { it.name == "lut1d" }.map { it.argument("interp") }

        fragment.chain[1] shouldBe PlaneLanes.node(listOf("y", "u", "v", "a"), PlaneLanes.WITH_ALPHA.format)
        fragment.chain.last().render() shouldBe "format=pix_fmts=yuva420p10le"
        tables.first() shouldBe "nearest"
        tables.last() shouldBe "nearest"
        tables.drop(1).dropLast(1).forEach { it shouldBe "linear" }
        fragment.readsEveryTableAsASidecar()
      }
    }
  }

  // A contrast's bias is folded into the curve with its factor. The curve's entry at black over its rise to the top
  // is the bias over the factor, whatever the curve was scaled by to fit the tables after it, so the bias is read off
  // the shared matrix scaled by the transfer's ceiling.
  @Test
  fun `a graded contrast pivots on mid grey scaled to the format's ceiling`() {
    val matrix = checkNotNull(colorMatrixOf(Contrast(STRETCH)))

    HdrTransfer.entries.forEach { transfer ->
      val curve = entries(fragmentOf(Contrast(STRETCH), transfer).tableAt(CURVE_FROM_END))
      val black = curve.first()[0]
      val bias = matrix.rr * black / (curve.last()[0] - black)

      assertNear(bias, matrix.rBias / transfer.sdrSignalCeiling, "$transfer")
    }
  }

  // The two-point table's corner at black is the bias and the corner at full red adds red's weight, both scaled alike,
  // so their ratio holds the bias over the transfer's ceiling and the two transfers do not share a table.
  @Test
  fun `a graded mix is written against the format's ceiling`() {
    val tables =
      HdrTransfer.entries.map { transfer ->
        val fragment = fragmentOf(BIASED_MIX, transfer)
        val corners = entries(fragment.sidecarOf(fragment.chain.single { it.name == "lut3d" }))
        val bias = BIASED_MIX.rr * corners[0][0] / (corners[1][0] - corners[0][0])
        val blueBias = BIASED_MIX.bb * corners[0][2] / (corners[BLUE_CORNER][2] - corners[0][2])

        assertNear(bias, BIASED_MIX.rBias / transfer.sdrSignalCeiling, "$transfer red")
        assertNear(blueBias, BIASED_MIX.bBias / transfer.sdrSignalCeiling, "$transfer blue")
        corners
      }

    withClue("one table served both transfer functions, so one of them is wrong") {
      (tables[0][0] != tables[1][0]) shouldBe true
    }
  }

  private fun FilterFragment.readsEveryTableAsASidecar() {
    val read = chain.filter { it.name == "lut1d" || it.name == "lut3d" }.map { it.argument("file") }

    read.toSet() shouldBe sidecars.map { it.placeholder }.toSet()
    sidecars.forEach { it.extension shouldBe "cube" }
  }

  private fun FilterFragment.tableAt(fromEnd: Int): ByteArray =
    sidecarOf(chain.filter { it.name == "lut1d" }.let { it[it.size - fromEnd] })

  private fun FilterFragment.sidecarOf(node: FilterNode): ByteArray =
    sidecars.single { it.placeholder == node.argument("file") }.bytes

  private fun FilterNode.argument(key: String): String = arguments.single { it.key == key }.value

  // Every row of a .cube file after its size line, as numbers.
  private fun entries(cube: ByteArray): List<List<Double>> =
    cube
      .decodeToString()
      .lines()
      .drop(1)
      .filter { it.isNotBlank() }
      .map { row -> row.split(" ").map { it.toDouble() } }

  private fun assertNear(
    read: Double,
    expected: Float,
    what: String,
  ) {
    withClue("$what read $read, expected $expected") { (abs(read - expected) <= TOLERANCE) shouldBe true }
  }

  private fun fragmentOf(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): FilterFragment {
    val resolution = resolver.resolve(spec, capabilities(), attributes(transfer))
    resolution.shouldBeInstanceOf<EffectResolution.Resolved>()
    return resolution.effect.fragment
  }

  private fun chainOf(
    spec: EffectSpec,
    transfer: HdrTransfer,
  ): String = fragmentOf(spec, transfer).chain.render()

  private fun attributes(transfer: HdrTransfer): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt2020,
      hdrTransfer = transfer,
      frameRate = RATE,
      span = TimeRange.of(Duration.ZERO, 1.seconds),
    )

  private fun capabilities(): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.FilterGraph,
      supportsFragmentShader = false,
      supportsComputeShader = false,
      supportsHdr = false,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = 16_384,
      features = emptySet(),
    )

  private companion object {
    const val HALF = 0.5f
    const val STRETCH = 1.5f
    const val QUARTER_TURN = 90f

    // A mix with cross terms and a bias on two of its rows.
    val BIASED_MIX = ColorMatrix(rr = 0.8f, rg = 0.2f, rBias = 0.1f, gg = 0.9f, bb = 1.1f, bBias = -0.05f)

    // The curve is the fourth table from the end: the encode warp, the encode curve and the code steps come after it.
    const val CURVE_FROM_END = 4

    // A two-point cube's corners run red fastest, so full blue alone is the fifth.
    const val BLUE_CORNER = 4

    val FRAME = Size(1920, 1080)
    const val RATE = 30f

    // The tables are printed to nine significant digits, so a ratio of two entries holds to about a millionth.
    const val TOLERANCE = 1e-6
  }
}
