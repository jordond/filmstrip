package dev.jordond.filmstrip.playback.internal

import android.content.Context
import android.graphics.Bitmap
import android.graphics.BitmapFactory
import android.graphics.ColorSpace
import android.opengl.GLES20
import android.os.Build
import androidx.media3.common.ColorInfo
import androidx.media3.common.DebugViewProvider
import androidx.media3.common.Effect
import androidx.media3.common.Format
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.MediaItem
import androidx.media3.common.MimeTypes
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.common.VideoFrameProcessor
import androidx.media3.common.util.ConstantRateTimestampIterator
import androidx.media3.common.util.GlUtil
import androidx.media3.datasource.DataSourceBitmapLoader
import androidx.media3.effect.DefaultVideoFrameProcessor
import com.google.common.util.concurrent.ListenableFuture
import dev.jordond.filmstrip.media3.internal.Media3Readback
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.nio.ByteBuffer
import java.util.concurrent.Executor
import kotlin.coroutines.resumeWithException

/**
 * Draws the one frame a photo contributes, through the chain an export of it would run.
 *
 * `FrameExtractor` cannot serve a photo. It builds its player with a single video renderer and no
 * image renderer, so an image item has nothing to decode it and yields no frame at all. The picture
 * is decoded here instead and pushed through a [DefaultVideoFrameProcessor] as bitmap input,
 * carrying the chain the lowering resolved. Both paths run the same effect classes over the same
 * parameter values, so a photo reads back as the pixels the file will carry.
 *
 * The decode is media3's own image loader configured the way an export configures it, which is what
 * puts the same pixels into the chain that an export starts from, EXIF rotation included.
 *
 * Frames come off the processor's texture output rather than a surface. The chain ends in the
 * composition's own size stage, so the texture is already the frame the file would carry and
 * nothing rescales it on the way out.
 *
 * @param context The application context the picture is decoded and rendered against.
 */
internal class Media3StillFrames(
  private val context: Context,
) {
  // Matches how an export builds its loader, since the options decide the decoded pixels. The
  // executor is left at media3's process-wide default, which the pixels do not depend on.
  private val loader: DataSourceBitmapLoader by lazy {
    DataSourceBitmapLoader
      .Builder(context)
      .setBitmapFactoryOptions(decodeOptions())
      .setMaximumOutputDimension(GlUtil.MAX_BITMAP_DECODING_SIZE)
      .build()
  }

  /**
   * The frame [readback]'s photo draws, at the size its chain resolves to.
   *
   * Neither half runs on the caller's thread. The decode reads a file, and building the processor
   * parks the calling thread until its GL context is up, which is not something to do on the
   * dispatcher a player delivers its callbacks on.
   *
   * @throws IllegalStateException when the item names no picture to read.
   * @throws VideoFrameProcessingException when the chain could not be run.
   */
  suspend fun render(readback: Media3Readback): Bitmap {
    val decoded = withContext(Dispatchers.IO) { loader.load(readback.span.item).awaitBitmap() }
    return withContext(Dispatchers.Default) { draw(decoded, readback.effects) }
  }

  /**
   * Runs [effects] over [source] and reads the result back.
   *
   * The bitmap is handed over rather than lent: media3 recycles what it is queued once the last
   * frame it produced has gone downstream.
   */
  private suspend fun draw(
    source: Bitmap,
    effects: List<Effect>,
  ): Bitmap {
    val drawn = CompletableDeferred<Bitmap>()
    val registered = CompletableDeferred<Unit>()

    val processor =
      DefaultVideoFrameProcessor.Factory
        .Builder()
        .setTextureOutput(
          { producer, texture, presentationTimeUs, _ ->
            try {
              drawn.complete(texture.readPixels())
            } finally {
              producer.releaseOutputTexture(presentationTimeUs)
            }
          },
          TEXTURE_CAPACITY,
        ).build()
        .create(
          context,
          DebugViewProvider.NONE,
          // What the frame extractor renders an SDR frame into, so a photo and a video frame of
          // one composition come back in the same space.
          ColorInfo.SDR_BT709_LIMITED,
          RENDER_AUTOMATICALLY,
          DIRECT,
          object : VideoFrameProcessor.Listener {
            override fun onInputStreamRegistered(
              inputType: Int,
              format: Format,
              effects: List<Effect>,
            ) {
              registered.complete(Unit)
            }

            override fun onError(exception: VideoFrameProcessingException) {
              registered.completeExceptionally(exception)
              drawn.completeExceptionally(exception)
            }

            override fun onEnded() {
              // A chain that ended without a texture drew nothing, and the caller is still waiting.
              drawn.completeExceptionally(VideoFrameProcessingException(NO_FRAME))
            }
          },
        )

    try {
      // Registration is what opens the processor to input, and it completes on a thread of its own,
      // so a bitmap queued before the callback lands is refused rather than drawn.
      processor.registerInputStream(VideoFrameProcessor.INPUT_TYPE_BITMAP, source.inputFormat(), effects, 0L)
      registered.await()

      if (!processor.queueInputBitmap(source, ConstantRateTimestampIterator(ONE_FRAME_US, ONE_FRAME_RATE))) {
        throw VideoFrameProcessingException(REFUSED)
      }
      processor.signalEndOfInput()
      return drawn.await()
    } finally {
      // Blocks until the render thread has come down, so never from the render thread itself.
      withContext(NonCancellable + Dispatchers.Default) { processor.release() }
    }
  }

  private companion object {
    // One frame in flight is all this ever draws, and the texture is read before it is released.
    const val TEXTURE_CAPACITY = 1

    // Nothing here paces a timeline, so a drawn frame goes straight out rather than waiting to be
    // asked for.
    const val RENDER_AUTOMATICALLY = true

    // Long enough for exactly one timestamp at zero, which is the whole of a still.
    const val ONE_FRAME_US = 1L
    const val ONE_FRAME_RATE = 1f

    const val NO_FRAME = "The effect chain ended without drawing the photo."
    const val REFUSED = "The effect chain refused the photo before it could be drawn."
    const val NO_PICTURE = "The image clip names no picture to read."

    const val CHANNELS = 4

    // The listener does nothing but complete a value, so it costs the thread that fired it one
    // lambda and no hop.
    val DIRECT = Executor { command -> command.run() }

    /**
     * Reads [item]'s picture, which is the same read an export of it performs.
     */
    fun DataSourceBitmapLoader.load(item: MediaItem): ListenableFuture<Bitmap> {
      val uri = checkNotNull(item.localConfiguration) { NO_PICTURE }.uri
      return loadBitmap(uri)
    }

    /**
     * The format a decoded picture enters the chain as.
     */
    fun Bitmap.inputFormat(): Format =
      Format
        .Builder()
        .setWidth(width)
        .setHeight(height)
        .setSampleMimeType(MimeTypes.IMAGE_RAW)
        .setColorInfo(ColorInfo.SRGB_BT709_FULL)
        .build()

    /**
     * This rendered texture as a bitmap, read on the thread that drew it.
     *
     * The buffer carries the channels in the order a bitmap expects them, so the pixels are copied
     * across rather than rearranged.
     */
    fun GlTextureInfo.readPixels(): Bitmap {
      val pixels = ByteBuffer.allocateDirect(width * height * CHANNELS)
      GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
      GlUtil.checkGlError()
      GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES20.GL_UNSIGNED_BYTE, pixels)
      GlUtil.checkGlError()

      return Bitmap
        .createBitmap(width, height, Bitmap.Config.ARGB_8888)
        .also { it.copyPixelsFromBuffer(pixels) }
    }

    /**
     * Suspends until this decode finishes.
     */
    suspend fun ListenableFuture<Bitmap>.awaitBitmap(): Bitmap =
      suspendCancellableCoroutine { continuation ->
        addListener(
          {
            try {
              continuation.resume(get()) { _, decoded, _ -> decoded.recycle() }
            } catch (
              @Suppress("TooGenericExceptionCaught") broken: Exception,
            ) {
              continuation.resumeWithException(broken)
            }
          },
          DIRECT,
        )
      }

    /**
     * Pins the decode to sRGB where the platform can, which is what an export asks for too.
     */
    fun decodeOptions(): BitmapFactory.Options? =
      if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) {
        null
      } else {
        BitmapFactory.Options().apply { inPreferredColorSpace = ColorSpace.get(ColorSpace.Named.SRGB) }
      }
  }
}
