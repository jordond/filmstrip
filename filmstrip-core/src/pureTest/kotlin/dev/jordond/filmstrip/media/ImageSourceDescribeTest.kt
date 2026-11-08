package dev.jordond.filmstrip.media

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlin.test.Test

/**
 * How an image source is named in a message.
 */
class ImageSourceDescribeTest {
  @Test
  fun aPathAndAnOrdinaryUriAreThemselves() {
    ImageSource.of("/photos/beach.jpg").describe() shouldBe "/photos/beach.jpg"
    ImageSource.ofUri("https://example.test/logo.png").describe() shouldBe "https://example.test/logo.png"
  }

  @Test
  fun aLongDataUriKeepsItsHeadAndSaysHowLongItWas() {
    val payload = "A".repeat(PAYLOAD)
    val uri = "data:image/png;base64,$payload"

    val described = ImageSource.ofUri(uri).describe()

    described shouldNotContain payload
    described shouldBe "${uri.take(48)}... (${uri.length} characters)"
  }

  @Test
  fun aShortDataUriIsItself() {
    ImageSource.ofUri("data:image/png;base64,AAAA").describe() shouldBe "data:image/png;base64,AAAA"
  }

  private companion object {
    const val PAYLOAD = 4096
  }
}
