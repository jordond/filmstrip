package dev.jordond.filmstrip.media3.internal.encoder

import android.content.Context
import android.media.metrics.LogSessionId
import android.os.Build
import android.util.Log
import androidx.annotation.OptIn
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.Format
import androidx.media3.common.util.ExperimentalApi
import androidx.media3.transformer.Codec
import androidx.media3.transformer.DefaultEncoderFactory
import androidx.media3.transformer.EncoderSelector
import androidx.media3.transformer.ExportException
import androidx.media3.transformer.VideoEncoderSettings
import com.google.common.collect.ImmutableList
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.media.ColorSpace

/**
 * A [Codec.EncoderFactory] that tags an SDR video with the color its encoder really writes.
 *
 * media3 draws each frame as RGB onto the encoder's input surface and asks for SDR BT.709 at limited range. Some
 * encoders convert that RGB into YCbCr through another matrix or range than the one asked for. Android's own software
 * encoders were measured writing BT.601 at limited range for that request on some API levels. For that request
 * [probe] measures what the encoder [delegate] picked writes at the size tier it was configured at. When the answer
 * differs, that encoder is released and the factory [pinned] gives for its name makes it again from the same format
 * tagged with what was measured, so the file records the matrix and range its codes were written through. [retag]
 * holds that once it has happened, for the export to report through [retagReport].
 *
 * media3 asks for SDR BT.601 when the first clip names it. Every SD clip's frame already reaches the encoder as BT.709
 * through [dev.jordond.filmstrip.media3.internal.SdPrimariesHead], so that request is made as SDR BT.709 at limited
 * range.
 *
 * An encoder the probe cannot tell about, or a probe that throws, keeps the request and [log] says why. A recreate
 * that throws is passed on with the first encoder released. One that comes back as another encoder than the one
 * measured is released too, and fails as an [ExportException] for an encoder that could not be set up, so media3 fails
 * the export rather than writing codes the file misnames.
 *
 * An audio encoder is passed through [aacTailPadded], so one that flushes nothing at the end of the stream on [sdkInt]
 * still writes every frame, and through [aacPrimed], so the file starts its audio on the first frame of the stream.
 *
 * @param delegate The factory that picks each encoder.
 * @param pinned Gives a factory that makes only the encoder of the name it is given.
 * @param probe What tells the color an encoder writes.
 * @param log Where a kept request is explained.
 * @param sdkInt The API level the encoders run on.
 * @param frameRate The rate the export writes its frames at. The video encoder is configured at it in place of the
 * rate media3 passes, which is the source's own, since an encoder counts its key frame interval and spreads its
 * bitrate in frames of the rate it was configured at. Null leaves media3's.
 */
internal class EncoderColor(
  private val delegate: Codec.EncoderFactory,
  private val pinned: (encoder: String) -> Codec.EncoderFactory,
  private val probe: EncoderColorProbe,
  private val log: (String) -> Unit = { Log.w(TAG, it) },
  private val sdkInt: Int = Build.VERSION.SDK_INT,
  private val frameRate: Int? = null,
) : Codec.EncoderFactory {
  /**
   * The encoder whose output was tagged with another color than media3 asked for, or null while none was.
   *
   * Written when media3 creates the encoder, read after the export finishes.
   */
  @Volatile
  var retag: Retag? = null
    private set

  override fun createForAudioEncoding(
    format: Format,
    logSessionId: LogSessionId?,
  ): Codec = aacTailPadded(aacPrimed(delegate.createForAudioEncoding(format, logSessionId)), sdkInt)

  override fun createForVideoEncoding(
    format: Format,
    logSessionId: LogSessionId?,
  ): Codec {
    val paced = if (frameRate == null) format else format.buildUpon().setFrameRate(frameRate.toFloat()).build()
    val made =
      if (paced.colorInfo?.isSdrBt601() == true) {
        paced.buildUpon().setColorInfo(ColorInfo.SDR_BT709_LIMITED).build()
      } else {
        paced
      }
    val encoder = delegate.createForVideoEncoding(made, logSessionId)
    val requested = made.colorInfo?.takeIf { it.isSdrBt709Limited() } ?: return encoder
    val mimeType = made.sampleMimeType ?: return encoder
    val name = encoder.name
    val configured = encoder.configurationFormat
    val request = EncoderColorRequest(name, mimeType, SizeTier.of(configured.width, configured.height), requested)
    val written =
      when (val answer = written(request)) {
        is WrittenColor.Unclear -> {
          log("$name keeps ${requested.described()}, its color is unclear: ${answer.reason}")
          return encoder
        }
        is WrittenColor.Measured -> {
          answer.color
        }
      }
    if (written == requested) return encoder
    encoder.release()
    val retaggedFormat = made.buildUpon().setColorInfo(written).build()
    val retagged = pinned(name).createForVideoEncoding(retaggedFormat, logSessionId)
    val retaggedName = retagged.name
    if (retaggedName != name) {
      retagged.release()
      throw ExportException.createForCodec(
        IllegalStateException("the factory pinned to $name made $retaggedName, which was not measured"),
        ExportException.ERROR_CODE_ENCODER_INIT_FAILED,
        ExportException.CodecInfo(retaggedFormat.toString(), true, false, retaggedName),
      )
    }
    retag = Retag(name, requested, written)
    return retagged
  }

  private fun written(request: EncoderColorRequest): WrittenColor =
    try {
      probe.written(request)
    } catch (thrown: Exception) {
      if (thrown is InterruptedException) Thread.currentThread().interrupt()
      WrittenColor.Unclear("the probe threw $thrown")
    }

  @OptIn(markerClass = [ExperimentalApi::class])
  override fun isVideoFormatSupported(format: Format): Boolean = delegate.isVideoFormatSupported(format)

  override fun audioNeedsEncoding(): Boolean = delegate.audioNeedsEncoding()

  override fun videoNeedsEncoding(): Boolean = delegate.videoNeedsEncoding()
}

/**
 * The [DefaultEncoderFactory] an export on [context] picks its encoders with, asking the video encoder for [bitrate]
 * and a key frame every [keyFrameSeconds].
 *
 * Both are the plan's own. The planner holds the bitrate at or under the encoder's ceiling, and media3 still clamps
 * it to the encoder's range, which can raise a rate below the encoder's floor. A copy carries neither and leaves
 * media3's settings alone.
 * [pinnedEncoderFactory] builds from the same settings, so an encoder made again by name is set up as the first was.
 */
internal fun encoderFactory(
  context: Context,
  bitrate: Bitrate?,
  keyFrameSeconds: Float?,
): Codec.EncoderFactory = encoderFactoryBuilder(context, bitrate, keyFrameSeconds).build()

/**
 * A [DefaultEncoderFactory] for [context] whose video encoder selector offers only the encoder named [encoder], built
 * like [encoderFactory] otherwise.
 *
 * It makes that encoder or fails, as media3 does when no encoder it is offered takes the format.
 */
internal fun pinnedEncoderFactory(
  context: Context,
  encoder: String,
  bitrate: Bitrate?,
  keyFrameSeconds: Float?,
): Codec.EncoderFactory =
  encoderFactoryBuilder(context, bitrate, keyFrameSeconds)
    .setVideoEncoderSelector { mimeType ->
      ImmutableList.copyOf(EncoderSelector.DEFAULT.selectEncoderInfos(mimeType).filter { it.name == encoder })
    }.build()

/**
 * The video encoder settings that ask for [bitrate] and a key frame every [keyFrameSeconds], or media3's defaults
 * for the one that is null.
 *
 * media3's [VideoEncoderSettings.Builder.setBitrate] takes the rate as an Int, so a rate past [Int.MAX_VALUE] is held
 * there. That is media3's limit, not the plan's.
 *
 * `OMX.google.h264.encoder` writes an I frame every [keyFrameSeconds] and an IDR only every 1000 frames, so a file
 * from it holds a key frame every 1000 frames whatever is asked for. That is the encoder's limit, and it is the only
 * H.264 encoder on an API 30 emulator.
 */
internal fun videoEncoderSettings(
  bitrate: Bitrate?,
  keyFrameSeconds: Float?,
): VideoEncoderSettings {
  if (bitrate == null && keyFrameSeconds == null) return VideoEncoderSettings.DEFAULT
  val settings = VideoEncoderSettings.Builder()
  bitrate?.let { settings.setBitrate(it.bitsPerSecond.coerceAtMost(Int.MAX_VALUE.toLong()).toInt()) }
  keyFrameSeconds?.let { settings.setiFrameIntervalSeconds(it) }
  return settings.build()
}

/**
 * The plan's key frame spacing in seconds of [output]'s frame rate, which is the unit media3's encoder settings take
 * it in. Null when the plan settled none.
 */
internal fun keyFrameSecondsOf(output: OutputFormat): Float? =
  output.keyFrameSpacing?.let { it.toFloat() / output.frameRate }

private fun encoderFactoryBuilder(
  context: Context,
  bitrate: Bitrate?,
  keyFrameSeconds: Float?,
): DefaultEncoderFactory.Builder =
  DefaultEncoderFactory
    .Builder(context)
    .setRequestedVideoEncoderSettings(videoEncoderSettings(bitrate, keyFrameSeconds))

/**
 * An encoder [EncoderColor] tagged with the color it writes in place of the one media3 asked for.
 *
 * @property encoder The name of the encoder the export ran on.
 * @property requested The color media3 asked the encoder for.
 * @property written The color the encoder writes, which the file is tagged with.
 */
internal class Retag(
  val encoder: String,
  val requested: ColorInfo,
  val written: ColorInfo,
)

/**
 * The adjustment an export on [path] reports when [retag] names the encoder [EncoderColor] tagged with the color it
 * writes, or null when nothing was tagged.
 *
 * An [ExportPath.Copy] export encodes nothing, so it never reports one. Android's `MediaFormat` and media3's
 * [ColorInfo] name the primaries and the matrix together as one color space, so a file tagged BT.601 for its matrix
 * records BT.470 BG primaries too, which the message says.
 */
internal fun retagReport(
  path: ExportPath,
  retag: Retag?,
): Adjustment? {
  if (path == ExportPath.Copy || retag == null) return null
  val primaries =
    if (retag.written.colorSpace == C.COLOR_SPACE_BT601) {
      " Android names the primaries and the matrix together, so the file records BT.470 BG primaries as well, and a " +
        "color-managed player can show greens a shade apart from the preview."
    } else {
      ""
    }
  return Adjustment(
    kind = AdjustmentKind.ColorSpaceChanged,
    requested = retag.requested.described(),
    resolved = retag.written.described(),
    message =
      "This export ran on ${retag.encoder}, which was measured writing ${retag.written.described()} when asked for " +
        "${retag.requested.described()}. The file is tagged with what it writes, so its colors decode as " +
        "authored.$primaries",
  )
}

private fun ColorInfo.described(): String {
  val space = if (colorSpace == C.COLOR_SPACE_BT601) ColorSpace.Bt601 else ColorSpace.Bt709
  val range = if (colorRange == C.COLOR_RANGE_FULL) "full" else "limited"
  return "${space.name} at $range range"
}

private fun ColorInfo.isSdrBt709Limited(): Boolean =
  colorSpace == C.COLOR_SPACE_BT709 && colorRange == C.COLOR_RANGE_LIMITED && colorTransfer == C.COLOR_TRANSFER_SDR

private fun ColorInfo.isSdrBt601(): Boolean = colorSpace == C.COLOR_SPACE_BT601 && colorTransfer == C.COLOR_TRANSFER_SDR

private const val TAG = "filmstrip-media3"
