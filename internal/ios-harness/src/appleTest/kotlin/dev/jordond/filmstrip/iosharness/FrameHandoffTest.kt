package dev.jordond.filmstrip.iosharness

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.set
import kotlinx.cinterop.value
import platform.CoreGraphics.CGImageGetHeight
import platform.CoreGraphics.CGImageGetWidth
import platform.CoreGraphics.CGImageRelease
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.createCGImage
import platform.CoreVideo.CVPixelBufferCreate
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferRefVar
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVReturnSuccess
import platform.Foundation.NSProcessInfo
import platform.Foundation.NSString
import platform.Foundation.NSUTF8StringEncoding
import platform.Foundation.create
import platform.Foundation.writeToFile
import platform.Metal.MTLCreateSystemDefaultDevice
import platform.QuartzCore.CACurrentMediaTime
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

/**
 * What it costs to hand a rendered frame across to Swift, and what shape survives the crossing.
 *
 * The `CGImage`-behind-an-opaque-handle shape is reachable but unmeasured, with the Swift side
 * unexercised, and it gates `core`'s API. This closes the measurement half. The export half is the
 * generated Objective-C header plus a `swiftc -typecheck` run, recorded alongside these numbers.
 *
 * Two costs are separated deliberately, because they are paid by different callers:
 *
 * 1. `CIContext.createCGImage`, what the zero-copy handle costs. Every caller pays it.
 * 2. `CGBitmapContext` readback to RGBA_8888, what the `toRgba8888()` escape costs on top.
 *    Only a caller who asks for bytes pays it.
 *
 * The source is a `CVPixelBuffer` rather than a generated `CIImage` on purpose: a solid-colour or
 * generator-backed image lets Core Image defer work into `createCGImage` and inflate arm 1 with
 * pixel production that the real path, a decoded frame already in a buffer, does not do.
 */
@OptIn(ExperimentalForeignApi::class)
class FrameHandoffTest {
  @Test
  fun handoffCostIsMeasuredAtBothResolutions() {
    val context =
      MTLCreateSystemDefaultDevice()
        ?.let {
          @Suppress("UNCHECKED_CAST")
          CIContext.contextWithMTLDevice(it as objcnames.protocols.MTLDeviceProtocol)
        }
        ?: CIContext.contextWithOptions(null)

    val rows = mutableListOf("resolution\tarm\tp50_ms\tp95_ms\tmax_ms\tbytes")

    listOf(1920 to 1080, 3840 to 2160).forEach { (width, height) ->
      val buffer = checkNotNull(createFilledBuffer(width, height)) { "CVPixelBufferCreate failed" }
      val source = CIImage.imageWithCVPixelBuffer(buffer)

      // Warm the graph. The first render compiles it, and including that in a p50 would report the
      // compile rather than the copy.
      val warmup = context.createCGImage(source, fromRect = source.extent)
      CGImageRelease(warmup)

      if (warmup == null && isSimulator()) {
        // The simulator's Core Image stack refuses to materialise an image here, exactly as its
        // decoder refuses the fixtures in the sibling still-mode test. That is an environment
        // limit and says nothing about the mechanism, so it is reported rather than dressed up as
        // a finding, or worse, as a pass. Run macosArm64Test for the numbers.
        println(
          "SKIPPED: this simulator could not create a CGImage at ${width}x$height, so the frame " +
            "handoff was not measured here. Run macosArm64Test instead.",
        )
        return@forEach
      }

      val createMs = mutableListOf<Double>()
      val copyMs = mutableListOf<Double>()
      var copiedBytes = 0

      repeat(ITERATIONS) {
        val startCreate = CACurrentMediaTime()
        val cgImage = context.createCGImage(source, fromRect = source.extent)
        createMs += (CACurrentMediaTime() - startCreate) * 1000.0

        assertTrue(cgImage != null, "createCGImage returned null at ${width}x$height")
        assertEquals(width, CGImageGetWidth(cgImage).toInt())
        assertEquals(height, CGImageGetHeight(cgImage).toInt())

        val handoff = HandoffImage(cgImage, source)
        val startCopy = CACurrentMediaTime()
        val bytes = handoff.toRgba8888()
        copyMs += (CACurrentMediaTime() - startCopy) * 1000.0
        copiedBytes = bytes.size

        handoff.close()
      }

      assertEquals(width * height * 4, copiedBytes, "the copy escape must be tightly packed RGBA")

      rows += row("${width}x$height", "createCGImage", createMs, 0)
      rows += row("${width}x$height", "toRgba8888", copyMs, copiedBytes)
    }

    val report = rows.joinToString("\n")
    println(report)

    // Only a run that measured something should overwrite the checked-in numbers, and only one
    // that measured it without the rest of the build competing for the GPU: a run during a
    // parallel compile reports p95 figures several times the isolated ones. The ratio between the
    // arms survives that. The absolute values do not.
    if (rows.size > 1) writeResults("frame-handoff.tsv", report)
  }

  private fun row(
    resolution: String,
    arm: String,
    samples: List<Double>,
    bytes: Int,
  ): String {
    val sorted = samples.sorted()

    fun percentile(fraction: Double) = sorted[(sorted.size * fraction).toInt().coerceAtMost(sorted.size - 1)]
    return listOf(
      resolution,
      arm,
      format(percentile(0.50)),
      format(percentile(0.95)),
      format(sorted.last()),
      bytes.toString(),
    ).joinToString("\t")
  }

  private fun format(value: Double): String {
    val scaled = (value * 1000.0).toLong()
    return "${scaled / 1000}.${(scaled % 1000).toString().padStart(3, '0')}"
  }

  /**
   * A buffer with a non-uniform pattern, so nothing downstream can shortcut on a constant.
   */
  private fun createFilledBuffer(
    width: Int,
    height: Int,
  ): CVPixelBufferRef? =
    memScoped {
      val holder = alloc<CVPixelBufferRefVar>()
      val status =
        CVPixelBufferCreate(
          allocator = null,
          width = width.toULong(),
          height = height.toULong(),
          pixelFormatType = kCVPixelFormatType_32BGRA,
          pixelBufferAttributes = null,
          pixelBufferOut = holder.ptr,
        )
      if (status != kCVReturnSuccess) return@memScoped null

      val buffer = holder.value ?: return@memScoped null
      CVPixelBufferLockBaseAddress(buffer, 0uL)
      val base = CVPixelBufferGetBaseAddress(buffer)?.reinterpret<UByteVar>()
      val stride = CVPixelBufferGetBytesPerRow(buffer).toInt()
      if (base != null) {
        for (y in 0 until height) {
          val rowStart = y * stride
          for (x in 0 until width step 4) {
            base[rowStart + x] = ((x + y) and 0xFF).toUByte()
          }
        }
      }
      CVPixelBufferUnlockBaseAddress(buffer, 0uL)
      buffer
    }

  private fun writeResults(
    name: String,
    contents: String,
  ) {
    val directory = environment("FILMSTRIP_RESULTS") ?: return
    NSString.create(string = contents).writeToFile(
      path = "$directory/$name",
      atomically = true,
      encoding = NSUTF8StringEncoding,
      error = null,
    )
  }

  private fun environment(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

  private fun isSimulator(): Boolean = environment("SIMULATOR_DEVICE_NAME") != null

  private companion object {
    const val ITERATIONS = 30
  }
}
