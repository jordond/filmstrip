package dev.jordond.filmstrip.testing

import dev.jordond.filmstrip.media.ColorSpace
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class FrameBuildersTest {
  @Test
  fun `a frame is an opaque black picture of the size asked for`() {
    if (!hostMakesImages) return
    val frame = testFrame(at = 2.seconds, widthPx = 4, heightPx = 2)

    frame.presentationTime shouldBe 2.seconds
    frame.colorSpace shouldBe ColorSpace.Bt709
    frame.image.widthPx shouldBe 4
    frame.image.heightPx shouldBe 2
    val pixels = frame.image.toRgba8888().toList()
    pixels.size shouldBe 4 * 2 * 4
    pixels.chunked(4).distinct() shouldBe listOf(listOf<Byte>(0, 0, 0, -1))
    frame.image.close()
  }
}

/**
 * Whether this host has a real picture type. False on the Android host, whose framework is a stub.
 */
internal expect val hostMakesImages: Boolean
