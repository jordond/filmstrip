package dev.jordond.filmstrip.test

import android.graphics.Bitmap
import android.media.MediaMetadataRetriever
import android.os.Build
import dev.jordond.filmstrip.InternalFilmstripApi
import org.junit.AssumptionViolatedException
import java.io.File
import kotlin.time.Duration

/**
 * Decodes the frame of [video] nearest [at] as an ARGB_8888 bitmap.
 *
 * Asked without bitmap params, `MediaMetadataRetriever` handed back RGB_565 on API 30, which moved a mid grey of 128
 * to 132, 130, 132. `MediaMetadataRetriever.BitmapParams` arrived in API 30, so an older device skips the calling test.
 * The skip throws JUnit 4's `AssumptionViolatedException`, which an Android instrumentation test classpath carries.
 *
 * @throws IllegalArgumentException When the retriever finds no frame at [at].
 * @throws IllegalStateException When the frame comes back in any other config.
 */
@InternalFilmstripApi
public fun eightBitFrameAt(
  video: File,
  at: Duration,
): Bitmap {
  if (Build.VERSION.SDK_INT < Build.VERSION_CODES.R) {
    throw AssumptionViolatedException("an eight-bit frame read needs MediaMetadataRetriever.BitmapParams, API 30")
  }
  val params = MediaMetadataRetriever.BitmapParams().apply { preferredConfig = Bitmap.Config.ARGB_8888 }
  val retriever = MediaMetadataRetriever()
  return try {
    retriever.setDataSource(video.path)
    val frame =
      requireNotNull(retriever.getFrameAtTime(at.inWholeMicroseconds, MediaMetadataRetriever.OPTION_CLOSEST, params)) {
        "no frame at $at in $video"
      }
    check(frame.config == Bitmap.Config.ARGB_8888) { "the frame at $at came back as ${frame.config}, not ARGB_8888" }
    frame
  } finally {
    retriever.release()
  }
}
