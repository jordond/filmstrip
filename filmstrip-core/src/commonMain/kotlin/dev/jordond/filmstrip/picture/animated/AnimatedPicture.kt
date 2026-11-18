package dev.jordond.filmstrip.picture.animated

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.FrameTimeline
import dev.jordond.filmstrip.picture.PICTURE_PEAK_CEILING
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.bytes.ByteView
import dev.jordond.filmstrip.picture.bytes.MalformedPicture
import dev.jordond.filmstrip.picture.bytes.ascii
import dev.jordond.filmstrip.picture.container
import dev.jordond.filmstrip.picture.pictureOrientationOf

/**
 * An animated GIF, APNG or WebP split into its frames and the timeline they play on.
 *
 * Every frame is a rectangle of [canvas] with the disposal and blend a compositor needs to rebuild what the file shows
 * after it. The [timeline] holds each frame's delay as shown and the file's plays.
 *
 * [canvas] and every frame's region are in stored coordinates. [orientation] is the turn the file asks for, which
 * [compositeFrames] applies to each frame it hands on, so everything a backend draws is [shown] in size.
 *
 * @property canvas The size every frame is composited onto, as the file stores it.
 * @property frames The frames in the order they play.
 * @property timeline When each frame shows and how many times the sequence plays.
 * @property orientation How the file's tags turn and mirror its frames to show them the right way up.
 */
@InternalFilmstripApi
public class AnimatedPicture(
  public val canvas: Size,
  public val frames: List<StoredFrame>,
  public val timeline: FrameTimeline,
  public val orientation: PictureOrientation = PictureOrientation.Normal,
) {
  /**
   * The size of every frame [compositeFrames] hands on, which is [canvas] with its sides swapped by a quarter turn.
   */
  public val shown: Size get() = orientation.orientedSize(canvas)
}

/**
 * One frame of an [AnimatedPicture], the rectangle it covers, how it meets the canvas and what it leaves behind.
 *
 * @property region The rectangle of the canvas the frame covers.
 * @property blend How the frame's texels meet the canvas under them.
 * @property disposal What the frame leaves behind once it has been shown.
 * @property pixels The frame's texels, decoded or still to be.
 */
@InternalFilmstripApi
public class StoredFrame(
  public val region: PictureCrop,
  public val blend: FrameBlend,
  public val disposal: FrameDisposal,
  public val pixels: FramePixels,
)

/**
 * How a frame's texels meet the canvas under them.
 */
@InternalFilmstripApi
public enum class FrameBlend {
  /**
   * The frame's texels replace the canvas, transparent ones included.
   */
  Source,

  /**
   * The frame's texels are laid over the canvas as straight alpha, so a transparent one leaves the canvas as it is.
   */
  Over,
}

/**
 * What a frame leaves behind once it has been shown, before the next one is drawn.
 */
@InternalFilmstripApi
public enum class FrameDisposal {
  /**
   * The canvas is left as the frame drew it.
   */
  None,

  /**
   * The frame's region is cleared to transparent, whatever background colour or transparency flag the file carries.
   */
  Background,

  /**
   * The frame's region is put back as it was before the frame was drawn. On the first frame it reads as [Background].
   */
  Previous,
}

/**
 * The texels of one frame.
 */
@InternalFilmstripApi
public sealed interface FramePixels {
  /**
   * Texels an APNG frame decodes to in core, four unsigned 16-bit samples a texel, red, green, blue and alpha, row by
   * row, in the layout a [dev.jordond.filmstrip.picture.png.PngSamples] has.
   *
   * A sample stored in 8 bits is scaled to the full range, so it is multiplied by 257. Read one with
   * `toInt() and 0xFFFF`.
   *
   * @property rgba The samples of every texel of the frame's region.
   * @property sixteenBit Whether the file stores 16-bit samples.
   */
  @InternalFilmstripApi
  public class Samples(
    public val rgba: ShortArray,
    public val sixteenBit: Boolean,
  ) : FramePixels

  /**
   * Texels a GIF frame decodes to in core, one packed 8-bit ARGB int a texel, row by row. A texel is opaque or it is
   * zero, since a GIF has no partial alpha.
   *
   * @property argb The texels of the frame's region.
   */
  @InternalFilmstripApi
  public class Texels(
    public val argb: IntArray,
  ) : FramePixels

  /**
   * A still WebP file holding just this frame, for a backend to decode with the decoder it already has.
   *
   * @property bytes The bytes of the file.
   */
  @InternalFilmstripApi
  public class WebpStill(
    public val bytes: ByteArray,
  ) : FramePixels
}

/**
 * What [animatedPictureOf] makes of a file.
 */
@InternalFilmstripApi
public sealed interface AnimatedRead {
  /**
   * The file is a picture that does not animate, because it has one frame or no animation, so it is read the way a
   * still is.
   */
  @InternalFilmstripApi
  public data object Still : AnimatedRead

  /**
   * The file animates, and [picture] is every frame of it.
   */
  @InternalFilmstripApi
  public class Animated(
    public val picture: AnimatedPicture,
  ) : AnimatedRead

  /**
   * The file animates and cannot be drawn, for the reason [message] gives in one sentence that is printed as it is.
   */
  @InternalFilmstripApi
  public class Refused(
    public val message: String,
  ) : AnimatedRead
}

/**
 * Splits the GIF, APNG or animated WebP in [bytes] into frames, or says why it cannot be.
 *
 * A file with one frame, no animation, or a container that is not one of the three reads as [AnimatedRead.Still]. An
 * animated AVIF is refused by name before anything else is read. A file that animates but whose frames cannot be walked
 * to the end, one with a frame outside its canvas, and one whose decoded frames would hold more than [ceiling] bytes
 * are refused. Nothing is decoded for a refusal on size. Never throws.
 *
 * Every delay is read through [shownFrameDelay] and every play count through [framePlays]. The orientation is the one
 * [pictureOrientationOf] reads from the same bytes a still is turned by.
 *
 * @param ceiling The most bytes the decoded frames of a GIF or APNG may hold, 4 a texel for a GIF and 8 for an APNG.
 */
@InternalFilmstripApi
public fun animatedPictureOf(
  bytes: ByteArray,
  ceiling: Long = PICTURE_PEAK_CEILING,
): AnimatedRead {
  val file = ByteView(bytes, bytes.size)
  if (file.isAnimatedAvif()) return AnimatedRead.Refused(ANIMATED_AVIF_REFUSED)
  val read =
    try {
      when (file.container()) {
        PictureContainer.Gif -> file.readGif(ceiling)
        PictureContainer.Png -> file.readApng(ceiling)
        PictureContainer.Webp -> file.readWebp()
        else -> AnimatedRead.Still
      }
    } catch (_: MalformedPicture) {
      AnimatedRead.Refused(ANIMATED_UNREADABLE)
    }
  if (read !is AnimatedRead.Animated) return read
  val picture = read.picture
  val orientation = pictureOrientationOf(bytes)
  return AnimatedRead.Animated(AnimatedPicture(picture.canvas, picture.frames, picture.timeline, orientation))
}

/**
 * The sentence an animated AVIF is refused with, on every backend.
 */
@InternalFilmstripApi
public const val ANIMATED_AVIF_REFUSED: String =
  "An animated AVIF cannot be drawn as an overlay, since no backend can read past its first frame. Use an animated " +
    "WebP, an APNG or a GIF instead."

/**
 * The sentence an animated picture is refused with when drawing it needs at least [bytes] bytes and the most an overlay
 * may hold is [ceiling].
 *
 * Every refusal on size is this one, whether the count is the decoded frames the reader makes before it decodes or the
 * peak of a backend's whole resolve.
 */
@InternalFilmstripApi
public fun animatedPictureTooLarge(
  bytes: Long,
  ceiling: Long,
): String =
  "This animated picture needs at least $bytes bytes to be read and drawn, which is more than the $ceiling bytes an " +
    "overlay may hold. Use fewer frames or a smaller picture."

internal const val ANIMATED_UNREADABLE: String =
  "This animated picture cannot be read to its last frame, so it may be cut short or damaged."

internal fun animatedFrameOutside(index: Int): String =
  "Frame $index of this animated picture lies outside its canvas, so it cannot be drawn."

internal fun animatedCanvasEmpty(): String = "This animated picture has a canvas with no area, so it cannot be drawn."

private val AVIF_SEQUENCE = ascii("avis")

// Whether the file is an AVIF image sequence, which names `avis` as its major brand or among its compatible ones.
private fun ByteView.isAnimatedAvif(): Boolean {
  if (container() != PictureContainer.Heif) return false
  if (matches(AVIF_BRAND_AT, AVIF_SEQUENCE)) return true
  val boxEnd = minOf(u32(0).takeIf { it >= AVIF_COMPATIBLE_AT } ?: end.toLong(), end.toLong()).toInt()
  return (AVIF_COMPATIBLE_AT until boxEnd - AVIF_SEQUENCE.size + 1 step AVIF_SEQUENCE.size).any {
    matches(it, AVIF_SEQUENCE)
  }
}

private const val AVIF_BRAND_AT = 8
private const val AVIF_COMPATIBLE_AT = 16
