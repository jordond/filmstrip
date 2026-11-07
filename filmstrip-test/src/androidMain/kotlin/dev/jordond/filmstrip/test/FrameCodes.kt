package dev.jordond.filmstrip.test

import android.media.Image
import android.media.MediaCodec
import android.media.MediaCodecInfo
import android.media.MediaExtractor
import android.media.MediaFormat
import android.os.Build
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Size
import org.junit.AssumptionViolatedException
import java.io.File
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * One decoded frame of a video, held as the luma, Cb and Cr codes it stores.
 *
 * `MediaMetadataRetriever` tone-maps an HDR frame to eight-bit sRGB on the way out, and picks its own matrix for an
 * SDR one, so a level is read exactly off the codes the encoder wrote, turned into RGB through the matrix the file
 * records.
 *
 * @property size The frame's visible pixel size.
 */
@InternalFilmstripApi
public class FrameCodes(
  public val size: Size,
  private val luma: IntArray,
  private val cb: IntArray,
  private val cr: IntArray,
) {
  /**
   * Luma, Cb and Cr averaged over a small patch centred on the fractions [fx] and [fy] of the frame.
   */
  public fun codesAt(
    fx: Float,
    fy: Float,
  ): IntArray {
    val cx = (fx * size.width).roundToInt()
    val cy = (fy * size.height).roundToInt()
    val chromaWidth = size.width / 2
    val sums = LongArray(3)
    for (y in cy - CODE_PATCH / 2 until cy + CODE_PATCH / 2) {
      for (x in cx - CODE_PATCH / 2 until cx + CODE_PATCH / 2) {
        val chroma = (y / 2) * chromaWidth + x / 2
        sums[0] += luma[y * size.width + x]
        sums[1] += cb[chroma]
        sums[2] += cr[chroma]
      }
    }
    return IntArray(3) { (sums[it].toFloat() / (CODE_PATCH * CODE_PATCH)).roundToInt() }
  }

  /**
   * The Cb sample stored at [column] and [row] of the chroma plane, one for each aligned 2x2 block of pixels.
   */
  public fun cbAt(
    column: Int,
    row: Int,
  ): Int = cb[row * (size.width / 2) + column]

  /**
   * The Cr sample stored at [column] and [row] of the chroma plane, one for each aligned 2x2 block of pixels.
   */
  public fun crAt(
    column: Int,
    row: Int,
  ): Int = cr[row * (size.width / 2) + column]
}

/**
 * Decodes the first frame of [video] as ten-bit samples, skipping the calling test on a device that
 * cannot hand them back.
 */
@InternalFilmstripApi
public fun firstTenBitFrame(video: File): FrameCodes {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.Q) {
    throw AssumptionViolatedException("ten-bit decoder output needs API 29")
  }
  return decodedFrame(video, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUVP010, Duration.ZERO) { it.toTenBitFrame() }
}

/**
 * Decodes the first frame of [video] as eight-bit samples.
 */
@InternalFilmstripApi
public fun firstEightBitFrame(video: File): FrameCodes =
  decodedFrame(
    video,
    MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible,
    Duration.ZERO,
  ) { it.toEightBitFrame() }

/**
 * Decodes the frame of [video] nearest [at] as eight-bit samples.
 */
@InternalFilmstripApi
public fun eightBitFrameCodesAt(
  video: File,
  at: Duration,
): FrameCodes =
  decodedFrame(video, MediaCodecInfo.CodecCapabilities.COLOR_FormatYUV420Flexible, at) { it.toEightBitFrame() }

private fun decodedFrame(
  video: File,
  colorFormat: Int,
  at: Duration,
  read: (Image) -> FrameCodes,
): FrameCodes {
  val extractor = MediaExtractor()
  extractor.setDataSource(video.path)
  val track =
    (0 until extractor.trackCount).first {
      extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
    }
  extractor.selectTrack(track)
  extractor.seekTo(at.inWholeMicroseconds, MediaExtractor.SEEK_TO_PREVIOUS_SYNC)
  val format = extractor.getTrackFormat(track)
  format.setInteger(MediaFormat.KEY_COLOR_FORMAT, colorFormat)
  val codec = MediaCodec.createDecoderByType(checkNotNull(format.getString(MediaFormat.KEY_MIME)))

  try {
    codec.configure(format, null, null, 0)
    codec.start()
    return codec.decodeNearest(extractor, at.inWholeMicroseconds, read) ?: error("no frame decoded from $video")
  } finally {
    runCatching { codec.stop() }
    codec.release()
    extractor.release()
  }
}

// Decodes forward until a frame lands at or past the target, and hands back whichever frame sat closest to it.
private fun MediaCodec.decodeNearest(
  extractor: MediaExtractor,
  target: Long,
  read: (Image) -> FrameCodes,
): FrameCodes? {
  val info = MediaCodec.BufferInfo()
  var inputDone = false
  var nearest: FrameCodes? = null
  var nearestOff = Long.MAX_VALUE
  val deadline = System.nanoTime() + DECODE_BUDGET_NS

  while (System.nanoTime() < deadline) {
    if (!inputDone) inputDone = queueNextSample(extractor)

    val index = dequeueOutputBuffer(info, WAIT_US)
    if (index < 0) continue
    val frame = getOutputImage(index)?.use(read)
    releaseOutputBuffer(index, false)
    if (frame != null) {
      val off = abs(info.presentationTimeUs - target)
      if (off < nearestOff) {
        nearest = frame
        nearestOff = off
      }
      if (info.presentationTimeUs >= target) return nearest
    }
    if (info.flags and MediaCodec.BUFFER_FLAG_END_OF_STREAM != 0) return nearest
  }
  return nearest
}

// Answers whether the input has run out.
private fun MediaCodec.queueNextSample(extractor: MediaExtractor): Boolean {
  val index = dequeueInputBuffer(WAIT_US)
  if (index < 0) return false
  val buffer = checkNotNull(getInputBuffer(index))
  val size = extractor.readSampleData(buffer, 0)
  if (size < 0) {
    queueInputBuffer(index, 0, 0, 0, MediaCodec.BUFFER_FLAG_END_OF_STREAM)
    return true
  }
  queueInputBuffer(index, 0, size, extractor.sampleTime, 0)
  extractor.advance()
  return false
}

// P010 keeps each sample in the top ten bits of a little-endian sixteen-bit word, so a plane whose
// samples sit one byte apart is eight-bit output the decoder chose instead.
private fun Image.toTenBitFrame(): FrameCodes {
  check(planes[0].pixelStride == SAMPLE_BYTES) {
    "the decoder handed back ${planes[0].pixelStride} byte samples, not ten-bit ones"
  }
  return codes { buffer, offset ->
    (buffer.order(ByteOrder.LITTLE_ENDIAN).getShort(offset).toInt() and WORD_MASK) ushr P010_SHIFT
  }
}

private fun Image.toEightBitFrame(): FrameCodes = codes { buffer, offset -> buffer.get(offset).toInt() and BYTE_MASK }

private fun Image.codes(sample: (ByteBuffer, Int) -> Int): FrameCodes {
  val crop = cropRect
  val width = crop.width()
  val height = crop.height()

  fun read(
    plane: Int,
    columns: Int,
    rows: Int,
    subsampling: Int,
  ): IntArray {
    val source = planes[plane]
    val buffer = source.buffer.duplicate()
    return IntArray(columns * rows) { index ->
      val x = crop.left / subsampling + index % columns
      val y = crop.top / subsampling + index / columns
      sample(buffer, y * source.rowStride + x * source.pixelStride)
    }
  }

  return FrameCodes(
    Size(width, height),
    read(0, width, height, 1),
    read(1, width / 2, height / 2, 2),
    read(2, width / 2, height / 2, 2),
  )
}

/**
 * The side of the square [FrameCodes.codesAt] averages, in pixels.
 */
@InternalFilmstripApi
public const val CODE_PATCH: Int = 4
private const val SAMPLE_BYTES = 2
private const val WORD_MASK = 0xFFFF
private const val BYTE_MASK = 0xFF
private const val P010_SHIFT = 6
private const val WAIT_US = 10_000L
private const val DECODE_BUDGET_NS = 10_000_000_000L
