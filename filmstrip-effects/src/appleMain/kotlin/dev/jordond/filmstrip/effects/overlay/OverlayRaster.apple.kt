package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.style.TextStyle
import kotlinx.cinterop.COpaquePointerVar
import kotlinx.cinterop.CPointer
import kotlinx.cinterop.CValue
import kotlinx.cinterop.ExperimentalForeignApi
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
import platform.CoreGraphics.CGColorCreate
import platform.CoreGraphics.CGColorRef
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGContextSetTextMatrix
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGPathCreateWithRect
import platform.CoreGraphics.CGPathRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.CGSizeMake
import platform.CoreGraphics.kCGBitmapByteOrder16Little
import platform.CoreImage.CIImage
import platform.CoreText.CTFontCreateCopyWithSymbolicTraits
import platform.CoreText.CTFontCreateUIFontForLanguage
import platform.CoreText.CTFontCreateWithName
import platform.CoreText.CTFontGetAscent
import platform.CoreText.CTFontGetCapHeight
import platform.CoreText.CTFontGetDescent
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
import platform.CoreText.kCTForegroundColorAttributeName
import platform.CoreText.kCTParagraphStyleAttributeName
import platform.CoreText.kCTParagraphStyleSpecifierAlignment
import platform.CoreText.kCTTextAlignmentCenter
import platform.CoreText.kCTTextAlignmentLeft
import platform.CoreText.kCTTextAlignmentRight
import kotlin.math.ceil
import kotlin.math.roundToInt

/**
 * The image's own pixel size.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.pixelSize(): Size =
  extent.useContents {
    Size(size.width.roundToInt().coerceAtLeast(1), size.height.roundToInt().coerceAtLeast(1))
  }

/**
 * Draws [text] into an image sized to the glyphs it actually covers, laid out by [laidOutText].
 *
 * The raster is counted by [textRasterFits] before it is allocated, at [TEXT_TEXEL_BYTES] a texel with the band
 * [drawnBandBytes] gives, and one past [ceiling] is refused with the reason [textTooLargeToDraw] gives. One that fits
 * but that Core Graphics cannot make is refused with the reason [textAllocationFailed] gives. Text in a face whose
 * line stands taller than [CORE_TEXT_TALLEST_LINE], which Core Text measures as nothing at all, is refused with the
 * reason [textTooTallToLayOut] gives.
 *
 * @param frame The frame the text is drawn on, which every fraction in [style] is measured against.
 * @param ceiling The most bytes the raster may hold at its peak, [PICTURE_PEAK_CEILING] unless a test sets it.
 * @param bitmapOf Makes the bitmap context a raster of the given width and height is drawn into, or null when it
 * cannot, which refuses the raster.
 * @return The drawn text, or why there is none.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun rasterizeText(
  text: String,
  style: TextStyle,
  frame: Size,
  ceiling: Long = PICTURE_PEAK_CEILING,
  bitmapOf: (width: Int, height: Int) -> CGContextRef? = ::textBitmap,
): TextRaster<OverlayPixels> =
  laidOutText(text, style, frame) { framesetter, range, block, line ->
    if (line > CORE_TEXT_TALLEST_LINE) {
      TextRaster.Refused(textTooTallToLayOut(ceil(line).toLong()))
    } else if (block.width <= 0 || block.height <= 0) {
      TextRaster.Empty
    } else {
      draw(framesetter, range, block, style, style.platePaddingOn(frame), ceiling, bitmapOf)
    }
  } ?: TextRaster.Empty

/**
 * Lays [text] out in [style] on [frame] and hands [use] the framesetter, the range it covers, the block it measures in
 * whole pixels and how tall one line of its face stands from ascent to descent. Everything made here is released once
 * [use] returns. It gives null when the text, the frame or its face leaves nothing to lay out.
 *
 * Laid out in two passes. The first wraps at the authored maximum width and reports the width the text really used,
 * the second re-lays it in a box tight to that, so an alignment is measured against the text itself, not a width it
 * never reached. Both are as tall as [UNBOUNDED].
 */
@OptIn(ExperimentalForeignApi::class)
internal fun <T> laidOutText(
  text: String,
  style: TextStyle,
  frame: Size,
  use: (framesetter: CTFramesetterRef, range: CValue<CFRange>, block: Size, line: Double) -> T,
): T? {
  if (text.isEmpty() || frame.width <= 0 || frame.height <= 0) return null

  val font = fontFor(style, style.capHeightOn(frame)) ?: return null
  val string = CFStringCreateWithCString(null, text, kCFStringEncodingUTF8)
  if (string == null) {
    CFRelease(font)
    return null
  }

  val paragraph = paragraphStyle(style.alignment)
  val attributes = attributesOf(font, paragraph, style.color)
  val attributed = attributes?.let { CFAttributedStringCreate(null, string, it) }
  val framesetter = attributed?.let { CTFramesetterCreateWithAttributedString(it) }

  val laidOut =
    framesetter?.let { setter ->
      val whole =
        cValue<CFRange> {
          location = 0
          length = 0
        }
      val wrapWidth = style.wrapWidthOn(frame).toDouble()
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

      use(setter, whole, block, CTFontGetAscent(font) + CTFontGetDescent(font))
    }

  framesetter?.let(::CFRelease)
  attributed?.let(::CFRelease)
  attributes?.let(::CFRelease)
  paragraph?.let(::CFRelease)
  CFRelease(string)
  CFRelease(font)
  return laidOut
}

/**
 * Renders the laid-out text into a bitmap and wraps it for Core Image as bare codes, or refuses it before [bitmapOf]
 * runs when the raster does not fit under [ceiling]. A bitmap [bitmapOf] cannot make, or one Core Graphics cannot copy
 * into an image, is refused with the reason [textAllocationFailed] gives.
 *
 * The bitmap holds sixteen bits a component, premultiplied and little endian, so a translucent texel keeps its
 * straight colour to within one sixteen-bit step over its alpha.
 *
 * A bitmap context is already bottom-left with +Y up, which is the space CoreText draws in and the
 * space Core Image measures in, so nothing is flipped on the way through.
 */
@OptIn(ExperimentalForeignApi::class)
@Suppress("LongParameterList")
private fun draw(
  framesetter: CTFramesetterRef,
  range: CValue<CFRange>,
  block: Size,
  style: TextStyle,
  padding: Int,
  ceiling: Long,
  bitmapOf: (width: Int, height: Int) -> CGContextRef?,
): TextRaster.Made<OverlayPixels> {
  val wide = plateSide(block.width.toLong(), padding)
  val tall = plateSide(block.height.toLong(), padding)
  if (!textRasterFits(wide, tall, TEXT_TEXEL_BYTES, drawnBandBytes(wide, tall), ceiling)) {
    return TextRaster.Refused(textTooLargeToDraw(wide, tall, TEXT_TEXEL_BYTES, ceiling))
  }
  val width = wide.toInt()
  val height = tall.toInt()
  val unallocated = TextRaster.Refused(textAllocationFailed(width, height))

  val context = bitmapOf(width, height) ?: return unallocated

  style.backgroundColor?.let { plate ->
    context.fillWith(plate)
    CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
  }
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
  if (bitmap == null) return unallocated

  val pixels = OverlayPixels(bitmap.heldCodes(), ceiling = ceiling)
  CGImageRelease(bitmap)
  return TextRaster.Drawn(pixels)
}

/**
 * A [width] by [height] bitmap context of [TEXT_TEXEL_BYTES] a texel, premultiplied and little endian, with rows
 * exactly as wide as their texels. It is null when Core Graphics cannot make one.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun textBitmap(
  width: Int,
  height: Int,
): CGContextRef? {
  val space = CGColorSpaceCreateDeviceRGB() ?: return null
  val context =
    CGBitmapContextCreate(
      data = null,
      width = width.toULong(),
      height = height.toULong(),
      bitsPerComponent = BITS_PER_COMPONENT,
      bytesPerRow = width.toULong() * TEXT_TEXEL_BYTES.toULong(),
      space = space,
      bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value or kCGBitmapByteOrder16Little,
    )
  CGColorSpaceRelease(space)
  return context
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
  val probe = named(style.resolvedFontFamily, CAP_PROBE_SIZE.toDouble()) ?: return null
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
 *
 * [color] is the foreground colour every glyph run is drawn in.
 */
@OptIn(ExperimentalForeignApi::class)
private fun attributesOf(
  font: CTFontRef,
  paragraph: CTParagraphStyleRef?,
  color: Int,
): CFDictionaryRef? {
  val ink = cgColorOf(color) ?: return null
  val attributes =
    memScoped {
      val count = if (paragraph == null) 2 else 3
      val keys = allocArray<COpaquePointerVar>(count)
      val values = allocArray<COpaquePointerVar>(count)
      keys[0] = kCTFontAttributeName
      values[0] = font
      keys[1] = kCTForegroundColorAttributeName
      values[1] = ink
      if (paragraph != null) {
        keys[2] = kCTParagraphStyleAttributeName
        values[2] = paragraph
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

  // The dictionary retains what it holds, so this reference has done its job.
  CGColorRelease(ink)
  return attributes
}

/**
 * The colour [argb] packs, in the same space the bitmap is drawn in so nothing is converted on the way.
 */
@OptIn(ExperimentalForeignApi::class)
private fun cgColorOf(argb: Int): CGColorRef? =
  memScoped {
    val space = CGColorSpaceCreateDeviceRGB() ?: return@memScoped null
    val components = allocArray<CGFloatVar>(RGBA)
    components[0] = ((argb shr 16) and BYTE) / FULL
    components[1] = ((argb shr 8) and BYTE) / FULL
    components[2] = (argb and BYTE) / FULL
    components[3] = ((argb shr 24) and BYTE) / FULL
    val color = CGColorCreate(space, components)
    CGColorSpaceRelease(space)
    color
  }

@OptIn(ExperimentalForeignApi::class)
private fun CPointer<*>.fillWith(argb: Int) {
  val color = cgColorOf(argb) ?: return
  CGContextSetFillColorWithColor(this.reinterpret(), color)
  CGColorRelease(color)
}

private fun TextAlignment.toCoreText(): CTTextAlignment =
  when (this) {
    TextAlignment.Start -> kCTTextAlignmentLeft
    TextAlignment.Center -> kCTTextAlignmentCenter
    TextAlignment.End -> kCTTextAlignmentRight
  }

private const val BITS_PER_COMPONENT = 16uL
private const val RGBA = 4
private const val BYTE = 0xFF
private const val FULL = 255.0

// The height Core Text is asked to lay a block out in when the text should be as tall as it needs. CGFloat.MAX
// overflows Core Text's layout arithmetic, so it never reads as unbounded. A block this tall is past every raster
// ceiling, so text Core Text would cut off at it is refused by its size before it draws.
private const val UNBOUNDED = 1_000_000_000.0

/**
 * The tallest line, in pixels, Core Text lays out. A block holding a line any taller measures as nothing at all.
 *
 * This is Core Text's limit, not filmstrip's, measured on macOS and the iOS simulator with any height to lay the
 * block out in.
 */
internal const val CORE_TEXT_TALLEST_LINE: Double = 10_000_000.0

/**
 * The reason text is refused when one [line] of its face, in pixels, stands taller than [CORE_TEXT_TALLEST_LINE].
 *
 * It names Core Text, since the limit is Core Text's own and not a ceiling of filmstrip's.
 */
internal fun textTooTallToLayOut(line: Long): String =
  "The text is too large to draw. A line of it stands $line pixels tall, and Core Text lays out no line taller " +
    "than ${CORE_TEXT_TALLEST_LINE.toLong()} pixels."
