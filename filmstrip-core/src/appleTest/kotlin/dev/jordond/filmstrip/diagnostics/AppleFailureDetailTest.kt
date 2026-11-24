package dev.jordond.filmstrip.diagnostics

import io.kotest.matchers.shouldBe
import platform.Foundation.NSError
import platform.Foundation.NSLocalizedDescriptionKey
import platform.Foundation.NSUnderlyingErrorKey
import kotlin.test.Test

/**
 * What an [NSError] says about itself on the failure event.
 *
 * The errors are built the way AVFoundation nests them, each status under the error that reported it.
 */
class AppleFailureDetailTest {
  @Test
  fun `a three level error reads its own code and domain and every level as a cause`() {
    val status = errorOf("NSOSStatusErrorDomain", -12_909, "The operation couldn't be completed.")
    val decode = errorOf("AVFoundationErrorDomain", -11_821, "Cannot Decode", status)
    val failed = errorOf("AVFoundationErrorDomain", -11_800, "The operation could not be completed", decode)

    val platform = failed.platformFailure("avfoundation", CodecRole.Decoder)

    platform.backend shouldBe "avfoundation"
    platform.code shouldBe -11_800
    platform.domain shouldBe "AVFoundationErrorDomain"
    platform.codecRole shouldBe CodecRole.Decoder
    platform.causes shouldBe
      listOf(
        "AVFoundationErrorDomain -11800: The operation could not be completed",
        "AVFoundationErrorDomain -11821: Cannot Decode",
        "NSOSStatusErrorDomain -12909: The operation couldn't be completed.",
      )
  }

  @Test
  fun `an error with nothing under it is its own only cause`() {
    val platform = errorOf("NSCocoaErrorDomain", 4, "No such file").platformFailure("avfoundation")

    platform.codecRole shouldBe null
    platform.causes shouldBe listOf("NSCocoaErrorDomain 4: No such file")
  }

  @Test
  fun `a deep chain keeps the outermost levels`() {
    val deepest =
      (1..MAX_CAUSES + 2).fold(
        null as NSError?,
      ) { under, level -> errorOf("Deep", level.toLong(), "x", under) }

    deepest!!.platformFailure("avfoundation").causes.size shouldBe MAX_CAUSES
  }

  private fun errorOf(
    domain: String,
    code: Long,
    description: String,
    underlying: NSError? = null,
  ): NSError {
    val info = mutableMapOf<Any?, Any?>(NSLocalizedDescriptionKey to description)
    if (underlying != null) info[NSUnderlyingErrorKey] = underlying
    return NSError.errorWithDomain(domain, code, info)
  }
}
