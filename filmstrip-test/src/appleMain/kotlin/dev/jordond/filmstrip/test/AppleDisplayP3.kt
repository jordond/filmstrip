package dev.jordond.filmstrip.test

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.color.PictureColor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.pictureColorFromDecodedProfile
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.allocArray
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.readBytes
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import platform.CoreFoundation.CFDataCreateMutable
import platform.CoreFoundation.CFDataGetBytePtr
import platform.CoreFoundation.CFDataGetLength
import platform.CoreFoundation.CFDictionaryRef
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFRetain
import platform.CoreFoundation.CFStringCreateWithCString
import platform.CoreFoundation.kCFStringEncodingUTF8
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorCreate
import platform.CoreGraphics.CGColorRelease
import platform.CoreGraphics.CGColorSpaceCopyICCData
import platform.CoreGraphics.CGColorSpaceCreateWithName
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetFillColorWithColor
import platform.CoreGraphics.CGFloatVar
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreGraphics.kCGColorSpaceDisplayP3
import platform.Foundation.CFBridgingRelease
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSMutableDictionary
import platform.Foundation.NSNumber
import platform.Foundation.numberWithDouble
import platform.Foundation.setValue
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithData
import platform.ImageIO.CGImageDestinationFinalize
import platform.ImageIO.kCGImageDestinationLossyCompressionQuality

/**
 * Display P3 as Core Graphics describes it, and pictures ImageIO writes in it.
 *
 * [color] is the description a Display P3 picture lands through, and [picture] paints one flat color in Display P3
 * and has ImageIO encode it, so a test can hold a backend's draw of a real HEIC or JPEG to the shared light.
 */
@InternalFilmstripApi
@OptIn(ExperimentalForeignApi::class)
public object AppleDisplayP3 {
  /**
   * The color description of Core Graphics' Display P3 space, read from the ICC profile it gives that space.
   */
  public fun color(): PictureColor {
    val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceDisplayP3))
    val icc = checkNotNull(CGColorSpaceCopyICCData(space)) { "Display P3 has no ICC data" }
    CGColorSpaceRelease(space)
    val profile = checkNotNull(CFDataGetBytePtr(icc)).readBytes(CFDataGetLength(icc).toInt())
    CFRelease(icc)
    return checkNotNull(pictureColorFromDecodedProfile(profile, RGB)) { "Display P3's profile is not RGB" }
  }

  /**
   * A picture of [size] painted the opaque Display P3 color [texel] and written by ImageIO as [type] at full
   * quality, or null when ImageIO cannot write that type on this platform.
   *
   * @param type The uniform type identifier ImageIO writes, such as `public.heic` or `public.jpeg`.
   * @param texel A packed RGB color, its alpha ignored.
   */
  public fun picture(
    type: String,
    size: Size,
    texel: Int,
  ): ByteArray? {
    val width = size.width.toULong()
    val height = size.height.toULong()
    val space = checkNotNull(CGColorSpaceCreateWithName(kCGColorSpaceDisplayP3))
    val context =
      checkNotNull(
        CGBitmapContextCreate(null, width, height, 8u, 0u, space, CGImageAlphaInfo.kCGImageAlphaNoneSkipLast.value),
      )
    val color =
      memScoped {
        val components = allocArray<CGFloatVar>(RGB + 1)
        AuthoredColors.channelsOf(texel).forEachIndexed { index, code -> components[index] = code / FULL }
        components[RGB] = 1.0
        CGColorCreate(space, components)
      }
    CGContextSetFillColorWithColor(context, color)
    CGContextFillRect(context, CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()))
    val image = checkNotNull(CGBitmapContextCreateImage(context))
    CGColorRelease(color)
    CGContextRelease(context)
    CGColorSpaceRelease(space)

    val data = checkNotNull(CFDataCreateMutable(null, 0))
    val name = CFStringCreateWithCString(null, type, kCFStringEncodingUTF8)
    val destination = CGImageDestinationCreateWithData(data, name, 1u, null)
    name?.let(::CFRelease)
    if (destination == null) {
      CGImageRelease(image)
      CFRelease(data)
      return null
    }

    // A real dictionary keyed by a string, since a bridged Kotlin map can reach ImageIO empty on iOS.
    val options = NSMutableDictionary()
    kCGImageDestinationLossyCompressionQuality?.let { key ->
      options.setValue(NSNumber.numberWithDouble(1.0), forKey = CFBridgingRelease(CFRetain(key)) as String)
    }
    val properties: CFDictionaryRef? = CFBridgingRetain(options)?.reinterpret()
    CGImageDestinationAddImage(destination, image, properties)
    val written = CGImageDestinationFinalize(destination)
    properties?.let(::CFRelease)
    CFRelease(destination)
    CGImageRelease(image)

    val bytes = if (written) CFDataGetBytePtr(data)?.readBytes(CFDataGetLength(data).toInt()) else null
    CFRelease(data)
    return bytes
  }

  private const val RGB = 3
  private const val FULL = 255.0
}
