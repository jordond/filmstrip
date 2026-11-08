package dev.jordond.filmstrip.effects.filters.vhs

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Runs [VhsExpressions]' two maps through ffmpeg's own `geq` and reads the planes [VhsPlanes] draws against them.
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class VhsPlanesMediaTest {
  @Test
  fun `the planes equal the geq's at mid-range ticks and strengths`() {
    listOf(Vhs(), Vhs(intensity = 0.5f)).forEach { vhs ->
      val nodes = VhsExpressions(vhs.seed, VhsParams.of(vhs))
      val inputs =
        vhsFragment(vhs, Size(WIDTH, HEIGHT))
          .live
          .shouldNotBeNull()
          .inputs
      val (grain, drop) = inputs
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

  private fun drawn(
    planes: Map<String, String>,
    tick: Int,
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
        "color=c=black:s=${RASTER}x$HEIGHT:d=0.04",
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
    val TICKS = listOf(0, 1, 37, 240, 241, 500, 719, 3596, 9001, 20011)
  }
}
