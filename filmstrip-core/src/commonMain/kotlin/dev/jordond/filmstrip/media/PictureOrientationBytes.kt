package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.internal.heifUnturned

/**
 * What dropping the `irot`, `imir` and `clap` associations of an AVIF or HEIC comes to, as [heifWithoutOrientation]
 * and [heifWithExplicitCodestreamColour] give it.
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
 * What [heifWithRestatedColour] comes to for an AVIF or HEIC, each result with its `irot`, `imir` and `clap` dropped
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
   * picture with its own colour.
   *
   * @property bytes The file without its `irot`, `imir` and `clap`, its units as they were.
   */
  @InternalFilmstripApi
  public class Unrestated(
    public val bytes: ByteArray,
  ) : RestatedHeif
}

/**
 * [bytes] with the `irot`, `imir` and `clap` associations of an AVIF or HEIC's primary item, and of its alpha items,
 * dropped from their `ipma` entries, so a decoder hands back the whole stored picture and
 * [dev.jordond.filmstrip.internal.pictureOrientationOf] on the result reads [PictureOrientation.Normal]. Read the
 * orientation and [dev.jordond.filmstrip.internal.pictureCropOf] from [bytes], not from the result, and crop by the one
 * before turning by the other. The colour is left as it is, which [heifWithExplicitCodestreamColour]
 * states in the same pass.
 *
 * The properties stay in `ipco` and every other association keeps its place and bits. An alpha item, one whose `auxl`
 * reference names the primary item and whose `auxC` names an alpha type, is cropped and turned by its own associations
 * and drawn with the primary item, so its associations are dropped too. A gain map, depth or matte item, up to 256 of
 * them, a thumbnail and a tile keep theirs and never stop the drop. Box sizes and item locations move with the dropped
 * bytes. Every item is read from the file's first `iprp`, as [dev.jordond.filmstrip.internal.pictureOrientationOf]
 * reads it.
 *
 * A file is tagged when its primary item or an alpha item lists a `clap`, an `imir` or an `irot` of anything but 0,
 * even when they fold back into [PictureOrientation.Normal], or a later `ipma` entry for one of them lists one.
 * Gives [UnturnedHeif.Untagged] with [bytes] itself for anything but an AVIF or HEIC and for an untagged one, an `irot`
 * of 0 alone and an image sequence with a `moov` box included. A tagged file is [UnturnedHeif.Dropped], or
 * [UnturnedHeif.Refused] when
 * - it has a `moov` box, whose sample tables hold offsets of their own,
 * - it has more than 15 alpha items,
 * - the primary item or an alpha item has a second `ipma` entry,
 * - an alpha item's `irot` and `imir` fold into another orientation than the primary item's,
 * - an `iscl` is listed where the `irot` and `imir` ahead of it fold into a turn or mirror, since it scales the turned
 * picture,
 * - the primary item's `clap` reads no crop, or an alpha item's `clap` crops it to another rectangle than the primary
 * item's,
 * - a dropped byte falls inside an item's data,
 * - it has more than 1024 top-level boxes,
 * - the file without them does not read back.
 *
 * A `clap` listed after a turn is dropped like one before it, since
 * [dev.jordond.filmstrip.internal.pictureCropOf] carries it back to the stored picture.
 *
 * A file whose first `iprp` holds an `ipco` with no `irot`, `imir` or `clap` among the boxes that parse is untagged, a
 * truncated or malformed one included. Past that, one any part of which the drop reads does not parse is refused too,
 * and so is one that names no primary item, or one with more than 1024 `ipma` boxes or more than 256 items naming its
 * primary item through `auxl`, as tagged or too large to tell. So is one with an `ipma` above version 1, or whose
 * primary item lists an `ispe` above version 0 or has no entry in the first `iprp`, which libheif refuses and core
 * never turns. It never throws.
 */
@InternalFilmstripApi
public fun heifWithoutOrientation(bytes: ByteArray): UnturnedHeif = heifUnturned(bytes)
