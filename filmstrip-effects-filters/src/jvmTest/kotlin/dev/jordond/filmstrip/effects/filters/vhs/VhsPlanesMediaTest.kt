package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Runs [VhsExpressions]' maps through ffmpeg's own `geq` and reads the planes [VhsPlanes] draws against them.
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class VhsPlanesMediaTest {
  @Test
  fun `the planes equal the geq's at mid-range ticks and strengths`() {
    listOf(Vhs(), Vhs(intensity = 0.5f), ROLLING_SNOW, EVENT_SNOW).forEach { vhs ->
      val nodes = VhsExpressions(vhs.seed, VhsParams.of(vhs))
      val inputs =
        vhsFragment(vhs, Size(WIDTH, HEIGHT))
          .live
          .shouldNotBeNull()
          .inputs
      val grain = inputs[0]
      val drop = inputs[1]
      TICKS.forEach { tick ->
        withClue("the grain of $vhs at tick $tick") {
          differing(
            grain.planeAt(tick),
            drawn(nodes.grainMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        withClue("the dropouts of $vhs at tick $tick") {
          differing(
            drop.planeAt(tick),
            drawn(nodes.dropMap().arguments.associate { it.key to it.value }, tick),
          ) shouldBe
            0
        }
        if (vhs.tracking != Vhs().tracking) {
          withClue("the snow and its weight of $vhs at tick $tick") {
            val snow = drawn(nodes.snowMap().arguments.associate { it.key to it.value }, tick)
            val weight = drawn(nodes.snowMask().arguments.associate { it.key to it.value }, tick, MASK)
            differing(inputs[2].planeAt(tick), beside(snow, RASTER, weight, MASK)) shouldBe 0
          }
        }
      }
    }
  }

  private fun differing(
    plane: ByteArray,
    frame: ByteArray,
  ): Int {
    plane.size shouldBe frame.size
    return plane.indices.count { plane[it] != frame[it] }
  }

  // Two 4:2:0 frames HEIGHT rows tall as one, [left] then [right] along each row of every plane.
  private fun beside(
    left: ByteArray,
    leftWidth: Int,
    right: ByteArray,
    rightWidth: Int,
  ): ByteArray {
    val planes =
      listOf(Triple(leftWidth, rightWidth, HEIGHT)) + List(2) { Triple(leftWidth / 2, rightWidth / 2, HEIGHT / 2) }
    var leftAt = 0
    var rightAt = 0
    return buildList {
      planes.forEach { (leftRow, rightRow, rows) ->
        repeat(rows) {
          addAll(left.copyOfRange(leftAt, leftAt + leftRow).toList())
          addAll(right.copyOfRange(rightAt, rightAt + rightRow).toList())
          leftAt += leftRow
          rightAt += rightRow
        }
      }
    }.toByteArray()
  }

  private fun drawn(
    planes: Map<String, String>,
    tick: Int,
    width: Int = RASTER,
  ): ByteArray {
    val geq =
      planes.entries.joinToString(
        ":",
      ) { (plane, expression) -> "$plane=${expression.replace(",", "\\,").replace(";", "\\;")}" }
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${width}x$HEIGHT:d=0.04",
        "-frames:v",
        "1",
        "-/filter:v",
        graphFile("format=yuv420p,settb=1/1000000,setpts=$tick/$TICK_RATE/TB,geq=$geq"),
        "-f",
        "rawvideo",
        "-pix_fmt",
        "yuv420p",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val frame = process.inputStream.readAllBytes()
    process.waitFor() shouldBe 0
    return frame
  }

  private fun graphFile(graph: String): String =
    java.io.File
      .createTempFile("vhs-planes", ".filtergraph")
      .apply {
        deleteOnExit()
        writeText(graph)
      }.path

  private companion object {
    const val WIDTH = 1920
    const val HEIGHT = 1080
    const val RASTER = 640
    const val MASK = 2
    val TICKS = listOf(0, 1, 37, 240, 241, 500, 719, 3596, 9001, 20011)
    val ROLLING_SNOW = Vhs(intensity = 0.6f, tracking = Tracking.Rolling(2.5.seconds, height = 0.15f, snow = 0.6f))
    val EVENT_SNOW = Vhs(tracking = Tracking.Events(chance = 1f, height = 0.12f, snow = 0.4f))
  }
}
