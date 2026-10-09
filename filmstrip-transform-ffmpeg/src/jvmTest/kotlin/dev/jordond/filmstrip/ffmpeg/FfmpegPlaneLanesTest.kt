package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlaneLanes
import dev.jordond.filmstrip.ffmpeg.HdrRender.FLOAT_RGB
import dev.jordond.filmstrip.ffmpeg.HdrRender.WIDE_YUV
import dev.jordond.filmstrip.media.SIXTEEN_BIT_MAX
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * Plane lanes as the graph builder writes them, run through the ffmpeg on PATH.
 *
 * A frame holding every sixteen bit code on each plane, each plane walking the codes in its own order, goes in as
 * sixteen bit samples and comes out as planar float. Every float has to land on its sample exactly, for Y'CbCr carried
 * as is and for RGB put back in the planar float order, whatever range the frame was tagged with on the way in.
 */
class FfmpegPlaneLanesTest {
  @Test
  fun `carries each Y'CbCr plane to float on its sample`() {
    val samples = gridSamples(planes = 3)
    val lanes = PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB)

    TAGS.forEach { tag ->
      val nodes = tag + listOf(FilterNode("format", "pix_fmts" to WIDE_YUV), lanes)

      val floats = floats(RawFrame(planarShorts(samples), WIDE_YUV).rendered(nodes, FLOAT_RGB), planes = 3)

      assertTrue(misses(floats, samples).isEmpty(), "$tag: " + misses(floats, samples).joinToString("\n"))
    }
  }

  // gbrpf32le stores green, blue and red in that order, and the lanes put each channel in its slot by name.
  @Test
  fun `carries each RGB channel of a picture to float on its sample`() {
    val rgb = gridSamples(planes = 3)
    val alpha = IntArray(CODES) { SIXTEEN_BIT_MAX }
    val lanes = PlaneLanes.node(listOf("r", "g", "b"), FLOAT_RGB)

    TAGS.forEach { tag ->
      val picture = RawFrame(packedShorts(rgb + listOf(alpha)), PICTURE)
      val floats = floats(picture.rendered(tag + lanes, FLOAT_RGB), planes = 3)

      val expected = listOf(rgb[1], rgb[2], rgb[0])
      assertTrue(misses(floats, expected).isEmpty(), "$tag: " + misses(floats, expected).joinToString("\n"))
    }
  }

  @Test
  fun `carries alpha to float on a lane of its own`() {
    val rgba = gridSamples(planes = 4)
    val lanes = PlaneLanes.node(listOf("r", "g", "b", "a"), FLOAT_RGBA)

    val floats = floats(RawFrame(packedShorts(rgba), PICTURE).rendered(listOf(lanes), FLOAT_RGBA), planes = 4)

    val expected = listOf(rgba[1], rgba[2], rgba[0], rgba[3])
    assertTrue(misses(floats, expected).isEmpty(), misses(floats, expected).joinToString("\n"))
  }

  @Test
  fun `tags the merged frame video range whatever came in`() {
    val nodes =
      listOf(
        FilterNode("setrange", "range" to "full"),
        PlaneLanes.node(listOf("y", "u", "v"), FLOAT_RGB),
        FilterNode("showinfo"),
      )

    val log = RawFrame(planarShorts(gridSamples(planes = 3)), WIDE_YUV).logged(nodes, FLOAT_RGB)

    val ranges = Regex("color_range:(\\w+)").findAll(log).map { it.groupValues[1] }.toList()
    assertEquals(listOf("tv"), ranges, log)
  }

  // Every sixteen bit code once on each plane, the rest of the grid frame padded with the last. Each plane steps
  // through the codes by a different odd stride, so a pixel pairs codes from all over the range and no plane copies
  // another.
  private fun gridSamples(planes: Int): List<IntArray> =
    STRIDES.take(planes).mapIndexed { plane, stride ->
      IntArray(CODES) { pixel -> (pixel * stride + plane * OFFSET) % CODES }
    }

  // The pixels whose float, scaled back to sixteen bits, rounds to anything but the sample that went in.
  private fun misses(
    floats: List<FloatArray>,
    expected: List<IntArray>,
  ): List<String> =
    expected.indices
      .flatMap { plane ->
        (0 until CODES).mapNotNull { pixel ->
          val read = floats[plane][pixel] * SIXTEEN_BIT_MAX
          val sample = expected[plane][pixel]
          if (read.roundToInt() == sample) null else "plane $plane pixel $pixel read $read, expected $sample"
        }
      }.take(HdrGrid.MISSES_SHOWN)

  // The first CODES floats of each of [planes] planes of a planar float frame of the grid's size.
  private fun floats(
    bytes: ByteArray,
    planes: Int,
  ): List<FloatArray> {
    val samples = ByteBuffer.wrap(bytes).order(ByteOrder.LITTLE_ENDIAN).asFloatBuffer()
    assertEquals(HdrGrid.PIXELS * planes, samples.remaining(), "a planar float grid frame of $planes planes")
    return (0 until planes).map { plane -> FloatArray(CODES) { samples.get(plane * HdrGrid.PIXELS + it) } }
  }

  private companion object {
    const val CODES = SIXTEEN_BIT_MAX + 1
    const val OFFSET = 101
    val STRIDES = listOf(1, 389, 743, 1021)

    // Untagged, and the two tags a filter before the lanes can leave on the frame.
    val TAGS =
      listOf(
        emptyList(),
        listOf(FilterNode("setrange", "range" to "full")),
        listOf(FilterNode("setrange", "range" to "limited")),
      )

    const val PICTURE = "rgba64le"
    const val FLOAT_RGBA = "gbrapf32le"
  }
}
