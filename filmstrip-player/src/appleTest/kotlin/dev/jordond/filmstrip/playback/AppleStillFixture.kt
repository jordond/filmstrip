package dev.jordond.filmstrip.playback

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.geometry.KenBurns
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.motion.Easing
import dev.jordond.filmstrip.test.TestFrame
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.reinterpret
import platform.CoreFoundation.CFRelease
import platform.CoreFoundation.CFStringRef
import platform.CoreFoundation.CFURLRef
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGBitmapContextCreateImage
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextFillRect
import platform.CoreGraphics.CGContextRef
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGContextSetRGBFillColor
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.Foundation.CFBridgingRetain
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import platform.ImageIO.CGImageDestinationAddImage
import platform.ImageIO.CGImageDestinationCreateWithURL
import platform.ImageIO.CGImageDestinationFinalize
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The fixture clip followed by a flat photo held for [PHOTO_LENGTH].
 *
 * A still takes its slot on the timeline from a generated segment rather than from a track of its
 * own, so a strip frame inside that slot is the one worth pinning to the export.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun applePhotoComposition(): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(MediaSource.Image(ImageSource.of(applePhotoFile()), PHOTO_LENGTH)),
          ),
        ),
      ),
  )

/**
 * The fixture clip followed by a patterned photo that a pan travels across.
 *
 * The photo is red on one side of [PHOTO_BOUNDARY] and blue on the other, so two readings inside
 * the span are two different pictures rather than the same flat sheet twice.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun applePannedPhotoComposition(): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(MediaSource.of(appleFixtureClip()), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(
              MediaSource.Image(ImageSource.of(appleSplitPhotoFile()), PHOTO_LENGTH),
              effects = listOf(PHOTO_PAN),
            ),
          ),
        ),
      ),
  )

/**
 * Where the split photo's red half gives way to its blue one.
 */
internal const val PHOTO_BOUNDARY: Float = 0.5f

/**
 * The pan the split photo travels under, from a window in the red half to one in the blue.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal val PHOTO_PAN: KenBurns =
  KenBurns(
    from = NormalizedRect(0f, 0f, 0.4f, 1f),
    to = NormalizedRect(0.6f, 0f, 1f, 1f),
    easing = Easing.Linear,
  )

/**
 * Two readings inside the photo's span, either side of the halfway point every curve agrees on.
 */
internal val PAN_FRACTIONS: List<Double> = listOf(0.4, 0.6)

/**
 * A photo split into a red half and a blue half, written into the temporary directory once.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun appleSplitPhotoFile(): String =
  photoWritten("filmstrip-player-split-photo.png") { context ->
    CGContextSetRGBFillColor(c = context, red = 1.0, green = 0.0, blue = 0.0, alpha = 1.0)
    CGContextFillRect(
      context,
      CGRectMake(0.0, 0.0, FIXTURE_FRAME.width * PHOTO_BOUNDARY.toDouble(), FIXTURE_FRAME.height.toDouble()),
    )
    CGContextSetRGBFillColor(c = context, red = 0.0, green = 0.0, blue = 1.0, alpha = 1.0)
    CGContextFillRect(
      context,
      CGRectMake(
        FIXTURE_FRAME.width * PHOTO_BOUNDARY.toDouble(),
        0.0,
        FIXTURE_FRAME.width * (1.0 - PHOTO_BOUNDARY),
        FIXTURE_FRAME.height.toDouble(),
      ),
    )
  }

/**
 * A flat photo the shape of the fixture's own frame, written into the temporary directory once.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun applePhotoFile(): String =
  photoWritten("filmstrip-player-photo.png") { context ->
    CGContextSetRGBFillColor(
      c = context,
      red = PHOTO_COLOR.first / FULL,
      green = PHOTO_COLOR.second / FULL,
      blue = PHOTO_COLOR.third / FULL,
      alpha = 1.0,
    )
    CGContextFillRect(
      context,
      CGRectMake(0.0, 0.0, FIXTURE_FRAME.width.toDouble(), FIXTURE_FRAME.height.toDouble()),
    )
  }

/**
 * Draws a photo the shape of the fixture's frame with [paint] and writes it to [name] once.
 *
 * @return The path it landed at.
 */
@OptIn(ExperimentalForeignApi::class)
private fun photoWritten(
  name: String,
  paint: (CGContextRef) -> Unit,
): String {
  val path = NSTemporaryDirectory() + name
  if (NSFileManager.defaultManager.fileExistsAtPath(path)) return path

  val space = CGColorSpaceCreateDeviceRGB() ?: fail("Core Graphics refused a device RGB colour space")
  val context =
    CGBitmapContextCreate(
      data = null,
      width = FIXTURE_FRAME.width.toULong(),
      height = FIXTURE_FRAME.height.toULong(),
      bitsPerComponent = BITS_PER_COMPONENT,
      bytesPerRow = 0uL,
      space = space,
      bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
    ) ?: fail("Core Graphics refused a bitmap context for the photo fixture")

  paint(context)

  val image = CGBitmapContextCreateImage(context)
  CGContextRelease(context)
  CGColorSpaceRelease(space)
  if (image == null) fail("Core Graphics drew no image for the photo fixture")

  val url: CFURLRef = CFBridgingRetain(NSURL.fileURLWithPath(path))?.reinterpret() ?: fail("no URL for $path")
  val uti: CFStringRef = CFBridgingRetain(PNG_UTI)?.reinterpret() ?: fail("no type identifier for $PNG_UTI")

  try {
    val destination =
      CGImageDestinationCreateWithURL(url, uti, 1u, null) ?: fail("ImageIO would not write a PNG to $path")
    try {
      CGImageDestinationAddImage(destination, image, null)
      if (!CGImageDestinationFinalize(destination)) fail("ImageIO would not finalize $path")
    } finally {
      CFRelease(destination)
    }
  } finally {
    CGImageRelease(image)
    CFRelease(uti)
    CFRelease(url)
  }

  return path
}

/**
 * How long the photo is held, and a whole number of frames at the fixture's rate.
 */
internal val PHOTO_LENGTH: Duration = 1000.milliseconds

/**
 * Where inside the photo's span a frame is asked for.
 *
 * Neither end of it, and not its halfway point either, which a span laid an interval out could
 * still land on.
 */
internal const val PHOTO_FRACTION: Double = 0.4

/**
 * The colour the photo is filled with, which nothing the fixture's own pattern draws comes near.
 */
internal val PHOTO_COLOR: Triple<Int, Int, Int> = Triple(0x11, 0xC2, 0xAA)

/**
 * The colour at the middle of this frame.
 */
internal fun TestFrame.centre(): Triple<Int, Int, Int> {
  val offset = ((size.height / 2) * size.width + size.width / 2) * CHANNELS
  return Triple(
    pixels[offset].toInt() and BYTE_MASK,
    pixels[offset + 1].toInt() and BYTE_MASK,
    pixels[offset + 2].toInt() and BYTE_MASK,
  )
}

/**
 * Asserts two colours are within the slack a real render leaves even on a flat patch.
 */
internal infix fun Triple<Int, Int, Int>.shouldBeCloseTo(expected: Triple<Int, Int, Int>) {
  val distance = abs(first - expected.first) + abs(second - expected.second) + abs(third - expected.third)
  assertTrue(distance <= COLOR_TOLERANCE, "expected a colour near $expected, got $this")
}

private const val CHANNELS = 4
private const val BYTE_MASK = 0xFF
private const val COLOR_TOLERANCE = 24

private const val PNG_UTI = "public.png"
private const val BITS_PER_COMPONENT = 8uL
private const val FULL = 255.0
