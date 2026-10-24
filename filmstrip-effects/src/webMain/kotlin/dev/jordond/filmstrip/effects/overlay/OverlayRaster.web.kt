@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.PngExtent
import dev.jordond.filmstrip.internal.pictureColour
import dev.jordond.filmstrip.internal.pngExtent
import dev.jordond.filmstrip.internal.pngSamples
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.PictureColour
import dev.jordond.filmstrip.media.blobOf
import dev.jordond.filmstrip.media.toByteArray
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * A run of text drawn to a canvas sized to the glyphs it covers, plus the plate around them.
 *
 * @property canvas The drawn text.
 * @property lines The lines the text was broken into, top to bottom.
 * @property capHeight How tall a capital stands in the face the text was drawn in, in pixels.
 */
internal class TextRaster(
  val canvas: OffscreenCanvas,
  val lines: List<String>,
  val capHeight: Float,
) {
  val size: Size get() = Size(canvas.width, canvas.height)
}

/**
 * Draws [text] to a canvas at the pixel size it occupies on [frame].
 *
 * The face is sized so a capital stands [capHeightOn] tall, measured off a capital drawn at
 * [CAP_PROBE_SIZE]. Lines wrap greedily at [wrapWidthOn], breaking at spaces and at every `\n`, and
 * a word wider than a whole line is split between graphemes. The block is as wide as its longest
 * line, each line is aligned inside it, and a plate adds [platePaddingOn] on every side.
 *
 * Colours are written as the codes the style names, with no colour management.
 *
 * @return The drawn text, or null when the style leaves nothing to draw.
 */
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
): TextRaster? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null
  val measure = OffscreenCanvas(1, 1).getContext(CONTEXT_2D) ?: return null

  val family = familyOf(style)
  measure.font = fontOf(style.weight, CAP_PROBE_SIZE, family)
  val probed = measure.measureText(CAP_PROBE).actualBoundingBoxAscent.toFloat()
  val cap = style.capHeightOn(frame)
  val font = fontOf(style.weight, if (probed > 0f) CAP_PROBE_SIZE * cap / probed else cap, family)
  measure.font = font

  val lines = wrapLines(text, style.wrapWidthOn(frame)) { measure.measureText(it).width }
  val widths = lines.map { measure.measureText(it).width }
  val metrics = measure.measureText(CAP_PROBE)
  val advance = metrics.fontBoundingBoxAscent + metrics.fontBoundingBoxDescent
  val block = ceil(widths.max()).toInt().coerceAtLeast(1)
  val height = ceil(advance * lines.size).toInt()
  if (height <= 0) return null

  val padding = style.platePaddingOn(frame)
  val canvas = OffscreenCanvas(block + 2 * padding, height + 2 * padding)
  val context = canvas.getContext(CONTEXT_2D) ?: return null
  style.backgroundColor?.let {
    context.fillStyle = cssColorOf(it)
    context.fillRect(0.0, 0.0, canvas.width.toDouble(), canvas.height.toDouble())
  }
  context.font = font
  context.fillStyle = cssColorOf(style.color)
  context.textBaseline = ALPHABETIC
  lines.forEachIndexed { index, line ->
    val x = padding + style.alignment.offset(block - widths[index])
    val y = padding + index * advance + metrics.fontBoundingBoxAscent
    context.fillText(line, x, y)
  }

  return TextRaster(canvas, lines, context.measureText(CAP_PROBE).actualBoundingBoxAscent.toFloat())
}

/**
 * Breaks [text] into lines no wider than [width] where it can.
 *
 * Greedy: each line takes as many space-separated words as fit, and every `\n` starts a new line. A
 * word wider than [width] on its own is split between graphemes, so it fills lines rather than
 * running off the edge of one.
 *
 * @param measure The width of a run of text, in the same pixels as [width].
 */
internal fun wrapLines(
  text: String,
  width: Int,
  measure: (String) -> Double,
): List<String> {
  val lines = mutableListOf<String>()
  for (paragraph in text.split('\n')) {
    var line = ""
    for (word in paragraph.split(' ')) {
      val joined = if (line.isEmpty()) word else "$line $word"
      if (measure(joined) <= width) {
        line = joined
        continue
      }
      if (line.isNotEmpty()) lines += line
      line = word
      if (measure(word) <= width) continue

      line = ""
      for (grapheme in graphemesOf(word)) {
        if (line.isNotEmpty() && measure(line + grapheme) > width) {
          lines += line
          line = grapheme
        } else {
          line += grapheme
        }
      }
    }
    lines += line
  }
  return lines
}

/**
 * A decoded overlay picture and how it describes the colour of its codes.
 *
 * The bitmap holds the picture's stored codes with no colour conversion, eight bits a channel. A PNG also carries its
 * stored samples at their full depth, and an AVIF Chrome cannot decode through its own matrix carries the colour core's
 * evaluator turns its samples into, which the compositor converts from instead.
 *
 * @property bitmap The decoded picture.
 * @property size The size the compositor uploads the picture at. A PNG takes the size of its samples, in the order it
 * stores them, and any other picture the size of [bitmap].
 * @property colour What [pictureColour] reads from the picture's bytes, or null when it is untagged.
 * @property samples What [pngSamples] reads from a PNG's bytes, what [AvifLanding.Evaluated] turns an AVIF's samples
 * into as sixteen-bit RGBA, or null for any other picture.
 * @property limits Why the picture lands short of how its bytes describe it, empty when it lands exactly.
 */
internal class OverlayPicture(
  val bitmap: ImageBitmap,
  val size: Size,
  val colour: PictureColour?,
  val samples: ShortArray?,
  val limits: List<Degradation> = emptyList(),
)

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
      try {
        fonts.load(font).await()
      } catch (cancelled: CancellationException) {
        throw cancelled
      } catch (unloadable: Throwable) {
        // The browser draws the next family in the list instead.
      }
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
// avifLanding hands it, so an AVIF lands through the matrix core reads it with, through its samples and core's
// evaluator, or through the nearest matrix Chrome reads. A PNG core cannot read is refused, since its samples are what
// gets drawn, and it is sized from them, since the browser turns the bitmap by any eXIf orientation and no other
// backend does. A PNG whose peak passes the ceiling is refused by size before anything is decoded.
private suspend fun decode(
  source: ImageSource,
  ceiling: Long,
): OverlayDecode =
  try {
    val bytes =
      when (source) {
        is ImageSource.Path -> null
        is ImageSource.Uri -> fetchBytes(source.uri)
        is ImageSource.Bytes -> source.bytes
      }
    bytes?.let { pictureOf(it, ceiling) } ?: OverlayDecode.Undecodable
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (undecodable: Throwable) {
    OverlayDecode.Undecodable
  }

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
  if (isPng(bytes)) {
    val extent = pngExtent(bytes) ?: return OverlayDecode.Undecodable
    val peak = pngPeakBytes(extent)
    if (peak == null || peak > ceiling) return OverlayDecode.Refused(tooLargeToDecode(extent, peak, ceiling))
    val samples = pngSamples(bytes, ceiling) ?: return OverlayDecode.Undecodable
    val bitmap = bitmapOf(bytes)
    return OverlayDecode.Decoded(
      OverlayPicture(bitmap, Size(samples.width, samples.height), pictureColour(bytes), samples.rgba),
    )
  }
  val landing =
    when (val landing = avifLanding(bytes)) {
      is AvifLanding.Refused -> return OverlayDecode.Refused(landing.message)
      is AvifLanding.Decoded -> landing
      is AvifLanding.Evaluated -> return evaluatedPicture(bytes, landing)
    }
  val bitmap = bitmapOf(landing.bytes)
  val picture = OverlayPicture(bitmap, Size(bitmap.width, bitmap.height), pictureColour(bytes), null, landing.limits)
  return OverlayDecode.Decoded(picture)
}

// The identity bitmap's codes are the picture's luma, blue difference and red difference samples as green, blue and
// red, so each texel is turned into colour by the landing's evaluator and carried as sixteen-bit samples.
private suspend fun evaluatedPicture(
  bytes: ByteArray,
  landing: AvifLanding.Evaluated,
): OverlayDecode {
  val bitmap = bitmapOf(landing.bytes)
  val codes = codesOf(bitmap) ?: return OverlayDecode.Undecodable
  val samples = ShortArray(codes.size)
  val rgb = DoubleArray(RGB_CHANNELS)
  for (texel in codes.indices step RGBA_CHANNELS) {
    val red = codes[texel].toInt() and BYTE_MASK
    val green = codes[texel + 1].toInt() and BYTE_MASK
    val blue = codes[texel + 2].toInt() and BYTE_MASK
    landing.evaluator.rgb(green, blue, red, rgb)
    for (channel in 0 until RGB_CHANNELS) samples[texel + channel] = (rgb[channel] * MAX_SAMPLE).roundToInt().toShort()
    samples[texel + ALPHA] = ((codes[texel + ALPHA].toInt() and BYTE_MASK) * SAMPLE_PER_CODE).toShort()
  }
  val picture = OverlayPicture(bitmap, Size(bitmap.width, bitmap.height), pictureColour(bytes), samples)
  return OverlayDecode.Decoded(picture)
}

private suspend fun bitmapOf(bytes: ByteArray): ImageBitmap =
  createImageBitmap(blobOf(bytes, OCTET_STREAM), JSON.parse(BITMAP_OPTIONS)).await()

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

private fun isPng(bytes: ByteArray): Boolean =
  bytes.size >= PNG_SIGNATURE.size && PNG_SIGNATURE.indices.all { bytes[it] == PNG_SIGNATURE[it] }

private fun graphemesOf(word: String): List<String> {
  val segments = Segmenter().segment(word)
  val graphemes = mutableListOf<String>()
  var index = 0
  while (index < word.length) {
    val grapheme = segments.containing(index)?.segment?.takeIf { it.isNotEmpty() } ?: word.substring(index, index + 1)
    graphemes += grapheme
    index += grapheme.length
  }
  return graphemes
}

private fun fontOf(
  weight: FontWeight,
  size: Float,
  family: String,
): String = "${weight.cssWeight()} ${size}px $family"

// A named family goes in as a CSS string, so a comma, a quote, a leading digit or a control character
// stays part of the name. The generic families behind it are keywords and stay bare, so a face that
// never loaded falls back the way the default does.
private fun familyOf(style: TextStyle): String {
  val family = style.resolvedFontFamily ?: return DEFAULT_FAMILY
  return "${cssStringOf(family)}, $DEFAULT_FAMILY"
}

// A control character is written as a hex escape ended by a space, since a raw newline ends the
// string and leaves the whole shorthand unparsed.
private fun cssStringOf(value: String): String =
  buildString {
    append('"')
    value.forEach { char ->
      when {
        char == '\\' || char == '"' -> append('\\').append(char)
        char < ' ' || char == DELETE -> append('\\').append(char.code.toString(HEX)).append(' ')
        else -> append(char)
      }
    }
    append('"')
  }

private fun FontWeight.cssWeight(): Int =
  when (this) {
    FontWeight.Regular -> 400
    FontWeight.Medium -> 500
    FontWeight.Bold -> 700
  }

private fun TextAlignment.offset(slack: Double): Double =
  when (this) {
    TextAlignment.Start -> 0.0
    TextAlignment.Center -> slack / 2
    TextAlignment.End -> slack
  }

private fun cssColorOf(argb: Int): String {
  val alpha = (argb ushr 24 and 0xFF) / 255.0
  return "rgba(${argb ushr 16 and 0xFF}, ${argb ushr 8 and 0xFF}, ${argb and 0xFF}, $alpha)"
}

private const val CONTEXT_2D = "2d"
private const val CAP_PROBE = "H"
private const val ALPHABETIC = "alphabetic"
private const val DEFAULT_FAMILY = "system-ui, sans-serif"
private const val DELETE = '\u007F'
private const val HEX = 16
private const val OCTET_STREAM = "application/octet-stream"
private const val RGB_CHANNELS = 3
private const val RGBA_CHANNELS = 4
private const val ALPHA = 3
private const val BYTE_MASK = 0xFF
private const val MAX_SAMPLE = 65535.0
private const val SAMPLE_PER_CODE = 257

private val PNG_SIGNATURE = byteArrayOf(0x89.toByte(), 0x50, 0x4E, 0x47, 0x0D, 0x0A, 0x1A, 0x0A)

// The image's own codes go to the texture, with no conversion into the canvas's colour space. A
// tagged picture is converted from those codes by the compositor, as its colour describes them.
private const val BITMAP_OPTIONS = """{"colorSpaceConversion":"none"}"""
