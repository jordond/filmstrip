package dev.jordond.filmstrip.media3.internal.bounds

import android.annotation.TargetApi
import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.graphics.Gainmap
import android.net.Uri
import android.os.Build
import androidx.media3.common.C
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.BitmapLoader
import androidx.media3.common.util.GlUtil
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.DefaultVideoFrameProcessor
import androidx.media3.transformer.AssetLoader
import androidx.media3.transformer.SampleConsumer
import com.google.common.util.concurrent.Futures
import com.google.common.util.concurrent.ListenableFuture
import com.google.common.util.concurrent.MoreExecutors
import dev.jordond.filmstrip.media.HdrTransfer
import java.util.concurrent.Executor

/**
 * The HDR grade a held picture is laid on, carried as its item's tag so the asset loader reports the picture in the
 * grade's colour.
 *
 * media3 reads a picture as sRGB and encodes an export in the colour of its first item, so a solid opening an HDR
 * export would turn the whole export SDR. A solid's own pixels never reach the output, so reporting it in the grade's
 * colour costs nothing and keeps the grade. [SrgbPictureProcessors] reads the picture back as the sRGB it holds, and
 * [gainmappedPictures] loads it.
 *
 * @property transfer The transfer function the export keeps.
 */
internal data class PictureGrade(
  val transfer: HdrTransfer,
) {
  /**
   * The colour the picture is reported in, BT.2020 at limited range with this grade's transfer.
   */
  val colorInfo: ColorInfo
    get() =
      ColorInfo
        .Builder()
        .setColorSpace(C.COLOR_SPACE_BT2020)
        .setColorRange(C.COLOR_RANGE_LIMITED)
        .setColorTransfer(
          when (transfer) {
            HdrTransfer.Pq -> C.COLOR_TRANSFER_ST2084
            HdrTransfer.Hlg -> C.COLOR_TRANSFER_HLG
          },
        ).build()
}

/**
 * The [PictureGrade] this item was laid with, or null for an item that is not a picture on an HDR grade.
 */
internal val MediaItem.pictureGrade: PictureGrade? get() = localConfiguration?.tag as? PictureGrade

/**
 * Passes every call on to [listener], with each format the loader reports recoloured to [color].
 */
internal class RecoloredListener(
  private val listener: AssetLoader.Listener,
  private val color: ColorInfo,
) : AssetLoader.Listener by listener {
  override fun onTrackAdded(
    inputFormat: Format,
    supportedOutputTypes: Int,
  ): Boolean = listener.onTrackAdded(inputFormat.recolored(), supportedOutputTypes)

  override fun onOutputFormat(format: Format): SampleConsumer? = listener.onOutputFormat(format.recolored())

  private fun Format.recolored(): Format = buildUpon().setColorInfo(color).build()
}

/**
 * Builds the frame processors an export runs, each one reading every picture as sRGB whatever colour its item reports.
 *
 * media3 reads a picture's pixels as sRGB anyway, and an HDR picture reported to it fails on its way into an HDR
 * output, so a [PictureGrade] has to reach the encoder without reaching the frame processor.
 */
internal class SrgbPictureProcessors(
  private val factory: VideoFrameProcessor.Factory = DefaultVideoFrameProcessor.Factory.Builder().build(),
) : VideoFrameProcessor.Factory {
  override fun create(
    context: Context,
    debugViewProvider: DebugViewProvider,
    outputColorInfo: ColorInfo,
    renderFramesAutomatically: Boolean,
    listenerExecutor: Executor,
    listener: VideoFrameProcessor.Listener,
  ): VideoFrameProcessor =
    SrgbPictureProcessor(
      factory.create(
        context,
        debugViewProvider,
        outputColorInfo,
        renderFramesAutomatically,
        listenerExecutor,
        listener,
      ),
    )
}

private class SrgbPictureProcessor(
  private val processor: VideoFrameProcessor,
) : VideoFrameProcessor by processor {
  override fun registerInputStream(
    inputType: Int,
    format: Format,
    effects: List<Effect>,
    offsetToAddUs: Long,
  ) {
    val read =
      if (inputType == VideoFrameProcessor.INPUT_TYPE_BITMAP) {
        format.buildUpon().setColorInfo(ColorInfo.SRGB_BT709_FULL).build()
      } else {
        format
      }
    processor.registerInputStream(inputType, read, effects, offsetToAddUs)
  }
}

/**
 * Why media3 cannot lay a solid on a kept HDR grade on [sdkInt], or null when it can.
 *
 * media3 takes a picture onto an HDR output only when it carries a gainmap, and Android offers gainmaps from API 34.
 * See [gainmappedPictures].
 */
internal fun gainmapLimit(sdkInt: Int): String? =
  if (sdkInt < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) {
    "media3 lays a solid on an HDR grade as a picture with a gainmap, which needs API 34, and this device runs " +
      "API $sdkInt."
  } else {
    null
  }

/**
 * Loads pictures the way media3's own image loader does, each one carrying a gainmap that changes none of its pixels.
 *
 * media3 refuses a picture on an HDR output unless it carries a gainmap, and a solid's pixels are painted over by its
 * first effect, so a neutral one is enough to let it through.
 */
@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
internal fun gainmappedPictures(context: Context): BitmapLoader =
  NeutralGainmapLoader(
    DataSourceBitmapLoader
      .Builder(context)
      .setBitmapFactoryOptions(
        BitmapFactory.Options().apply {
          inPreferredColorSpace =
            ColorSpace.get(ColorSpace.Named.SRGB)
        },
      ).setMaximumOutputDimension(GlUtil.MAX_BITMAP_DECODING_SIZE)
      .build(),
  )

@TargetApi(Build.VERSION_CODES.UPSIDE_DOWN_CAKE)
private class NeutralGainmapLoader(
  private val loader: BitmapLoader,
) : BitmapLoader by loader {
  override fun decodeBitmap(data: ByteArray): ListenableFuture<Bitmap> = loader.decodeBitmap(data).gainmapped()

  override fun loadBitmap(uri: Uri): ListenableFuture<Bitmap> = loader.loadBitmap(uri).gainmapped()

  private fun ListenableFuture<Bitmap>.gainmapped(): ListenableFuture<Bitmap> =
    Futures.transform(
      this,
      { bitmap -> bitmap.apply { gainmap = Gainmap(Bitmap.createBitmap(1, 1, Bitmap.Config.ALPHA_8)) } },
      MoreExecutors.directExecutor(),
    )
}
