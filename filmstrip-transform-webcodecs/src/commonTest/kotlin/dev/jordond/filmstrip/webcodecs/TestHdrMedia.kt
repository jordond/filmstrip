@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.media.BT2020_CB_SCALE
import dev.jordond.filmstrip.media.BT2020_CR_SCALE
import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.hlgDisplayNitsFromScene
import dev.jordond.filmstrip.media.hlgSceneFromDisplayNits
import dev.jordond.filmstrip.media.hlgSignalFromScene
import dev.jordond.filmstrip.media.nitsFromPqSignal
import dev.jordond.filmstrip.media.pqSignalFromNits
import dev.jordond.filmstrip.media.sceneFromHlgSignal
import dev.jordond.filmstrip.webcodecs.internal.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.HDR_VP9_CODEC
import dev.jordond.filmstrip.webcodecs.internal.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.Output
import dev.jordond.filmstrip.webcodecs.internal.Quality
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.Uint16Array
import dev.jordond.filmstrip.webcodecs.internal.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.VideoSampleSource
import dev.jordond.filmstrip.webcodecs.internal.toUint8Array
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.get
import kotlin.math.roundToInt

// Ten-bit fixtures and read-back, so an HDR test can look at code values rather than at the
// eight-bit picture a canvas would give it. The fixtures are built here rather than committed, the
// same way makeClip builds its own, and every expected figure comes off the shared transfer
// functions, so a fixture and the pipeline that reads it back cannot agree on a number core does
// not.

/**
 * Muxes [frames] frames of ten-bit BT.2020 [transfer] video into an MP4 and hands the file back.
 *
 * [row] gives one scanline's linear BT.2020 light in cd/m2, which lets a test tell the top of a
 * frame from the bottom. Chroma is averaged over the two rows of its block, which is what a
 * conforming 4:2:0 encoder would have written.
 */
internal suspend fun makeHdrClip(
  transfer: HdrTransfer,
  width: Int = 64,
  height: Int = 64,
  frames: Int = 12,
  frameRate: Int = 30,
  row: (y: Int) -> FloatArray,
): ByteArray {
  val target = BufferTarget()
  val output =
    Output(
      JsOptions()
        .put("format", Mp4OutputFormat())
        .put("target", target)
        .build(),
    )
  val source =
    VideoSampleSource(
      JsOptions()
        .put("codec", "vp9")
        .put("fullCodecString", HDR_VP9_CODEC)
        .put("quality", Quality(JsOptions().put("bitrate", HDR_FIXTURE_BITRATE).build()))
        .put("keyFrameInterval", 1.0)
        .build(),
    )
  output.addVideoTrack(source, JsOptions().put("frameRate", frameRate).build())
  output.start().await()

  val planes = tenBitPlanes(transfer, width, height, row)
  for (index in 0 until frames) {
    val sample =
      VideoSample(
        planes.toUint8Array(),
        JsOptions()
          .put("format", TEN_BIT)
          .put("codedWidth", width)
          .put("codedHeight", height)
          .put("timestamp", index.toDouble() / frameRate)
          .put("duration", 1.0 / frameRate)
          .put("colorSpace", colorSpaceOf(transfer))
          .build(),
      )
    try {
      source.add(sample).await()
    } finally {
      sample.close()
    }
  }

  output.finalize().await()
  val buffer = target.buffer ?: error("the fixture muxer produced no buffer")
  val view = Uint8Array(buffer)
  return ByteArray(view.length) { view.at(it).toByte() }
}

/**
 * The tightly packed `I420P10` bytes of one frame, which is the layout a `VideoSample` assumes when
 * it is given none.
 */
private fun tenBitPlanes(
  transfer: HdrTransfer,
  width: Int,
  height: Int,
  row: (y: Int) -> FloatArray,
): ByteArray {
  val signals = List(height) { y -> transfer.pictureSignalOf(row(y)) }
  val lumaBytes = width * height * SAMPLE_BYTES
  val chromaBytes = (width / 2) * (height / 2) * SAMPLE_BYTES
  val bytes = ByteArray(lumaBytes + 2 * chromaBytes)

  for (y in 0 until height) {
    val luma = lumaCodeOf(signals[y])
    for (x in 0 until width) bytes.putCode((y * width + x) * SAMPLE_BYTES, luma)
  }
  for (y in 0 until height / 2) {
    val mean = FloatArray(3) { (signals[2 * y][it] + signals[2 * y + 1][it]) / 2f }
    val (cb, cr) = chromaCodesOf(mean)
    for (x in 0 until width / 2) {
      val offset = (y * (width / 2) + x) * SAMPLE_BYTES
      bytes.putCode(lumaBytes + offset, cb)
      bytes.putCode(lumaBytes + chromaBytes + offset, cr)
    }
  }
  return bytes
}

/**
 * One decoded ten-bit frame, with its planes in memory so a test can read a code value at a point.
 */
internal class TenBitFrame(
  val timestampUs: Double,
  val width: Int,
  val height: Int,
  private val transfer: HdrTransfer,
  private val luma: IntArray,
  private val cb: IntArray,
  private val cr: IntArray,
) {
  /**
   * The luma code at a point given as fractions of the frame, measured from the top left the way
   * filmstrip measures everything else.
   */
  fun lumaAt(
    x: Double,
    y: Double,
  ): Int = luma[rowOf(y) * width + columnOf(x)]

  /**
   * The Cb and Cr codes covering that same point.
   */
  fun chromaAt(
    x: Double,
    y: Double,
  ): Pair<Int, Int> {
    val index = (rowOf(y) / 2) * (width / 2) + columnOf(x) / 2
    return cb[index] to cr[index]
  }

  /**
   * The linear BT.2020 display light at that point, in cd/m2, decoded through the shared transfer
   * functions rather than through a curve of this file's own.
   */
  fun nitsAt(
    x: Double,
    y: Double,
  ): FloatArray {
    val (cbCode, crCode) = chromaAt(x, y)
    val signal = signalOf(lumaAt(x, y), cbCode, crCode)
    return FloatArray(3) { transfer.displayNitsFromSignal(signal[it]) }
  }

  private fun columnOf(x: Double): Int = (x * width).toInt().coerceIn(0, width - 1)

  private fun rowOf(y: Double): Int = (y * height).toInt().coerceIn(0, height - 1)
}

/**
 * Decodes every frame of [source] as ten-bit planes. The reader never saw the encoder, and it opens
 * the same software decoder the export's own compositor reads through.
 */
internal suspend fun decodeTenBitFrames(
  source: MediaSource,
  transfer: HdrTransfer,
): List<TenBitFrame> {
  val reader = SourceReader.of(source) ?: error("the decoder could not open $source")
  try {
    val stream =
      reader.frames(0.0, Double.POSITIVE_INFINITY, tenBit = true)
        ?: error("the decoded file has no video track")
    val frames = mutableListOf<TenBitFrame>()
    try {
      while (true) {
        val sample = stream.next() ?: break
        try {
          val format = sample.format?.toString()
          check(format == TEN_BIT) { "the decoded frame was $format rather than $TEN_BIT" }
          val options = JsOptions().build()
          val bytes = ArrayBuffer(sample.allocationSize(options))
          val layout = sample.copyTo(bytes, options).await()
          val width = sample.codedWidth
          val height = sample.codedHeight
          val planes =
            List(PLANES) { index ->
              val plane = checkNotNull(layout[index])
              val samplesPerRow = plane.stride / SAMPLE_BYTES
              val planeWidth = if (index == 0) width else width / 2
              val planeHeight = if (index == 0) height else height / 2
              val view = Uint16Array(bytes, plane.offset, samplesPerRow * planeHeight)
              IntArray(planeWidth * planeHeight) { at ->
                view.at((at / planeWidth) * samplesPerRow + at % planeWidth)
              }
            }
          frames +=
            TenBitFrame(
              timestampUs = sample.microsecondTimestamp,
              width = width,
              height = height,
              transfer = transfer,
              luma = planes[0],
              cb = planes[1],
              cr = planes[2],
            )
        } finally {
          sample.close()
        }
      }
    } finally {
      stream.close()
    }
    return frames
  } finally {
    reader.close()
  }
}

/**
 * The limited-range ten-bit luma code one channel triple of signal encodes to.
 */
internal fun lumaCodeOf(signal: FloatArray): Int =
  (LUMA_FLOOR + LUMA_RANGE * lumaOf(signal)).roundToInt().coerceIn(0, MAX_CODE)

/**
 * The limited-range ten-bit Cb and Cr codes one channel triple of signal encodes to.
 */
internal fun chromaCodesOf(signal: FloatArray): Pair<Int, Int> {
  val luma = lumaOf(signal)
  val cb = (signal[2] - luma) / BT2020_CB_SCALE
  val cr = (signal[0] - luma) / BT2020_CR_SCALE
  return (CHROMA_MID + CHROMA_RANGE * cb).roundToInt().coerceIn(0, MAX_CODE) to
    (CHROMA_MID + CHROMA_RANGE * cr).roundToInt().coerceIn(0, MAX_CODE)
}

/**
 * The signal a picture pixel of [nits] of display light encodes to, per channel.
 *
 * The inverse of [displayNitsFromSignal], and what every backend keeping a grade writes for a
 * picture. It is not what a fill writes on HLG: a fill's opto-optical transfer is driven by the
 * colour's luminance, which `signalFromNits` spells and which a saturated colour parts from.
 */
internal fun HdrTransfer.pictureSignalOf(nits: FloatArray): FloatArray =
  FloatArray(3) {
    when (this) {
      HdrTransfer.Pq -> pqSignalFromNits(nits[it])
      HdrTransfer.Hlg -> hlgSignalFromScene(hlgSceneFromDisplayNits(nits[it]))
    }
  }

/**
 * The display light one channel of [signal] carries, in cd/m2, per channel.
 *
 * The inverse of what the pack pass writes for a picture pixel: PQ's own transfer, and for HLG the
 * per-channel opto-optical transfer media3 and ffmpeg apply.
 */
internal fun HdrTransfer.displayNitsFromSignal(signal: Float): Float =
  when (this) {
    HdrTransfer.Pq -> nitsFromPqSignal(signal)
    HdrTransfer.Hlg -> hlgDisplayNitsFromScene(sceneFromHlgSignal(signal))
  }

internal fun colorSpaceOf(transfer: HdrTransfer) =
  JsOptions()
    .put("primaries", "bt2020")
    .put("transfer", if (transfer == HdrTransfer.Pq) "pq" else "hlg")
    .put("matrix", "bt2020-ncl")
    .put("fullRange", false)
    .build()

private fun lumaOf(signal: FloatArray): Float =
  BT2020_LUMA_R * signal[0] + BT2020_LUMA_G * signal[1] + BT2020_LUMA_B * signal[2]

private fun signalOf(
  luma: Int,
  cb: Int,
  cr: Int,
): FloatArray {
  val y = (luma - LUMA_FLOOR) / LUMA_RANGE
  val blueDiff = (cb - CHROMA_MID) / CHROMA_RANGE
  val redDiff = (cr - CHROMA_MID) / CHROMA_RANGE
  val red = y + BT2020_CR_SCALE * redDiff
  val blue = y + BT2020_CB_SCALE * blueDiff
  val green = (y - BT2020_LUMA_R * red - BT2020_LUMA_B * blue) / BT2020_LUMA_G
  return floatArrayOf(red.coerceIn(0f, 1f), green.coerceIn(0f, 1f), blue.coerceIn(0f, 1f))
}

private fun ByteArray.putCode(
  offset: Int,
  code: Int,
) {
  this[offset] = (code and BYTE_MASK).toByte()
  this[offset + 1] = ((code shr BYTE_BITS) and BYTE_MASK).toByte()
}

internal const val TEN_BIT = "I420P10"

private const val PLANES = 3
private const val SAMPLE_BYTES = 2
private const val BYTE_BITS = 8
private const val BYTE_MASK = 0xFF
private const val MAX_CODE = 1023
private const val LUMA_FLOOR = 64
private const val LUMA_RANGE = 876f
private const val CHROMA_MID = 512
private const val CHROMA_RANGE = 896f
private const val HDR_FIXTURE_BITRATE = 12_000_000
