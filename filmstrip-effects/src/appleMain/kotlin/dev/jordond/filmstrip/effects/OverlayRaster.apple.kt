package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.cinterop.BetaInteropApi
import kotlinx.cinterop.COpaquePointer
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.alloc
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.cValue
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.sizeOf
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import kotlinx.cinterop.value
import platform.CoreFoundation.CFAttributedStringCreate
import platform.CoreFoundation.CFDictionaryCreate
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRange
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreFoundation.kCFTypeDictionaryKeyCallBacks
import platform.CoreFoundation.kCFTypeDictionaryValueCallBacks
import platform.CoreGraphics.CGAffineTransformIdentity
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGContextSetTextMatrix
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPathCreateWithRect
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder32Big
import platform.CoreImage.CIImage
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.CTFontGetCapHeight
import platform.CoreText.CTFontRef
import platform.CoreText.CTFrameDraw
import platform.CoreText.CTFramesetterCreateFrame
import platform.CoreText.CTFramesetterCreateWithAttributedString
import platform.CoreText.CTFramesetterRef
import platform.CoreText.CTFramesetterSuggestFrameSizeWithConstraints
import platform.CoreText.CTParagraphStyleCreate
import platform.CoreText.CTParagraphStyleRef
import platform.CoreText.CTParagraphStyleSetting
import platform.CoreText.CTTextAlignment
import platform.CoreText.CTTextAlignmentVar
import platform.CoreText.kCTFontAttributeName
import platform.CoreText.kCTFontTraitBold
import platform.CoreText.kCTFontUIFontSystem
import platform.CoreText.kCTParagraphStyleAttributeName
import platform.CoreText.kCTParagraphStyleSpecifierAlignment
import platform.CoreText.kCTTextAlignmentCenter
import platform.CoreText.kCTTextAlignmentLeft
import platform.CoreText.kCTTextAlignmentRight
import platform.Foundation.NSData
import platform.Foundation.NSURL
import platform.Foundation.create
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * Decodes this source into a Core Image image, or null when it cannot be read.
 *
 * Nothing is drawn here. A `CIImage` from a URL is a recipe, and the decode happens the first time
 * a context renders it.
 */
@OptIn(ExperimentalForeignApi::class, BetaInteropApi::class)
internal fun ImageSource.decode(): CIImage? =
  when (this) {
    is ImageSource.Path -> {
      CIImage.imageWithContentsOfURL(NSURL.fileURLWithPath(path))
    }
    is ImageSource.Uri -> {
      NSURL.URLWithString(uri)?.let { CIImage.imageWithContentsOfURL(it) }
    }
    is ImageSource.Bytes -> {
      if (bytes.isEmpty()) {
        null
      } else {
        bytes.usePinned { pinned ->
          CIImage.imageWithData(NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong()))
        }
      }
    }
  }

/**
 * The image's own pixel size.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.pixelSize(): Size =
  extent.useContents {
    Size(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
  }

/**
 * Draws [text] into an image sized to the glyphs it actually covers.
 *
 * Laid out in two passes. The first wraps at the authored maximum width and reports the width the
 * text really used, the second re-lays it in a box tight to that, so an alignment is measured
 * against the text itself, not a width it never reached.
 *
 * @param frame The frame the text is drawn on, which every fraction in [style] is measured against.
 * @return The drawn text, or null when the style leaves nothing to draw.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("LongMethod")
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
): CIImage? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null

  val font = fontFor(style, style.fontSize * frame.height) ?: return null
  val string = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
  if (string == null) {
    CFRelease(font)
    return null
  }

  val paragraph = paragraphStyle(style.alignment)
  val attributes = attributesOf(font, paragraph)
  val attributed = attributes?.let { CFAttributedStringCreate(null, string, it) }
  val framesetter = attributed?.let { CTFramesetterCreateWithAttributedString(it) }

  val image =
    framesetter?.let { setter ->
      val whole =
        cValue<CFRange> {
          location = 0
          length = 0
        }
      val wrapWidth = (style.maxWidth * frame.width).toDouble().coerceAtLeast(1.0)
      val wrapped =
        CTFramesetterSuggestFrameSizeWithConstraints(
          framesetter = setter,
          stringRange = whole,
          frameAttributes = null,
          constraints = CGSizeMake(wrapWidth, UNBOUNDED),
          fitRange = null,
        ).useContents { width }

      val block =
        CTFramesetterSuggestFrameSizeWithConstraints(
          framesetter = setter,
          stringRange = whole,
          frameAttributes = null,
          constraints = CGSizeMake(ceil(wrapped).coerceAtLeast(1.0), UNBOUNDED),
          fitRange = null,
        ).useContents { Size(ceil(width).toInt(), ceil(height).toInt()) }

      if (block.width <= 0 || block.height <= 0) {
        null
      } else {
        draw(setter, whole, block, style, CTFontGetCapHeight(font))
      }
    }

  framesetter?.let(::CFRelease)
  attributed?.let(::CFRelease)
  attributes?.let(::CFRelease)
  paragraph?.let(::CFRelease)
  CFRelease(string)
  CFRelease(font)
  return image
}

/**
 * Renders the laid-out text into a bitmap and wraps it as a Core Image image.
 *
 * A bitmap context is already bottom-left with +Y up, which is the space CoreText draws in and the
 * space Core Image measures in, so nothing is flipped on the way through.
 */
@OptIn(ExperimentalForeignApi::class)
private fun draw(
  framesetter: CTFramesetterRef,
  range: CValue<CFRange>,
  block: Size,
  style: TextStyle,
  capHeight: Double,
): CIImage? {
  // A plate that stops at the glyphs reads as a mistake, so it carries a margin proportional to the
  // text, not a fixed number of pixels.
  val padding = if (style.backgroundColor == null) 0 else (capHeight * PLATE_PADDING).roundToInt()
  val width = block.width + 2 * padding
  val height = block.height + 2 * padding

  val space = CGColorSpaceCreateDeviceRGB() ?: return null
  val context =
    CGBitmapContextCreate(
      data = null,
      width = width.toULong(),
      height = height.toULong(),
      bitsPerComponent = BITS_PER_COMPONENT,
      bytesPerRow = 0u,
      space = space,
      bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder32Big,
    )
  CGColorSpaceRelease(space)
  if (context == null) return null

  style.backgroundColor?.let { plate ->
    context.fillWith(plate)
    CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
  }
  context.fillWith(style.color)
  CGContextSetTextMatrix(context, CGAffineTransformIdentity.readValue())

  val path =
    CGPathCreateWithRect(
      CGRectMake(padding.toDouble(), padding.toDouble(), block.width.toDouble(), block.height.toDouble()),
      null,
    )
  val frame = CTFramesetterCreateFrame(framesetter, range, path, null)
  if (frame != null) {
    CTFrameDraw(frame, context)
    CFRelease(frame)
  }
  path?.let(::CGPathRelease)

  val bitmap = CGBitmapContextCreateImage(context)
  CGContextRelease(context)
  if (bitmap == null) return null

  val image = CIImage.imageWithCGImage(bitmap)
  CGImageRelease(bitmap)
  return image
}

/**
 * The font [style] asks for, at the size its capitals stand [target] pixels tall.
 *
 * A style names a cap height, not an em size, so the same number reads as the same visual
 * weight whatever typeface it lands on. Cap height is measured on a probe-sized face and scaled
 * from there, since it is a ratio of the point size.
 */
@OptIn(ExperimentalForeignApi::class)
private fun fontFor(
  style: TextStyle,
  target: Float,
): CTFontRef? {
  val probe = named(style.resolvedFontFamily, CAP_PROBE_SIZE) ?: return null
  val cap = CTFontGetCapHeight(probe)
  val size = if (cap > 0.0) CAP_PROBE_SIZE * target / cap else target.toDouble()
  CFRelease(probe)

  val plain = named(style.resolvedFontFamily, size) ?: return null
  // Named weights below bold need a descriptor match, not a trait, and a family that has no
  // medium answers with its regular anyway, so the closer of the two faces is the regular one.
  if (style.weight != FontWeight.Bold) return plain

  val bold =
    CTFontCreateCopyWithSymbolicTraits(
      font = plain,
      size = size,
      matrix = null,
      symTraitValue = kCTFontTraitBold,
      symTraitMask = kCTFontTraitBold,
    )
  if (bold == null) return plain

  CFRelease(plain)
  return bold
}

@OptIn(ExperimentalForeignApi::class)
private fun named(
  family: String?,
  size: Double,
): CTFontRef? {
  if (family == null) return CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, size, null)
  val name = CFStringCreateWithCString(null, family, kCFStringEncodingUTF8)
  val font = CTFontCreateWithName(name, size, null)
  name?.let(::CFRelease)
  return font ?: CTFontCreateUIFontForLanguage(kCTFontUIFontSystem, size, null)
}

/**
 * The paragraph style carrying the alignment, or null when it could not be built.
 */
@OptIn(ExperimentalForeignApi::class)
private fun paragraphStyle(alignment: TextAlignment): CTParagraphStyleRef? =
  memScoped {
    val value = alloc<CTTextAlignmentVar>()
    value.value = alignment.toCoreText()
    val setting =
      alloc<CTParagraphStyleSetting>().apply {
        spec = kCTParagraphStyleSpecifierAlignment
        valueSize = sizeOf<CTTextAlignmentVar>().toULong()
        this.value = value.ptr
      }
    CTParagraphStyleCreate(setting.ptr, 1u)
  }

/**
 * The attribute dictionary CoreText lays the string out with.
 *
 * Built through `CFDictionaryCreate`. A Kotlin map will not do, since the attribute names are
 * `CFStringRef` constants and those do not bridge into a dictionary as keys. What comes back is a dictionary with
 * no recognised keys in it, and CoreText silently lays out with its defaults.
 */
@OptIn(ExperimentalForeignApi::class)
private fun attributesOf(
  font: CTFontRef,
  paragraph: CTParagraphStyleRef?,
): CFDictionaryRef? =
  memScoped {
    val count = if (paragraph == null) 1 else 2
    val keys = allocArray<COpaquePointerVar>(count)
    val values = allocArray<COpaquePointerVar>(count)
    keys[0] = kCTFontAttributeName
    values[0] = font
    if (paragraph != null) {
      keys[1] = kCTParagraphStyleAttributeName
      values[1] = paragraph
    }

    CFDictionaryCreate(
      allocator = null,
      keys = keys,
      values = values,
      numValues = count.toLong(),
      keyCallBacks = kCFTypeDictionaryKeyCallBacks.ptr,
      valueCallBacks = kCFTypeDictionaryValueCallBacks.ptr,
    )
  }

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<*>.fillWith(argb: Int) {
  CGContextSetRGBFillColor(
    c = this.reinterpret(),
    red = ((argb shr 16) and BYTE) / FULL,
    green = ((argb shr 8) and BYTE) / FULL,
    blue = (argb and BYTE) / FULL,
    alpha = ((argb shr 24) and BYTE) / FULL,
  )
}

private fun TextAlignment.toCoreText(): CTTextAlignment =
  when (this) {
    TextAlignment.Start -> kCTTextAlignmentLeft
    TextAlignment.Center -> kCTTextAlignmentCenter
    TextAlignment.End -> kCTTextAlignmentRight
  }

private const val CAP_PROBE_SIZE = 100.0
private const val PLATE_PADDING = 0.3
private const val BITS_PER_COMPONENT = 8uL
private const val BYTE = 0xFF
private const val FULL = 255.0

// CoreText treats a height this large as "as tall as it needs", and CGFloat.MAX overflows the
// layout arithmetic, so it never reads as unbounded.
private const val UNBOUNDED = 1_000_000.0
