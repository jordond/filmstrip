package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.RestatedHeif
import dev.jordond.filmstrip.media.UnturnedHeif
import kotlin.test.fail

/**
 * The bytes a HEIF rewrite hands a decoder, whether it dropped an `irot` or `imir` or found none to drop.
 *
 * Fails the test with the rewrite's reason when it refuses the file.
 */
@OptIn(InternalFilmstripApi::class)
internal val UnturnedHeif.handed: ByteArray
  get() =
    when (this) {
      is UnturnedHeif.Handed -> bytes
      is UnturnedHeif.Refused -> fail("The rewrite was refused, since $reason")
    }

/**
 * The bytes of a restate that applied, with the colour stated in them.
 *
 * Fails the test when the restate does not apply or the file is refused.
 */
@OptIn(InternalFilmstripApi::class)
internal val RestatedHeif.restated: ByteArray
  get() =
    when (this) {
      is RestatedHeif.Restated -> bytes
      is RestatedHeif.Unrestated -> fail("The restate does not apply")
      is UnturnedHeif.Refused -> fail("The rewrite was refused, since $reason")
    }
