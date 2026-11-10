package dev.jordond.filmstrip.test

import android.graphics.Bitmap
import android.net.Uri
import androidx.media3.common.MediaItem
import androidx.media3.exoplayer.SeekParameters
import androidx.media3.inspector.frame.FrameExtractor
import androidx.test.platform.app.InstrumentationRegistry
import dev.jordond.filmstrip.InternalFilmstripApi
import java.io.File
import java.util.concurrent.TimeUnit
import kotlin.time.Duration

private const val FRAME_TIMEOUT_SECONDS = 60L

/**
 * Decodes the frame of [video] at [at] as an ARGB_8888 bitmap through media3's `FrameExtractor`.
 *
 * The extractor decodes with `MediaCodec` and converts to RGB on the GPU, so it runs on every API level media3 does.
 * It takes whole milliseconds and seeks exactly, so [at] rounds down and the frame returned is the first one presented
 * at or after that millisecond. An [at] that sits on a frame time reads that frame.
 *
 * @throws java.util.concurrent.TimeoutException When the frame does not decode within the timeout.
 * @throws java.util.concurrent.ExecutionException When the extractor fails to decode the frame.
 * @throws IllegalStateException When the frame comes back in another config.
 */
@InternalFilmstripApi
public fun eightBitFrameThroughPlayerAt(
  video: File,
  at: Duration,
): Bitmap {
  val context = InstrumentationRegistry.getInstrumentation().targetContext
  val extractor =
    FrameExtractor
      .Builder(context, MediaItem.fromUri(Uri.fromFile(video)))
      .setSeekParameters(SeekParameters.EXACT)
      .build()
  return try {
    val frame = extractor.getFrame(at.inWholeMilliseconds).get(FRAME_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    check(frame.bitmap.config == Bitmap.Config.ARGB_8888) {
      "the frame at $at came back as ${frame.bitmap.config}, not ARGB_8888"
    }
    frame.bitmap
  } finally {
    extractor.close()
  }
}
