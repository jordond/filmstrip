package dev.jordond.filmstrip.picture.heif

import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.fail

// Reads the result of a HEIF rewrite in a test, failing it when the result is not the kind asked for.

/**
 * The bytes a HEIF rewrite hands a decoder, whether it dropped an `irot` or `imir` or found none to drop.
 *
 * Fails the test with the rewrite's reason when it refuses the file.
 */
internal val UnturnedHeif.handed: ByteArray
  get() =
    when (this) {
      is UnturnedHeif.Handed -> bytes
      is UnturnedHeif.Refused -> fail("The rewrite was refused, since $reason")
    }

/**
 * The bytes of a restate that applied, with the color stated in them.
 *
 * Fails the test when the restate does not apply or the file is refused.
 */
internal val RestatedHeif.restated: ByteArray
  get() =
    when (this) {
      is RestatedHeif.Restated -> bytes
      is RestatedHeif.Unrestated -> fail("The restate does not apply")
      is UnturnedHeif.Refused -> fail("The rewrite was refused, since $reason")
    }

/**
 * The bytes of a restate that does not apply, holding only the drop.
 *
 * Fails the test on any other result.
 */
internal val RestatedHeif.unrestated: ByteArray get() = shouldBeInstanceOf<RestatedHeif.Unrestated>().bytes

/**
 * The bytes of a rewrite that dropped an `irot` or `imir`.
 *
 * Fails the test on any other result.
 */
internal val UnturnedHeif.dropped: ByteArray get() = shouldBeInstanceOf<UnturnedHeif.Dropped>().bytes

/**
 * The bytes of a rewrite of a file with nothing to drop, which are the file's own.
 *
 * Fails the test on any other result.
 */
internal val UnturnedHeif.untagged: ByteArray get() = shouldBeInstanceOf<UnturnedHeif.Untagged>().bytes

/**
 * Checks that the rewrite refused the file for [reason].
 *
 * Fails the test when it handed bytes over or refused the file for another reason.
 */
internal fun UnturnedHeif.shouldBeRefused(reason: String) {
  shouldBeInstanceOf<UnturnedHeif.Refused>().reason shouldBe reason
}

/**
 * Checks that the restate refused the file for [reason].
 *
 * Fails the test when it handed bytes over or refused the file for another reason.
 */
internal fun RestatedHeif.shouldBeRefused(reason: String) {
  shouldBeInstanceOf<UnturnedHeif.Refused>().reason shouldBe reason
}
