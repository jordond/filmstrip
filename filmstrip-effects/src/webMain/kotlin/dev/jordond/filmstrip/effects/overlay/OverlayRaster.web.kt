@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.awaitCatching
import dev.jordond.filmstrip.media.ImageBitmap
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.blobOf
import dev.jordond.filmstrip.media.createImageBitmap
import dev.jordond.filmstrip.media.fetch
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.picture.PictureContainer
import dev.jordond.filmstrip.picture.PictureCrop
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.pictureColor
import dev.jordond.filmstrip.picture.pictureContainerOf
import dev.jordond.filmstrip.picture.pictureCropOf
import dev.jordond.filmstrip.picture.pictureOrientationOf
import dev.jordond.filmstrip.picture.pictureWithoutOrientationTag
import dev.jordond.filmstrip.picture.png.PngExtent
import dev.jordond.filmstrip.picture.png.pngExtent
import dev.jordond.filmstrip.picture.png.pngSamples
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.roundToInt

/**
 * What decoding an overlay image gave, as [OverlayImages] holds it.
 */
internal sealed interface OverlayDecode {
  /**
   * The image decoded to [picture].
   */
  class Decoded(
    val picture: OverlayPicture,
  ) : OverlayDecode

  /**
   * The image is refused before it is decoded, since it cannot land the way its file describes.
   *
   * @property message What the overlay reports.
   */
  class Refused(
    val message: String,
  ) : OverlayDecode

  /**
   * The image could not be read or decoded.
   */
  data object Undecodable : OverlayDecode
}

/**
 * The decoded images overlays draw, held for the resolver that owns them.
 *
 * A browser only decodes asynchronously, so [preload] decodes ahead of planning and the resolver
 * reads the answer back without waiting. An image that failed is remembered as failed. At most
 * [CAPACITY] images are held, and the one read longest ago is dropped first. A dropped bitmap is
 * never closed, since an overlay already built from it still draws it.
 *
 * @param ceiling The most bytes decoding one PNG may hold at its peak, as [pngPeakBytes] counts it.
 */
internal class OverlayImages(
  private val ceiling: Long = PICTURE_PEAK_CEILING,
) {
  private val entries = LinkedHashMap<ImageSource, OverlayDecode>()

  /**
   * Decodes [source] unless it is already held.
   */
  suspend fun preload(source: ImageSource) {
    if (source in entries) {
      touch(source)
      return
    }
    entries[source] = decode(source, ceiling)
    if (entries.size > CAPACITY) entries.remove(entries.keys.first())
  }

  /**
   * Whether [source] went through [preload] and is still held.
   */
  operator fun contains(source: ImageSource): Boolean = source in entries

  /**
   * What decoding [source] gave, or null when it was never preloaded.
   */
  operator fun get(source: ImageSource): OverlayDecode? = touch(source)

  private fun touch(source: ImageSource): OverlayDecode? {
    val decoded = entries.remove(source) ?: return null
    entries[source] = decoded
    return decoded
  }

  private companion object {
    const val CAPACITY = 16
  }
}

/**
 * Decodes every overlay image in [composition] into [images] and waits for every face its text
 * overlays name.
 *
 * Walks the composition's own effects, each track's and each clip's. A face that fails to load is
 * left to the browser's fallback, the way a missing face is drawn everywhere else.
 */
internal suspend fun preloadOverlays(
  composition: EditComposition,
  images: OverlayImages,
) {
  val effects = overlayEffectsOf(composition)

  effects
    .filterIsInstance<ImageOverlay>()
    .map { it.image }
    .distinct()
    .forEach { images.preload(it) }

  val fonts = globalThis.fonts ?: globalThis.document?.fonts ?: return
  effects
    .filterIsInstance<TextOverlay>()
    .map { fontOf(it.style.weight, CAP_PROBE_SIZE, familyOf(it.style)) }
    .distinct()
    .forEach { font ->
      // A font that does not load leaves the browser drawing the next family in the list.
      awaitCatching { fonts.load(font).await() }
    }
}

/**
 * How an image is named in a message about it.
 *
 * A path or a URI is named by itself. Bytes are named by their length, since their content is no
 * use in a message.
 */
internal fun ImageSource.named(): String =
  when (this) {
    is ImageSource.Path -> "the image at $path"
    is ImageSource.Uri -> "the image at $uri"
    is ImageSource.Bytes -> "the ${bytes.size}-byte image"
  }

private fun overlayEffectsOf(composition: EditComposition): List<EffectSpec> =
  composition.effects +
    composition.tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } }

// A path names a filesystem no browser has, and a URI is fetched for its bytes. The browser decodes the bytes
// avifRouteOf hands it, so an AVIF lands through the matrix core reads it with, through its samples and core's
// evaluator, or through the nearest matrix Chrome reads. A PNG core cannot read is refused, since its samples are what
// gets drawn, and it is sized from them. An AVIF is cut to the rectangle pictureCropOf reads out of its clap, and then
// every picture is turned by the orientation pictureOrientationOf reads out of its bytes as given, never by the
// browser. A PNG whose peak passes the ceiling is refused by size before anything is decoded.
private suspend fun decode(
  source: ImageSource,
  ceiling: Long,
): OverlayDecode =
  awaitCatching {
    val bytes =
      when (source) {
        is ImageSource.Path -> null
        is ImageSource.Uri -> fetchBytes(source.uri)
        is ImageSource.Bytes -> source.bytes
      }
    bytes?.let { pictureOf(it, ceiling) } ?: OverlayDecode.Undecodable
  }.getOrDefault(OverlayDecode.Undecodable)

private suspend fun fetchBytes(uri: String): ByteArray? {
  val response = fetch(uri).await()
  if (!response.ok) return null
  val blob = response.blob().await()
  return blob.arrayBuffer().await().toByteArray()
}

private suspend fun pictureOf(
  bytes: ByteArray,
  ceiling: Long,
): OverlayDecode {
  val orientation = pictureOrientationOf(bytes)
  if (pictureContainerOf(bytes) == PictureContainer.Png) {
    val extent = pngExtent(bytes) ?: return OverlayDecode.Undecodable
    val peak = pngPeakBytes(extent)
    if (peak == null || peak > ceiling) return OverlayDecode.Refused(tooLargeToDecode(extent, peak, ceiling))
    val samples = pngSamples(bytes, ceiling) ?: return OverlayDecode.Undecodable
    val bitmap = bitmapOf(bytes)
    val (size, rgba) = orientation.laidOut(Size(samples.width, samples.height), samples.rgba)
    return OverlayDecode.Decoded(OverlayPicture(bitmap, size, pictureColor(bytes), rgba))
  }
  val route =
    when (val route = avifRouteOf(bytes)) {
      is PictureRoute.Refused -> return OverlayDecode.Refused(route.message)
      is PictureRoute.Decoded -> route
      is PictureRoute.Evaluated -> return evaluatedPicture(bytes, route, orientation)
    }
  val crop = pictureCropOf(bytes)
  val bitmap = bitmapOf(route.bytes).let { it.clapCropped(crop) ?: return clapRefused(it, checkNotNull(crop)) }
  val size = Size(bitmap.width, bitmap.height)
  val picture = OverlayPicture(bitmap, size, pictureColor(bytes), null, route.limits, orientation)
  return OverlayDecode.Decoded(picture)
}

// The identity bitmap's codes are the picture's luma, blue difference and red difference samples as green, blue and
// red, so each texel is turned into color by the route's evaluator and carried as sixteen-bit samples.
private suspend fun evaluatedPicture(
  bytes: ByteArray,
  route: PictureRoute.Evaluated,
  orientation: PictureOrientation,
): OverlayDecode {
  val crop = pictureCropOf(bytes)
  val bitmap = bitmapOf(route.bytes).let { it.clapCropped(crop) ?: return clapRefused(it, checkNotNull(crop)) }
  val codes = codesOf(bitmap) ?: return OverlayDecode.Undecodable
  val samples = ShortArray(codes.size)
  val rgb = DoubleArray(RGB_CHANNELS)
  for (texel in codes.indices step RGBA_CHANNELS) {
    val red = codes[texel].toInt() and BYTE_MASK
    val green = codes[texel + 1].toInt() and BYTE_MASK
    val blue = codes[texel + 2].toInt() and BYTE_MASK
    route.evaluator.rgb(green, blue, red, rgb)
    for (channel in 0 until RGB_CHANNELS) samples[texel + channel] = (rgb[channel] * TOP_SAMPLE).roundToInt().toShort()
    samples[texel + ALPHA] = ((codes[texel + ALPHA].toInt() and BYTE_MASK) * EIGHT_BIT_STEP).toShort()
  }
  val (size, rgba) = orientation.laidOut(Size(bitmap.width, bitmap.height), samples)
  return OverlayDecode.Decoded(OverlayPicture(bitmap, size, pictureColor(bytes), rgba))
}

/**
 * The picture in [bytes] decoded as stored. Chrome turns a JPEG by its orientation tag even with `imageOrientation`
 * set to `none`, so the browser is handed the bytes with that tag written as 1 by [pictureWithoutOrientationTag], and
 * no decoder sees a turn the compositor lays the picture out by.
 */
internal suspend fun bitmapOf(bytes: ByteArray): ImageBitmap =
  createImageBitmap(blobOf(pictureWithoutOrientationTag(bytes), OCTET_STREAM), bitmapOptions()).await()

/**
 * The refusal of a picture whose decoded [bitmap] holds no rectangle [crop] names, with the sentence
 * [PictureCrop.refusalFor] gives.
 */
internal fun clapRefused(
  bitmap: ImageBitmap,
  crop: PictureCrop,
): OverlayDecode = OverlayDecode.Refused(crop.refusalFor(Size(bitmap.width, bitmap.height)))

/**
 * The bytes decoding the PNG [extent] describes holds at its peak, its inflated rows and sixteen-bit samples beside the
 * eight-bit bitmap the browser draws it from, four bytes a texel. It is null when the rows and samples alone pass what
 * core sizes. A 48 MP eight-bit RGBA picture peaks at 768 MB and a sixteen-bit one at 960 MB.
 */
internal fun pngPeakBytes(extent: PngExtent): Long? =
  extent.decodeBytes?.let { it + extent.width.toLong() * extent.height * RGBA_CHANNELS }

/**
 * The reason the PNG [extent] describes is refused when its [peak], as [pngPeakBytes] counts it, passes [ceiling], or
 * is null for passing what core sizes.
 */
internal fun tooLargeToDecode(
  extent: PngExtent,
  peak: Long?,
  ceiling: Long,
): String {
  val needs = peak?.let { "they need $it" } ?: "they need more than that"
  return "The ${extent.width} by ${extent.height} PNG is too large to draw. Its inflated rows, its sixteen-bit " +
    "samples and the eight-bit bitmap drawn from it must fit in $ceiling bytes, and $needs."
}

/**
 * The codes of [bitmap] as a canvas reads them back, four bytes a texel, red, green, blue then alpha, row by row from
 * the top left, or null when no canvas context is to be had.
 */
internal fun codesOf(bitmap: ImageBitmap): ByteArray? {
  val context = OffscreenCanvas(bitmap.width, bitmap.height).getContext(CONTEXT_2D) ?: return null
  context.drawImage(bitmap, 0.0, 0.0)
  return context
    .getImageData(0, 0, bitmap.width, bitmap.height)
    .data.buffer
    .toByteArray()
}

private const val OCTET_STREAM = "application/octet-stream"
private const val RGB_CHANNELS = 3
private const val RGBA_CHANNELS = 4
private const val ALPHA = 3
private const val BYTE_MASK = 0xFF
