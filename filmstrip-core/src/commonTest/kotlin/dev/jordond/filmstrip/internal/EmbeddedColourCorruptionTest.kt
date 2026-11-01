package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.media.pictureSrgbCodes
import kotlin.random.Random
import kotlin.test.Test

/**
 * Overwrites random bytes of each of [EMBEDDED_COLOUR_CONTAINERS] and pushes whatever description it reads through
 * [pictureLight] and [pictureSrgbCodes], so a corrupted profile that throws in the colour maths is caught.
 */
class EmbeddedColourCorruptionTest {
  @Test
  fun `a corrupted container never throws and its description always lands`() {
    val random = Random(29)
    val texels = intArrayOf(0xFF000000.toInt(), 0xFFC86432.toInt(), 0xFF808080.toInt(), -1)
    for (tagged in EMBEDDED_COLOUR_CONTAINERS) {
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
