package dev.jordond.filmstrip.player.internal

import android.content.Context
import android.opengl.GLES20
import android.opengl.GLES30
import android.util.Half
import androidx.media3.common.GlObjectsProvider
import androidx.media3.common.GlTextureInfo
import androidx.media3.common.util.GlUtil
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.PassthroughShaderProgram
import dev.jordond.filmstrip.effects.media3Signal
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import java.nio.ByteBuffer
import java.nio.ByteOrder
import kotlin.time.Duration

/**
 * The last effect of a readback chain on a kept HDR grade, which copies each frame out of media3's working space as it
 * passes and hands it on untouched.
 *
 * media3 holds an HDR frame between effects as half float linear light, and its frame extractor only hands one back
 * through HLG, which clips a PQ grade at media3's own peak. Reading the texture here keeps every figure the chain drew,
 * so [frameAt] can encode it in the grade's own transfer through [media3Signal].
 */
internal class HdrTap : GlEffect {
  @Volatile
  private var captured: Captured? = null

  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = TapProgram(useHdr)

  /**
   * The frame the chain passed last as codes in [transfer], reported at [presentationTime].
   *
   * @throws IllegalStateException when no frame passed, or media3 drew it in SDR.
   */
  fun frameAt(
    presentationTime: Duration,
    transfer: HdrTransfer,
  ): HdrPreviewFrame {
    val frame = checkNotNull(captured) { NOTHING_PASSED }
    check(frame.hdr) { DRAWN_IN_SDR }
    val pixels = frame.size.width * frame.size.height
    val signal = FloatArray(pixels * RGB)
    val working = FloatArray(RGB)
    for (pixel in 0 until pixels) {
      for (channel in 0 until RGB) working[channel] = Half.toFloat(frame.halves[pixel * RGBA + channel])
      media3Signal(working, transfer).copyInto(signal, pixel * RGB)
    }
    return HdrPreviewFrame.fromSignal(signal, frame.size, presentationTime, transfer)
  }

  private inner class TapProgram(
    private val hdr: Boolean,
  ) : PassthroughShaderProgram() {
    override fun queueInputFrame(
      glObjectsProvider: GlObjectsProvider,
      inputTexture: GlTextureInfo,
      presentationTimeUs: Long,
    ) {
      try {
        captured = Captured(inputTexture.readHalves(), Size(inputTexture.width, inputTexture.height), hdr)
      } catch (failure: GlUtil.GlException) {
        onError(failure)
        return
      }
      super.queueInputFrame(glObjectsProvider, inputTexture, presentationTimeUs)
    }
  }

  private class Captured(
    val halves: ShortArray,
    val size: Size,
    val hdr: Boolean,
  )

  private companion object {
    const val RGB = 3
    const val RGBA = 4
    const val NOTHING_PASSED = "No frame reached the end of the readback chain."
    const val DRAWN_IN_SDR = "media3 drew the frame in SDR, so it holds no HDR light to read."

    /**
     * This texture as half float RGBA, row major from its top row.
     *
     * GL reads the bottom row first, so the rows are copied across in reverse.
     */
    fun GlTextureInfo.readHalves(): ShortArray {
      val rowHalves = width * RGBA
      val buffer = ByteBuffer.allocateDirect(rowHalves * height * Short.SIZE_BYTES).order(ByteOrder.nativeOrder())
      GlUtil.focusFramebufferUsingCurrentContext(fboId, width, height)
      GLES20.glReadPixels(0, 0, width, height, GLES20.GL_RGBA, GLES30.GL_HALF_FLOAT, buffer)
      GlUtil.checkGlError()

      val read = buffer.asShortBuffer()
      val halves = ShortArray(rowHalves * height)
      for (y in 0 until height) {
        read.position((height - 1 - y) * rowHalves)
        read.get(halves, y * rowHalves, rowHalves)
      }
      return halves
    }
  }
}
