package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.color.pictureLight
import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.picture.fixtures.EMBEDDED_COLOR_CONTAINERS
import kotlin.random.Random
import kotlin.test.Test

/**
 * Overwrites random bytes of each of [EMBEDDED_COLOR_CONTAINERS] and pushes whatever description it reads through
 * [pictureLight] and [pictureSrgbCodes], so a corrupted profile that throws in the color maths is caught.
 */
class EmbeddedColorCorruptionTest {
  @Test
  fun `a corrupted container never throws and its description always lands`() {
    val random = Random(29)
    val texels = intArrayOf(0xFF000000.toInt(), 0xFFC86432.toInt(), 0xFF808080.toInt(), -1)
    for (tagged in EMBEDDED_COLOR_CONTAINERS) {
      repeat(500) {
        val corrupted = tagged.copyOf()
        repeat(1 + it % 4) { corrupted[random.nextInt(corrupted.size)] = random.nextInt(256).toByte() }
        embeddedColor(corrupted)
        val color = pictureColor(corrupted)
        pictureLight(color, texels)
        pictureSrgbCodes(color, texels)
      }
    }
  }
}
