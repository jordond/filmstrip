@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.color.hlgDisplayNitsFromSignal
import dev.jordond.filmstrip.color.hlgSignalFromDisplayNits
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.webcodecs.internal.SourceReader
import dev.jordond.filmstrip.webcodecs.internal.interop.ArrayBuffer
import dev.jordond.filmstrip.webcodecs.internal.interop.BufferTarget
import dev.jordond.filmstrip.webcodecs.internal.interop.HDR_VP9_CODEC
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.Mp4OutputFormat
import dev.jordond.filmstrip.webcodecs.internal.interop.Output
import dev.jordond.filmstrip.webcodecs.internal.interop.Quality
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint16Array
import dev.jordond.filmstrip.webcodecs.internal.interop.Uint8Array
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSampleSource
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import io.kotest.assertions.withClue
import kotlinx.coroutines.await
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.get

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
    val luma = tenBitCodesFromSignal(signals[y])[0]
    for (x in 0 until width) bytes.putCode((y * width + x) * SAMPLE_BYTES, luma)
  }
  for (y in 0 until height / 2) {
    val mean = FloatArray(3) { (signals[2 * y][it] + signals[2 * y + 1][it]) / 2f }
    val (_, cb, cr) = tenBitCodesFromSignal(mean)
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
    val signal = signalFromTenBitCodes(lumaAt(x, y), cbCode, crCode)
    return FloatArray(3) { transfer.displayNitsFromSignal(signal[it]) }
  }

  /**
   * The luma, Cb and Cr codes covering that same point.
   */
  fun codesAt(
    x: Double,
    y: Double,
  ): IntArray {
    val (cbCode, crCode) = chromaAt(x, y)
    return intArrayOf(lumaAt(x, y), cbCode, crCode)
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
 * The signal a picture pixel of [nits] of display light encodes to, per channel.
 *
 * The inverse of [displayNitsFromSignal], and what every backend keeping a grade writes for a
 * picture. It is not what a fill writes on HLG: a fill's opto-optical transfer is driven by the
 * color's luminance, which `signalFromNits` spells and which a saturated color parts from.
 */
internal fun HdrTransfer.pictureSignalOf(nits: FloatArray): FloatArray =
  FloatArray(3) {
    when (this) {
      HdrTransfer.Pq -> pqSignalFromNits(nits[it])
      HdrTransfer.Hlg -> hlgSignalFromDisplayNits(nits[it])
    }
  }

/**
 * The display light one channel of [signal] carries, in cd/m2, per channel.
 *
 * The inverse of what the pack pass writes for a picture pixel: PQ's own transfer, and for HLG core's
 * per-channel display decode, the reading media3 and ffmpeg apply.
 */
internal fun HdrTransfer.displayNitsFromSignal(signal: Float): Float =
  when (this) {
    HdrTransfer.Pq -> nitsFromPqSignal(signal)
    HdrTransfer.Hlg -> hlgDisplayNitsFromSignal(signal)
  }

internal fun colorSpaceOf(transfer: HdrTransfer) =
  JsOptions()
    .put("primaries", "bt2020")
    .put("transfer", if (transfer == HdrTransfer.Pq) "pq" else "hlg")
    .put("matrix", "bt2020-ncl")
    .put("fullRange", false)
    .build()

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
private const val HDR_FIXTURE_BITRATE = 12_000_000

/**
 * Exports [composition] with its grade kept at the measuring bitrate and no audio, failing with the transfer named when
 * it does not succeed.
 */
internal suspend fun hdrExportOf(
  composition: Composition,
  transfer: HdrTransfer,
): ExportStatus.Success {
  val spec = ExportSpec(bitrate = MEASURING_BITRATE, audioCodec = AudioCodec.None, hdr = HdrMode.KeepHdr)
  return withClue("the $transfer export") { exportOf(composition = composition, to = MediaSink.Uri(""), spec = spec) }
}
