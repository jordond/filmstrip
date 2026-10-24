package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.TestIccProfiles
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.random.Random
import kotlin.test.Test

/**
 * Cuts and corrupts containers carrying `cICP`, `sRGB`, `gAMA`, `cHRM`, `nclx`, `pixi`, `av1C`, `hvcC`, frame headers
 * and samples per pixel, so a read that throws, or that reads a part of a container as something else, is caught.
 */
class EmbeddedColourFuzzTest {
  private val p3 = TestIccProfiles.displayP3

  private val containers =
    listOf(
      png(cicpChunk(12, 13), iccpChunk(p3)),
      png(gamaChunk(50000), chrmChunk(P3_CHRM)),
      png(badCrc(srgbChunk()), gamaChunk(70000), colourType = 0),
      png(iccpChunk(TestIccProfiles.grey22), colourType = 4),
      heifItems(listOf(nclx(12, 13), pixi(3))),
      heifItems(listOf(colr("prof", p3), av1c(monochrome = false)), tile = listOf(nclx(9, 8))),
      heifItems(emptyList(), tile = listOf(nclx(9, 1), hvcc(chromaFormat = 1))),
      jpegSegments(app2(1, 2, p3.copyOfRange(0, 100)), app2(2, 2, p3.copyOfRange(100, p3.size)), components = 3),
      tiffWith(p3, samplesPerPixel(4), extraSamples()),
      tiffWith(p3, samplesPerPixel(4), extraSamples(), little = false),
      tiffWith(p3, samplesPerPixel(3, type = 4), little = false, big = true),
    )

  @Test
  fun `every container reads a description whole`() {
    containers.forEach { pictureColour(it).shouldNotBeNull() }
  }

  @Test
  fun `a cut container gives no description or the whole one and never throws`() {
    for (tagged in containers) {
      val whole = pictureColour(tagged).summary()
      for (length in tagged.indices) {
        val read = pictureColour(tagged.copyOf(length))
        if (read != null) read.summary() shouldBe whole
      }
    }
  }

  @Test
  fun `a corrupted container never throws and its description always lands`() {
    val random = Random(29)
    val texels = intArrayOf(0xFF000000.toInt(), 0xFFC86432.toInt(), 0xFF808080.toInt(), -1)
    for (tagged in containers) {
      repeat(500) {
        val corrupted = tagged.copyOf()
        repeat(1 + it % 4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
        embeddedColour(corrupted)
        val colour = pictureColour(corrupted)
        pictureLight(colour, texels)
        pictureSrgbCodes(colour, texels)
      }
    }
  }
}
