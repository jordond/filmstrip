package dev.jordond.filmstrip.player

import android.content.Context
import android.graphics.Bitmap
import android.graphics.Color
import androidx.media3.common.MediaItem
import androidx.media3.common.VideoFrameProcessingException
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import dev.jordond.filmstrip.media3.internal.preview.Media3Readback
import dev.jordond.filmstrip.media3.internal.preview.Media3Span
import dev.jordond.filmstrip.player.internal.Media3StillFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
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

      withClue("a photo the chain drew still holds its pixels") { source.isRecycled.shouldBeTrue() }
    }

  @Test
  fun aPhotoTheChainCouldNotDrawIsFreedToo() =
    runTest {
      val source = flatPhoto()
      assertFailsWith<VideoFrameProcessingException> {
        Media3StillFrames(contractContext()).draw(source, listOf(BrokenEffect), Duration.ZERO)
      }

      withClue("a chain that failed stranded the photo it was handed") { source.isRecycled.shouldBeTrue() }
    }

  @Test
  fun aPositionThatFailedLeavesTheRunsPictureAlone() =
    runTest {
      val picture = flatPhoto()
      try {
        assertFailsWith<VideoFrameProcessingException> {
          Media3StillFrames(contractContext()).drawAt(picture, brokenReadback(), Duration.ZERO)
        }

        withClue("one failed position emptied the decode the whole run reads") { picture.isRecycled.shouldBeFalse() }
      } finally {
        picture.recycle()
      }
    }
}

private fun flatPhoto(): Bitmap =
  Bitmap.createBitmap(PHOTO_EDGE, PHOTO_EDGE, Bitmap.Config.ARGB_8888).apply { eraseColor(Color.WHITE) }

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
