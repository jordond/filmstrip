package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.interop.PIXEL_FORMAT_KEY
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import dev.jordond.filmstrip.transform.internal.hdr.toneMappedSdr
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.UByteVar
import kotlinx.cinterop.addressOf
import kotlinx.cinterop.get
import kotlinx.cinterop.reinterpret
import kotlinx.cinterop.useContents
import kotlinx.cinterop.usePinned
import platform.AVFoundation.AVAsset
import platform.AVFoundation.AVAssetReader
import platform.AVFoundation.AVAssetReaderTrackOutput
import platform.AVFoundation.AVAssetReaderVideoCompositionOutput
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoComposition
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIContext
import platform.CoreImage.CIImage
import platform.CoreImage.kCIFormatRGBAf
import platform.CoreImage.kCIImageColorSpace
import platform.CoreMedia.CMSampleBufferGetImageBuffer
import platform.CoreVideo.CVPixelBufferGetBaseAddress
import platform.CoreVideo.CVPixelBufferGetBytesPerRow
import platform.CoreVideo.CVPixelBufferGetHeight
import platform.CoreVideo.CVPixelBufferGetWidth
import platform.CoreVideo.CVPixelBufferLockBaseAddress
import platform.CoreVideo.CVPixelBufferRef
import platform.CoreVideo.CVPixelBufferUnlockBaseAddress
import platform.CoreVideo.kCVPixelBufferLock_ReadOnly
import platform.CoreVideo.kCVPixelFormatType_32BGRA
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange
import platform.CoreVideo.kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange
import platform.Foundation.NSNull
import platform.Foundation.NSURL
import kotlin.math.abs

/**
 * One frame of an SDR file as the BT.709 codes it was written with, each channel a fraction of one.
 *
 * The reader hands back the file's YCbCr converted straight to RGB, with no colour management on the way.
 */
internal class CodeFrame(
  val width: Int,
  val height: Int,
  private val bgra: ByteArray,
  private val rowBytes: Int,
) {
  /**
   * The mean code of the [READ_SIZE] square centred at ([xFraction], [yFraction]) from the top left.
   */
  fun at(
    xFraction: Float,
    yFraction: Float,
  ): Rgb {
    val left = ((width * xFraction).toInt() - READ_SIZE / 2).coerceIn(0, width - READ_SIZE)
    val top = ((height * yFraction).toInt() - READ_SIZE / 2).coerceIn(0, height - READ_SIZE)
    var red = 0.0
    var green = 0.0
    var blue = 0.0
    for (y in top until top + READ_SIZE) {
      for (x in left until left + READ_SIZE) {
        val offset = y * rowBytes + x * BGRA_BYTES
        blue += bgra[offset].toInt() and BYTE_MASK
        green += bgra[offset + 1].toInt() and BYTE_MASK
        red += bgra[offset + 2].toInt() and BYTE_MASK
      }
    }
    val scale = READ_SIZE * READ_SIZE * MAX_CODE
    return Rgb(red / scale, green / scale, blue / scale)
  }

  private companion object {
    const val READ_SIZE = 16
    const val BGRA_BYTES = 4
    const val BYTE_MASK = 0xFF
  }
}

/**
 * The first [count] frames of the SDR file at [path], in the order they are shown.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun codeFramesOf(
  path: String,
  count: Int,
): List<CodeFrame> {
  val (reader, output) = readerOf(path, kCVPixelFormatType_32BGRA.toInt())
  val frames = mutableListOf<CodeFrame>()
  try {
    while (frames.size < count) {
      val sample = output.copyNextSampleBuffer() ?: break
      CMSampleBufferGetImageBuffer(sample)?.let { frames += it.toCodeFrame() }
      CFRelease(sample)
    }
  } finally {
    reader.cancelReading()
  }
  return frames
}

@OptIn(ExperimentalForeignApi::class)
private fun readerOf(
  path: String,
  format: Int,
): Pair<AVAssetReader, AVAssetReaderTrackOutput> {
  val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
  val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
  val output = AVAssetReaderTrackOutput(track = track, outputSettings = mapOf(PIXEL_FORMAT_KEY to format))
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for $path")
  reader.addOutput(output)
  reader.startReading().shouldBeTrue()
  return reader to output
}

@OptIn(ExperimentalForeignApi::class)
private fun CVPixelBufferRef.toCodeFrame(): CodeFrame {
  CVPixelBufferLockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  try {
    val width = CVPixelBufferGetWidth(this).toInt()
    val height = CVPixelBufferGetHeight(this).toInt()
    val rowBytes = CVPixelBufferGetBytesPerRow(this).toInt()
    val base = CVPixelBufferGetBaseAddress(this)?.reinterpret<UByteVar>() ?: error("no base address")
    return CodeFrame(width, height, ByteArray(rowBytes * height) { base[it].toByte() }, rowBytes)
  } finally {
    CVPixelBufferUnlockBaseAddress(this, kCVPixelBufferLock_ReadOnly)
  }
}

/**
 * Frame [index] of [graph] as its video composition renders it, before any encoder has touched it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun renderedFrameOf(
  graph: AvComposition,
  index: Int,
): CodeFrame = renderedFrameOf(graph.composition, graph.videoComposition, index)

/**
 * Frame [index] of [asset] as [composition] renders it, before any encoder has touched it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun renderedFrameOf(
  asset: AVAsset,
  composition: AVVideoComposition?,
  index: Int,
): CodeFrame {
  val output =
    AVAssetReaderVideoCompositionOutput.assetReaderVideoCompositionOutputWithVideoTracks(
      asset.tracksWithMediaType(AVMediaTypeVideo),
      videoSettings = mapOf(PIXEL_FORMAT_KEY to kCVPixelFormatType_32BGRA.toInt()),
    )
  output.videoComposition = composition
  val reader = AVAssetReader.assetReaderWithAsset(asset, error = null) ?: error("no reader for the graph")
  reader.addOutput(output)
  reader.startReading().shouldBeTrue()
  try {
    repeat(index) { output.copyNextSampleBuffer()?.let(::CFRelease) ?: error("the graph ended before frame $index") }
    val sample = output.copyNextSampleBuffer() ?: error("the graph ended before frame $index")
    try {
      return CMSampleBufferGetImageBuffer(sample)?.toCodeFrame() ?: error("no image buffer at frame $index")
    } finally {
      CFRelease(sample)
    }
  } finally {
    reader.cancelReading()
  }
}

/**
 * What [read] makes of the first frame of the SDR file at [path], decoded to the buffers a tone mapped plan reads.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun <T> firstFrameOf(
  path: String,
  read: (CVPixelBufferRef) -> T,
): T {
  val (reader, output) = readerOf(path, kCVPixelFormatType_420YpCbCr8BiPlanarVideoRange.toInt())
  val sample = output.copyNextSampleBuffer() ?: error("no sample in $path")
  try {
    return read(CMSampleBufferGetImageBuffer(sample) ?: error("no image buffer in $path"))
  } finally {
    CFRelease(sample)
    reader.cancelReading()
  }
}

/**
 * The signal the first frame of the HDR file at [path] carries at its centre, read the way the tone map's head reads
 * it.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun signalAtCentreOf(path: String): Rgb {
  val (reader, output) = readerOf(path, kCVPixelFormatType_420YpCbCr10BiPlanarVideoRange.toInt())
  val sample = output.copyNextSampleBuffer() ?: error("no sample in $path")
  try {
    val buffer = CMSampleBufferGetImageBuffer(sample) ?: error("no image buffer in $path")
    val image = CIImage.imageWithCVPixelBuffer(buffer, options = mapOf(kCIImageColorSpace to NSNull()))
    return image.pixelAtCentre(space = null)
  } finally {
    CFRelease(sample)
    reader.cancelReading()
  }
}

/**
 * This image's centre pixel as Core Image writes it into [space], or as it stands with no space at all.
 */
@OptIn(ExperimentalForeignApi::class)
internal fun CIImage.pixelAtCentre(space: platform.CoreGraphics.CGColorSpaceRef?): Rgb {
  val texel = FloatArray(TEXEL)
  val (x, y) = extent.useContents { origin.x + size.width / 2.0 to origin.y + size.height / 2.0 }
  texel.usePinned { pinned ->
    CIContext().render(
      this,
      toBitmap = pinned.addressOf(0),
      rowBytes = (TEXEL * Float.SIZE_BYTES).toLong(),
      bounds = CGRectMake(x, y, 1.0, 1.0),
      format = kCIFormatRGBAf,
      colorSpace = space,
    )
  }
  return Rgb(texel[0].toDouble(), texel[1].toDouble(), texel[2].toDouble())
}

/**
 * Asserts [actual] sits within [tolerance] 8-bit codes of [expected] on every channel.
 */
internal fun assertCodes(
  actual: Rgb,
  expected: Rgb,
  label: String,
  tolerance: Double = TONE_MAP_TOLERANCE_CODES,
) {
  withClue("$label read ${actual.inCodes()} against ${expected.inCodes()}") {
    val differences = listOf(actual.r - expected.r, actual.g - expected.g, actual.b - expected.b)
    differences.all { abs(it) * MAX_CODE <= tolerance + CODE_EPSILON }.shouldBeTrue()
  }
}

/**
 * What the shared curve puts [transfer]'s graded fixture at, read off the fixture itself.
 */
internal fun curveOfFixture(transfer: HdrTransfer): Rgb =
  toneMappedSdr(transfer, signalAtCentreOf(hdrFixture(transfer)))

private fun Rgb.inCodes(): List<Double> = listOf(r * MAX_CODE, g * MAX_CODE, b * MAX_CODE)

/**
 * What every backend's tone map meets against `toneMappedSdr`, in 8-bit codes.
 */
internal const val TONE_MAP_TOLERANCE_CODES = 2.0

/**
 * What a colour read back out of a written file meets, in 8-bit codes.
 *
 * Measured over every encoded colour read in the Apple tone map, ladder, SD colour, film, CRT and LUT tests on a
 * MacBook Pro with an M4 Max (Mac16,5), twice on macOS 26.6.2 through the hardware VideoToolbox encoder and twice on
 * the iOS 27.0 simulator through its software encoder, whose reads repeat exactly. The ladder test runs on macOS only.
 * The largest on macOS is 4.42 codes, a film glow's red over a flat grey, which leaves a margin of 0.58. The largest on
 * the simulator is 4.06 codes, an SMPTE C bar written as BT.709. One 8-bit Cr step in the written H.264 moves red by
 * 1.8 codes, so a colour rounds further in the encoder than a grey does.
 */
internal const val WRITTEN_COLOUR_CODES = 5.0

/**
 * What a luma read, or a mean of the three channels, read back out of a written file meets, in 8-bit codes.
 *
 * Measured over the film glow's luma and the VHS channel means on a MacBook Pro with an M4 Max (Mac16,5), once on macOS
 * 26.6.2 and twice on the iOS 27.0 simulator, whose reads repeat exactly. The largest on macOS is 1.13 codes, a VHS
 * frame over a tone mapped HLG clip. The largest on the simulator is 1.26 codes, a VHS frame over a tone mapped PQ
 * clip, which leaves a margin of 0.24. Luma is carried at full resolution in the written H.264, and a mean of the
 * channels follows it, so either rounds less than one colour does.
 */
internal const val WRITTEN_LUMA_CODES = 1.5

private const val MAX_CODE = 255.0
private const val CODE_EPSILON = 1e-9
private const val TEXEL = 4
