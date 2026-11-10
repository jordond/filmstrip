package dev.jordond.filmstrip.media3

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.media.MediaCodec
import android.media.MediaExtractor
import android.media.MediaFormat
import android.media.MediaMuxer
import android.net.Uri
import androidx.media3.inspector.MediaExtractorCompat
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.writeAtomically
import java.io.File
import java.io.IOException
import java.nio.ByteBuffer
import kotlin.time.Duration.Companion.milliseconds

/**
 * Writes an H.264 clip of vertical bars in each of [bars]' red, green and blue to [target], from zero to one.
 *
 * The picture is a still run through an export, so the encoder picks the codes, and a test reads them back off the file
 * rather than trusting them. Its samples are then copied into a new container naming only [colorStandard], at limited
 * range and on the SDR transfer. Without one, the container names no colour at all.
 */
internal suspend fun Media3ExportProbe.writeBarsClip(
  target: File,
  width: Int,
  height: Int,
  bars: List<FloatArray>,
  colorStandard: Int? = null,
) {
  val still = File(context.cacheDir, "${target.nameWithoutExtension}.png")
  val bitmap = barsBitmap(width, height, bars)
  still.writeAtomically { bitmap.compress(Bitmap.CompressFormat.PNG, BARS_PNG_QUALITY, it) }
  val source = MediaSource.Image(ImageSource.of(still.path), LENGTH)
  val picture = export(Composition(listOf(Track(listOf(Clip(source))))), ExportSpec(heightPx = height))
  copyNaming(picture, target, colorStandard)
}

private fun barsBitmap(
  width: Int,
  height: Int,
  bars: List<FloatArray>,
): Bitmap {
  val bitmap = Bitmap.createBitmap(width, height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint()
  bars.forEachIndexed { index, (red, green, blue) ->
    paint.color = Color.rgb((red * MAX_CODE).toInt(), (green * MAX_CODE).toInt(), (blue * MAX_CODE).toInt())
    val left = (index * width / bars.size).toFloat()
    val right = ((index + 1) * width / bars.size).toFloat()
    canvas.drawRect(left, 0f, right, height.toFloat(), paint)
  }
  return bitmap
}

private fun copyNaming(
  source: File,
  target: File,
  colorStandard: Int?,
) {
  // Muxed beside the target and renamed over it, so a run killed part way never leaves a truncated clip behind.
  val partial = File.createTempFile("${target.name}.", ".partial", target.absoluteFile.parentFile)
  try {
    mux(source, partial, colorStandard)
    if (!partial.renameTo(target)) throw IOException("could not move $partial over $target")
  } finally {
    partial.delete()
  }
}

private fun mux(
  source: File,
  target: File,
  colorStandard: Int?,
) {
  val extractor = MediaExtractorCompat().apply { setDataSource(Uri.fromFile(source), 0L) }
  val muxer = MediaMuxer(target.path, MediaMuxer.OutputFormat.MUXER_OUTPUT_MPEG_4)
  try {
    val index =
      (0 until extractor.trackCount).first {
        extractor.getTrackFormat(it).getString(MediaFormat.KEY_MIME)?.startsWith("video/") == true
      }
    extractor.selectTrack(index)
    val track = muxer.addTrack(extractor.getTrackFormat(index).withOnlyParameterSets(colorStandard))
    muxer.start()
    val buffer = ByteBuffer.allocate(SAMPLE_BUFFER_BYTES)
    val info = MediaCodec.BufferInfo()
    while (extractor.sampleTime >= 0) {
      val sync = (extractor.sampleFlags and MediaExtractor.SAMPLE_FLAG_SYNC) != 0
      val flags = if (sync) MediaCodec.BUFFER_FLAG_KEY_FRAME else 0
      info.set(0, extractor.readSampleData(buffer, 0), extractor.sampleTime, flags)
      muxer.writeSampleData(track, buffer, info)
      extractor.advance()
    }
    muxer.stop()
  } finally {
    muxer.release()
    extractor.release()
  }
}

private fun MediaFormat.withOnlyParameterSets(colorStandard: Int?): MediaFormat {
  val copied =
    MediaFormat.createVideoFormat(
      checkNotNull(getString(MediaFormat.KEY_MIME)),
      getInteger(MediaFormat.KEY_WIDTH),
      getInteger(MediaFormat.KEY_HEIGHT),
    )
  listOf("csd-0", "csd-1").forEach { key -> copied.setByteBuffer(key, checkNotNull(getByteBuffer(key))) }
  if (colorStandard != null) {
    copied.setInteger(MediaFormat.KEY_COLOR_STANDARD, colorStandard)
    copied.setInteger(MediaFormat.KEY_COLOR_TRANSFER, MediaFormat.COLOR_TRANSFER_SDR_VIDEO)
    copied.setInteger(MediaFormat.KEY_COLOR_RANGE, MediaFormat.COLOR_RANGE_LIMITED)
  }
  return copied
}

private const val MAX_CODE = 255
private const val BARS_PNG_QUALITY = 100
private const val SAMPLE_BUFFER_BYTES = 2 * 1024 * 1024
private val LENGTH = 500.milliseconds
