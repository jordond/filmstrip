@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.OverlaySource
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageBitmap
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import kotlin.js.ExperimentalWasmJsInterop

/**
 * A decoded overlay picture, what the compositor uploads it from and how it describes the color of its codes.
 *
 * A PNG is uploaded from its stored samples at their full depth, and an AVIF Chrome cannot decode through its own
 * matrix from the color core's evaluator turns its samples into, which the compositor converts from. Those samples are
 * laid out the way the picture's orientation shows it, through [orientedSamples]. Any other picture is uploaded from
 * the bitmap the browser decodes it to, eight bits a channel with no color conversion and no turn, or from the codes of
 * the rectangle its `clap` keeps, and is laid out by the compositor as it draws, through [orientation].
 *
 * @property source What the compositor uploads, [OverlaySource.Samples] for a picture carrying samples,
 * [OverlaySource.Codes] for one its `clap` cuts and [OverlaySource.Image] for any other.
 * @property size The size the compositor uploads the picture at. A picture carrying samples takes their size as they
 * are laid out, a cut picture the size of its rectangle, and any other picture the size of its bitmap.
 * @property color What `pictureColor` reads from the picture's bytes, or null when it is untagged.
 * @property limits Why the picture lands short of how its bytes describe it, empty when it lands exactly.
 * @property orientation The turn the compositor lays the uploaded picture out by, [PictureOrientation.Normal] for one
 * carrying samples.
 */
internal class OverlayPicture(
  val source: OverlaySource,
  val size: Size,
  val color: PictureColor?,
  val limits: List<Degradation> = emptyList(),
  val orientation: PictureOrientation = PictureOrientation.Normal,
) {
  /**
   * The size the picture is shown at, [size] laid out by [orientation].
   */
  val shownSize: Size get() = orientation.orientedSize(size)
}

// These samples of a picture stored at stored, laid out the way this orientation shows it, with the size they take.
internal fun PictureOrientation.laidOut(
  stored: Size,
  rgba: ShortArray,
): Pair<Size, ShortArray> =
  if (this == PictureOrientation.Normal) stored to rgba else orientedSize(stored) to orientedSamples(this, rgba, stored)

/**
 * The rectangle of this decoded bitmap that [crop] keeps, the texels core reads an AVIF's `clap` properties keeping, or
 * null when there is no crop or it keeps the whole bitmap.
 *
 * Chrome is handed the picture with its `clap` dropped, so it decodes the whole stored frame and the caller reads the
 * rectangle out of it with [codesOf]. The rectangle may reach past the bitmap, which [PictureCrop.liesWithin] answers.
 */
internal fun ImageBitmap.clapCut(crop: PictureCrop?): PictureCrop? =
  crop?.takeUnless { it.keepsAll(Size(width, height)) }

// What every overlay picture is decoded with: no color conversion, no turn and no premultiplying, so the
// bitmap holds the straight codes a read gives back at any alpha.
internal fun bitmapOptions(): JsAny = JSON.parse(BITMAP_OPTIONS)

private const val BITMAP_OPTIONS =
  """{"colorSpaceConversion":"none","imageOrientation":"none","premultiplyAlpha":"none"}"""
