@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageBitmap
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.orientedSamples
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

/**
 * A decoded overlay picture and how it describes the color of its codes.
 *
 * The bitmap holds the picture's stored codes with no color conversion, eight bits a channel, as stored, since it is
 * decoded with no orientation applied. A PNG also carries its stored samples at their full depth, and an AVIF Chrome
 * cannot decode through its own matrix carries the color core's evaluator turns its samples into, which the
 * compositor converts from instead. Those samples are laid out the way the picture's orientation shows it, through
 * [orientedSamples], and a picture with none is laid out by the compositor as it draws, through [orientation].
 *
 * @property bitmap The decoded picture.
 * @property size The size the compositor uploads the picture at. A picture carrying samples takes their size as they
 * are laid out, and any other picture the size of [bitmap].
 * @property color What `pictureColor` reads from the picture's bytes, or null when it is untagged.
 * @property samples What `pngSamples` reads from a PNG's bytes, what [PictureRoute.Evaluated] turns an AVIF's samples
 * into as sixteen-bit RGBA, or null for any other picture.
 * @property limits Why the picture lands short of how its bytes describe it, empty when it lands exactly.
 * @property orientation The turn the compositor lays the uploaded picture out by, [PictureOrientation.Normal] for one
 * carrying samples.
 */
internal class OverlayPicture(
  val bitmap: ImageBitmap,
  val size: Size,
  val color: PictureColor?,
  val samples: ShortArray?,
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
 * This decoded bitmap cut to [crop], the texels core reads an AVIF's `clap` properties keeping, or null when it holds
 * no such rectangle.
 *
 * Chrome is handed the picture with its `clap` dropped, so it decodes the whole stored frame and this cuts it down to
 * [crop]. The bitmap is kept as it is when [crop] is null or keeps all of it.
 */
internal suspend fun ImageBitmap.clapCropped(crop: PictureCrop?): ImageBitmap? {
  val stored = Size(width, height)
  if (crop == null || crop.keepsAll(stored)) return this
  if (!crop.liesWithin(stored)) return null
  return createImageBitmap(this, crop.left, crop.top, crop.size.width, crop.size.height, bitmapOptions()).await()
}

internal external fun createImageBitmap(
  image: JsAny,
  sx: Int,
  sy: Int,
  sw: Int,
  sh: Int,
  options: JsAny,
): Promise<ImageBitmap>

// The image's own codes go to the texture, with no conversion into the canvas's color space. A
// tagged picture is converted from those codes by the compositor, as its color describes them. The
// texels stay in the order they are stored, whatever orientation the file names.
// What every overlay picture is decoded and cut with: no color conversion and no turn.
internal fun bitmapOptions(): JsAny = JSON.parse(BITMAP_OPTIONS)

private const val BITMAP_OPTIONS = """{"colorSpaceConversion":"none","imageOrientation":"none"}"""
