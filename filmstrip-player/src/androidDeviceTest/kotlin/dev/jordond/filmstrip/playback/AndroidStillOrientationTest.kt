package dev.jordond.filmstrip.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.playback.contract.asTestFrame
import dev.jordond.filmstrip.playback.contract.awaitComposition
import dev.jordond.filmstrip.playback.contract.awaitContract
import dev.jordond.filmstrip.playback.contract.awaitFrame
import dev.jordond.filmstrip.playback.contract.contractTest
import dev.jordond.filmstrip.playback.contract.withEngine
import dev.jordond.filmstrip.playback.internal.Media3PlayerEngine
import dev.jordond.filmstrip.playback.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailPlanner
import dev.jordond.filmstrip.playback.internal.Media3ThumbnailSource
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.suspendCancellableCoroutine
import java.io.File
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail

/**
 * A photo read back as a strip tile and as a preview frame, which has to come back the way up it was painted.
 *
 * The photo carries a different colour in each quarter, so a picture turned upside down reads the bottom colours at
 * the top and one mirrored reads the end colours at the start. Every quarter is read at its own middle, a quarter and
 * three quarters of the way across and down, and every expected colour is the one that quarter was painted.
 */
class AndroidStillOrientationTest {
  @Test
  fun aPhotoThumbnailKeepsItsTopAtTheTopAndItsStartAtTheStart() =
    contractTest { scope ->
      val source: ThumbnailSource =
        Media3ThumbnailSource(
          scope = scope,
          context = contractContext(),
          planner = Media3ThumbnailPlanner(CONTRACT_COMPONENTS),
        )
      val request =
        ThumbnailRequest(quarteredComposition(), PROBE, FIXTURE_FRAME.height, REVISION, precise = true)
      val thumbnail = source.awaitThumbnail(request)

      try {
        val frame = TestFrame(thumbnail.image.toRgba8888(), Size(thumbnail.image.widthPx, thumbnail.image.heightPx))
        frame.size shouldBe FIXTURE_FRAME
        frame.assertQuartersUpright()
      } finally {
        thumbnail.image.close()
      }
    }

  @Test
  fun aPhotoPreviewFrameKeepsItsTopAtTheTopAndItsStartAtTheStart() =
    contractTest { scope ->
      val engine =
        Media3PlayerEngine(
          parent = scope,
          context = contractContext(),
          planner = Media3PreviewPlanner(CONTRACT_COMPONENTS),
          config = PlayerConfig(),
        )
      withEngine(engine) { recorder ->
        engine.awaitComposition(quarteredComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
        awaitContract("the preview to be presentable") { recorder.lastState.hasComposition }

        val frame = engine.readback.awaitFrame(PROBE).asTestFrame()
        frame.size shouldBe FIXTURE_FRAME
        frame.assertQuartersUpright()
      }
    }

  private fun TestFrame.assertQuartersUpright() {
    colourAt(NEAR, NEAR) shouldBeCloseTo TOP_START
    colourAt(FAR, NEAR) shouldBeCloseTo TOP_END
    colourAt(NEAR, FAR) shouldBeCloseTo BOTTOM_START
    colourAt(FAR, FAR) shouldBeCloseTo BOTTOM_END
  }

  private fun quarteredComposition(): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.Image(ImageSource.of(quarteredPhotoFile().path), PHOTO_LENGTH))))),
    )

  // Painted top down and start to end, the way a Bitmap's rows and columns run.
  private fun quarteredPhotoFile(): File {
    val file = File(contractContext().cacheDir, PHOTO_NAME)
    if (file.exists()) return file

    val width = FIXTURE_FRAME.width.toFloat()
    val height = FIXTURE_FRAME.height.toFloat()
    val bitmap = Bitmap.createBitmap(FIXTURE_FRAME.width, FIXTURE_FRAME.height, Bitmap.Config.ARGB_8888)
    val canvas = Canvas(bitmap)
    val paint = Paint()
    listOf(
      Triple(0f, 0f, TOP_START),
      Triple(width / 2, 0f, TOP_END),
      Triple(0f, height / 2, BOTTOM_START),
      Triple(width / 2, height / 2, BOTTOM_END),
    ).forEach { (left, top, colour) ->
      paint.color = Color.rgb(colour.first, colour.second, colour.third)
      canvas.drawRect(left, top, left + width / 2, top + height / 2, paint)
    }
    file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
    bitmap.recycle()
    return file
  }

  private companion object {
    const val REVISION = 300L
    const val PHOTO_NAME = "filmstrip-player-quartered-photo.png"
    const val PNG_QUALITY = 100

    val PROBE = PHOTO_LENGTH * PHOTO_FRACTION

    // The middle of the start or top half, and of the end or bottom half.
    const val NEAR = 0.25f
    const val FAR = 0.75f

    // Every two quarters differ by 0xA0 on at least one channel, so a flip, a mirror or both reads as a different
    // quarter.
    val TOP_START = Triple(0xD0, 0x30, 0x30)
    val TOP_END = Triple(0x30, 0xD0, 0x30)
    val BOTTOM_START = Triple(0x30, 0x30, 0xD0)
    val BOTTOM_END = Triple(0xD0, 0xD0, 0x30)
  }
}

/**
 * The colour at a point given as a fraction of this frame's width and height.
 */
private fun TestFrame.colourAt(
  fx: Float,
  fy: Float,
): Triple<Int, Int, Int> {
  val x = (fx * size.width).toInt().coerceIn(0, size.width - 1)
  val y = (fy * size.height).toInt().coerceIn(0, size.height - 1)
  val offset = (y * size.width + x) * CHANNELS
  return Triple(
    pixels[offset].toInt() and BYTE_MASK,
    pixels[offset + 1].toInt() and BYTE_MASK,
    pixels[offset + 2].toInt() and BYTE_MASK,
  )
}

private suspend fun ThumbnailSource.awaitThumbnail(request: ThumbnailRequest): ThumbnailResult.Success =
  suspendCancellableCoroutine { continuation ->
    val handle: Cancellable =
      requestThumbnail(request) { result ->
        if (!continuation.isActive) return@requestThumbnail
        when (result) {
          is ThumbnailResult.Success -> continuation.resume(result)
          is ThumbnailResult.Failure -> continuation.resume(fail("the thumbnail failed: ${result.error.message}"))
        }
      }
    continuation.invokeOnCancellation { handle.cancel() }
  }

private const val CHANNELS = 4
private const val BYTE_MASK = 0xFF
