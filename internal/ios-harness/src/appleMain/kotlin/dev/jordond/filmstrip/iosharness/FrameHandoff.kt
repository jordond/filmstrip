package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.usePinned
import platform.CoreGraphics.CGBitmapContextCreate
import platform.CoreGraphics.CGColorSpaceCreateDeviceRGB
import platform.CoreGraphics.CGColorSpaceRelease
import platform.CoreGraphics.CGContextDrawImage
import platform.CoreGraphics.CGContextRelease
import platform.CoreGraphics.CGImageAlphaInfo
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRef
import platform.CoreGraphics.CGImageRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIImage
import platform.Foundation.NSData
import platform.Foundation.create

/**
 * Candidate shapes for `FrameResult` and `PlatformImage`, written to be compiled into the framework
 * and read back out of the generated Objective-C header.
 *
 * The shape under consideration is a `CGImage` behind an opaque `PlatformImage` handle plus a
 * `toBytes()` escape. A `CGImage` is already reachable from Kotlin/Native
 * ([IosStillRenderer.renderToCGImage]). What decides the public API is the other half: what a
 * Swift caller can do with one, and what the copy escape costs.
 *
 * So every plausible accessor is declared here at once, and the header says which survive export.
 * A `CGImageRef` is `CPointer<CGImage>?`, a cinterop type rather than an Objective-C object, and
 * whether Kotlin/Native exports one at all is the whole question. `CIImage` is a real Objective-C
 * class and is the control: if it appears in the header and the pointer does not, the shape has to
 * be built on the class.
 */
@OptIn(ExperimentalForeignApi::class)
public class HandoffImage internal constructor(
  private var image: CGImageRef?,
  private val ci: CIImage?,
) {
  public val widthPx: Int get() = image?.let { CGImageGetWidth(it).toInt() } ?: 0

  public val heightPx: Int get() = image?.let { CGImageGetHeight(it).toInt() } ?: 0

  /**
   * Candidate A: hand the raw `CGImageRef` across. Zero copy, if the pointer exports at all.
   */
  public fun cgImage(): CGImageRef? = image

  /**
   * Candidate B: hand a `CIImage` across. A real Objective-C class, so this is the control arm.
   */
  public fun ciImage(): CIImage? = ci

  /**
   * Candidate C: the copy escape. Tightly packed RGBA_8888, row-major, no padding.
   *
   * Measured rather than assumed, because the copy is what decides whether the shape is usable.
   */
  public fun toRgba8888(): ByteArray {
    val source = image ?: return ByteArray(0)
    val width = CGImageGetWidth(source).toInt()
    val height = CGImageGetHeight(source).toInt()
    val bytes = ByteArray(width * height * BYTES_PER_PIXEL)

    val colorSpace = CGColorSpaceCreateDeviceRGB()
    bytes.usePinned { pinned ->
      val context =
        CGBitmapContextCreate(
          data = pinned.addressOf(0),
          width = width.toULong(),
          height = height.toULong(),
          bitsPerComponent = 8uL,
          bytesPerRow = (width * BYTES_PER_PIXEL).toULong(),
          space = colorSpace,
          bitmapInfo = CGImageAlphaInfo.kCGImageAlphaPremultipliedLast.value,
        )
      CGContextDrawImage(
        context,
        CGRectMake(0.0, 0.0, width.toDouble(), height.toDouble()),
        source,
      )
      CGContextRelease(context)
    }
    CGColorSpaceRelease(colorSpace)
    return bytes
  }

  /**
   * Candidate D: the same bytes as an `NSData`.
   *
   * Exists because candidate C's `ByteArray` reaches Swift as a `KotlinByteArray`, whose only bulk
   * accessor is `get(index:)`, one bridged call per byte. `NSData` is a Foundation class that
   * Kotlin/Native exports natively and Swift sees as `Data`, so if the per-byte cost of C is real
   * this is the escape that has to ship on the Apple side.
   */
  public fun toNSData(): NSData {
    val bytes = toRgba8888()
    return bytes.usePinned { pinned ->
      NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
  }

  /**
   * Releases the underlying `CGImage`.
   *
   * `queueInputBitmap` takes ownership and recycles on Android. A `CGImage` from
   * `CIContext.createCGImage` is a `+1` reference the caller must release. Both platforms make the
   * lifetime the caller's problem, so the type says so rather than pretending it is garbage.
   */
  public fun close() {
    image?.let(::CGImageRelease)
    image = null
  }

  private companion object {
    const val BYTES_PER_PIXEL = 4
  }
}

/**
 * The sealed-result half of the candidate, to check how the arms land in the header.
 */
public sealed interface HandoffResult {
  public class Success(
    public val image: HandoffImage,
    public val presentationTimeMillis: Long,
  ) : HandoffResult

  public class Failure(
    public val message: String,
  ) : HandoffResult
}

/**
 * Prices the Objective-C bridge itself, with no rendering in the way.
 *
 * The two escapes hand Swift the same bytes by different routes: candidate C as a
 * `KotlinByteArray`, whose only accessor is a bridged `get(index:)` per element, and candidate D as
 * an `NSData` that Swift sees as `Data`. Whether that distinction matters is a throughput question,
 * so it is measured rather than argued.
 */
@OptIn(ExperimentalForeignApi::class)
public object BridgeProbe {
  public fun byteArray(sizeBytes: Int): ByteArray = ByteArray(sizeBytes) { (it and 0x7F).toByte() }

  public fun nsData(sizeBytes: Int): NSData {
    val bytes = byteArray(sizeBytes)
    return bytes.usePinned { pinned ->
      NSData.create(bytes = pinned.addressOf(0), length = bytes.size.toULong())
    }
  }
}

/**
 * Whether `kotlin.time.Duration` survives Objective-C export.
 *
 * It is a `value class`, and rule 4 of the Swift-implementability checklist bans those from public
 * signatures because they are not properly exposed in framework headers. Whether that means
 * *mangled*, *erased to a primitive*, or *omitted outright* decides how filmstrip spells media time
 * in every public type it has, so it is read out of the header rather than assumed.
 */
public class DurationExportProbe {
  public var duration: kotlin.time.Duration = kotlin.time.Duration.ZERO

  public fun takesDuration(value: kotlin.time.Duration): Long = value.inWholeMilliseconds

  public fun returnsDuration(): kotlin.time.Duration = duration

  public fun takesNullableDuration(value: kotlin.time.Duration?): Boolean = value != null

  public fun takesMillis(value: Long): Long = value
}

/**
 * Whether a Kotlin-only convenience can be kept off the Objective-C surface entirely.
 *
 * If it can, media time is `Long` milliseconds in every exported signature and `Duration` survives
 * as a Kotlin ergonomic, rather than being lost from both.
 */
@OptIn(kotlin.experimental.ExperimentalObjCRefinement::class)
public class HiddenProbe {
  public val visibleMillis: Long = 5_000L

  @kotlin.native.HiddenFromObjC
  public val hiddenDuration: kotlin.time.Duration
    get() = kotlin.time.Duration.ZERO

  @kotlin.native.HiddenFromObjC
  public fun hiddenTakesDuration(value: kotlin.time.Duration): Long = value.inWholeMilliseconds
}
