package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.effects.LINEAR_COLOR
import dev.jordond.filmstrip.effects.signalOf
import dev.jordond.filmstrip.effects.wideSignal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import java.awt.image.Raster
import java.io.ByteArrayInputStream
import javax.imageio.ImageIO
import kotlin.test.Test

/**
 * An overlay picture carried onto an HDR grade's signal by [hdrOverlayRaster], read back off the PNG it writes.
 */
class HdrOverlayRasterTest {
  @Test
  fun `each texel's color lands at the signal its light is written at`() {
    for (color in listOf<PictureColor?>(null, LINEAR_COLOR)) {
      for (transfer in HdrTransfer.entries) {
        val raster = rasterOf(StoredPicture(StoredSamples.Argb(TEXELS), ROW, color), transfer)

        TEXELS.indices.forEach { x ->
          val opaque = signalOf(color, TEXELS[x] or OPAQUE, transfer)
          withClue("$color $transfer $x") { raster.getPixel(x, 0, IntArray(4)).copyOf(3) shouldBe opaque.copyOf(3) }
        }
      }
    }
  }

  // The color stays straight, so the graph decodes it to light apart from the alpha it reads as the blend's mask.
  @Test
  fun `alpha is widened to sixteen bits and leaves the color it rides on unmultiplied`() {
    val raster = rasterOf(StoredPicture(StoredSamples.Argb(TEXELS), ROW, null), HdrTransfer.Pq)

    TEXELS.indices.map { raster.getSample(it, 0, 3) } shouldBe listOf(0xFFFF, 0x8080, 0x4040, 0)
    raster.getPixel(3, 0, IntArray(4)).copyOf(3) shouldBe raster.getPixel(0, 0, IntArray(4)).copyOf(3)
  }

  @Test
  fun `a deep picture is carried at every bit it stores`() {
    val samples = shortArrayOf(0x1234, 0x8001.toShort(), 0xFEDC.toShort(), 0x4321)
    for (transfer in HdrTransfer.entries) {
      val raster = rasterOf(StoredPicture(StoredSamples.Rgba16(samples), Size(1, 1), null), transfer)

      val opaque = samples.copyOf().also { it[3] = 0xFFFF.toShort() }
      val expected = wideSignal(pictureLight(null, opaque), transfer).copyOf(3) + 0x4321
      withClue("$transfer") { raster.getPixel(0, 0, IntArray(4)) shouldBe expected }
    }
  }

  @Test
  fun `the raster keeps the picture's size and is written at sixteen bits a channel`() {
    val size = Size(3, 2)
    val texels = IntArray(size.width * size.height) { TEXELS[it % TEXELS.size] }
    val mapped = hdrOverlayRaster(StoredPicture(StoredSamples.Argb(texels), size, null), HdrTransfer.Hlg)
    val raster = ImageIO.read(ByteArrayInputStream(mapped.png)).raster

    mapped.size shouldBe size
    Size(raster.width, raster.height) shouldBe size
    (0 until 4).map { raster.sampleModel.getSampleSize(it) } shouldBe List(4) { 16 }
    withClue("one color twice") { raster.getPixel(1, 1, IntArray(4)) shouldBe raster.getPixel(0, 0, IntArray(4)) }
  }

  private fun rasterOf(
    picture: StoredPicture,
    transfer: HdrTransfer,
  ): Raster = ImageIO.read(ByteArrayInputStream(hdrOverlayRaster(picture, transfer).png)).raster

  private companion object {
    const val OPAQUE = 0xFF shl 24

    // A mid tone at three alphas, then the first color wholly transparent.
    val TEXELS = intArrayOf(0xFF1E90FF.toInt(), 0x80808080.toInt(), 0x4040A040, 0x001E90FF)
    val ROW = Size(TEXELS.size, 1)
  }
}
