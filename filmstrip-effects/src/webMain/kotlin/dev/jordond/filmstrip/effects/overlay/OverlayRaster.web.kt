@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.coroutines.await
import kotlin.coroutines.cancellation.CancellationException
import kotlin.io.encoding.Base64
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.math.ceil

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
 * The decoded images overlays draw, held for the resolver that owns them.
 *
 * A browser only decodes asynchronously, so [preload] decodes ahead of planning and the resolver
 * reads the answer back without waiting. An image that failed is remembered as failed. At most
 * [CAPACITY] images are held, and the one read longest ago is dropped first. A dropped bitmap is
 * never closed, since an overlay already built from it still draws it.
 */
internal class OverlayImages {
  private val entries = LinkedHashMap<ImageSource, ImageBitmap?>()

  /**
   * Decodes [source] unless it is already held.
   */
  suspend fun preload(source: ImageSource) {
    if (source in entries) {
      touch(source)
      return
    }
    val bitmap = decode(source)
    entries[source] = bitmap
    if (entries.size > CAPACITY) entries.remove(entries.keys.first())
  }

  /**
   * Whether [source] went through [preload] and is still held.
   */
  operator fun contains(source: ImageSource): Boolean = source in entries

  /**
   * The decoded [source], or null when it was never preloaded or would not decode.
   */
  operator fun get(source: ImageSource): ImageBitmap? = touch(source)

  private fun touch(source: ImageSource): ImageBitmap? {
    if (source !in entries) return null
    val bitmap = entries.remove(source)
    entries[source] = bitmap
    return bitmap
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

// A path names a filesystem no browser has. Bytes go in as a data URL, since fetching one yields a
// blob the same way on both targets.
private suspend fun decode(source: ImageSource): ImageBitmap? {
  val address =
    when (source) {
      is ImageSource.Path -> return null
      is ImageSource.Uri -> source.uri
      is ImageSource.Bytes -> DATA_URL_PREFIX + Base64.encode(source.bytes)
    }
  return try {
    val response = fetch(address).await()
    if (!response.ok) return null
    createImageBitmap(response.blob().await(), JSON.parse(BITMAP_OPTIONS)).await()
  } catch (cancelled: CancellationException) {
    throw cancelled
  } catch (undecodable: Throwable) {
    null
  }
}

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
private const val DATA_URL_PREFIX = "data:application/octet-stream;base64,"

// The image's own codes go to the texture, with no conversion into the canvas's colour space.
private const val BITMAP_OPTIONS = """{"colorSpaceConversion":"none"}"""
