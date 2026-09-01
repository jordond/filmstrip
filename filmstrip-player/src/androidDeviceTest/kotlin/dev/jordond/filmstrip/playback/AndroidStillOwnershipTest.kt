package dev.jordond.filmstrip.playback

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media3.internal.Media3Readback
import dev.jordond.filmstrip.media3.internal.Media3Span
import dev.jordond.filmstrip.playback.internal.Media3StillFrames
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Who owns the bitmap a still is drawn from.
 *
 * A photo enters the chain as a full size decode, so one that nothing frees is a leaked picture per
 * position a strip scrolls over. media3 recycles what it drew and drops what it did not, which
 * leaves every failing path to the caller.
 */
class AndroidStillOwnershipTest {
  @Test
  fun aPhotoTheChainDrewIsFreed() =
    runTest {
      val source = flatPhoto()
      val drawn = Media3StillFrames(contractContext()).draw(source, emptyList(), Duration.ZERO)
      drawn.recycle()

      assertTrue(source.isRecycled, "a photo the chain drew still holds its pixels")
    }

  @Test
  fun aPhotoTheChainCouldNotDrawIsFreedToo() =
    runTest {
      val source = flatPhoto()
      assertFailsWith<VideoFrameProcessingException> {
        Media3StillFrames(contractContext()).draw(source, listOf(BrokenEffect), Duration.ZERO)
      }

      assertTrue(source.isRecycled, "a chain that failed stranded the photo it was handed")
    }

  @Test
  fun aPositionThatFailedLeavesTheRunsPictureAlone() =
    runTest {
      val picture = flatPhoto()
      try {
        assertFailsWith<VideoFrameProcessingException> {
          Media3StillFrames(contractContext()).drawAt(picture, brokenReadback(), Duration.ZERO)
        }

        assertFalse(picture.isRecycled, "one failed position emptied the decode the whole run reads")
      } finally {
        picture.recycle()
      }
    }
}

private fun flatPhoto(): Bitmap =
  Bitmap.createBitmap(PHOTO_EDGE, PHOTO_EDGE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

@OptIn(InternalFilmstripApi::class)
private fun brokenReadback(): Media3Readback =
  Media3Readback(
    span = Media3Span(Duration.ZERO, PHOTO_LENGTH, MediaItem.EMPTY, emptyList(), still = true),
    effects = listOf(BrokenEffect),
  )

// A stage that cannot be built, which fails a draw the same way a shader that will not compile does.
private object BrokenEffect : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = throw VideoFrameProcessingException(BROKEN)
}

private const val BROKEN = "the test's own effect refuses to build"
private const val PHOTO_EDGE = 64
