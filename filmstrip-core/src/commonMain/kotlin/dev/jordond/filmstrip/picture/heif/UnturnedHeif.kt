package dev.jordond.filmstrip.picture.heif

import dev.jordond.filmstrip.InternalFilmstripApi

/**
 * What dropping the `irot`, `imir` and `clap` associations of an AVIF or HEIC comes to, as [heifWithoutOrientation]
 * and [heifWithExplicitCodestreamColor] give it.
 *
 * A [Handed] result holds the bytes a decoder is handed, whose primary item and alpha items list no `imir`, no `clap`
 * and no `irot` but one of 0, so no decoder crops, turns or mirrors the samples. A [Refused] one holds no bytes. Its
 * file holds an `irot`, `imir` or `clap` the drop could neither take off nor rule out, and handed over as it is, its
 * decoder could crop or turn the picture another way than core does.
 */
@InternalFilmstripApi
public sealed interface UnturnedHeif {
  /**
   * A result a decoder can be handed.
   *
   * @property bytes What the decoder is handed.
   */
  @InternalFilmstripApi
  public sealed interface Handed : UnturnedHeif {
    public val bytes: ByteArray
  }

  /**
   * The file listed an `irot`, `imir` or `clap` on its primary item or an alpha item, and [bytes] are the file without
   * them.
   * Nothing else in the file is changed but the box sizes and item locations the dropped bytes move.
   */
  @InternalFilmstripApi
  public class Dropped(
    override val bytes: ByteArray,
  ) : Handed

  /**
   * The file's primary item and alpha items list no `imir`, no `clap` and no `irot` but one of 0, and no later `ipma`
   * entry for them lists one, or it is no AVIF or HEIC, so [bytes] carry nothing a decoder crops or turns by.
   */
  @InternalFilmstripApi
  public class Untagged(
    override val bytes: ByteArray,
  ) : Handed

  /**
   * The file's `ipco` holds an `irot`, `imir` or `clap`, and the drop could not take them off its primary item and
   * alpha items, or could not tell whether those list one. A file whose primary item lists none is refused too when the
   * drop cannot read its listing the way libheif does. It is the refusal [RestatedHeif] gives too, so a backend reports
   * the same sentence whichever function refused.
   *
   * @property reason Why not, as a clause such as "it is an image sequence with a moov box".
   */
  @InternalFilmstripApi
  public class Refused(
    public val reason: String,
  ) : UnturnedHeif,
    RestatedHeif {
    /**
     * What a backend reports when it refuses the picture for this. It is one sentence naming [reason], printed as it
     * is.
     */
    public val message: String
      get() =
        "The picture's HEIF irot, imir or clap could not be dropped before its decoder reads it, since $reason, so " +
          "the decoder could crop or turn it another way than core."
  }
}

/**
 * What [heifWithRestatedColor] comes to for an AVIF or HEIC, each result with its `irot`, `imir` and `clap` dropped
 * as [heifWithoutOrientation] drops them.
 *
 * A [Restated] result holds bytes whose units state the answer, and an [Unrestated] one the bytes a caller hands over
 * when the restate does not apply. A file whose drop is refused is [UnturnedHeif.Refused].
 */
@InternalFilmstripApi
public sealed interface RestatedHeif {
  /**
   * The restate applied, and [bytes] state the matrix, primaries and range it gives, with any `irot`, `imir` and `clap`
   * dropped. That includes a file whose units already stated them, tagged or not.
   *
   * @property bytes The file with its units stating the answer.
   */
  @InternalFilmstripApi
  public class Restated(
    public val bytes: ByteArray,
  ) : RestatedHeif

  /**
   * The restate does not apply, and [bytes] are the file with only its `irot`, `imir` and `clap` dropped, or the input
   * itself when the drop finds it untagged, as [UnturnedHeif.Untagged] reads it. A decoder handed them lands the
   * picture with its own color.
   *
   * @property bytes The file without its `irot`, `imir` and `clap`, its units as they were.
   */
  @InternalFilmstripApi
  public class Unrestated(
    public val bytes: ByteArray,
  ) : RestatedHeif
}
