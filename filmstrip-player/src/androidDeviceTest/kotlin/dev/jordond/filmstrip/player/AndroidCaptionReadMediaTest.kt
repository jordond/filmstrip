package dev.jordond.filmstrip.player

import android.graphics.Bitmap
import android.os.Handler
import android.os.Looper
import android.view.PixelCopy
import android.view.SurfaceView
import androidx.test.core.app.ActivityScenario
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.asTestFrame
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitFrame
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.seekAndWait
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.Media3PlayerEngine
import dev.jordond.filmstrip.player.internal.Media3PreviewPlanner
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.assertFramesSimilar
import dev.jordond.filmstrip.test.compareFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.ints.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.coroutineScope
import kotlinx.coroutines.delay
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import kotlin.coroutines.resume
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * A caption read back from a preview while the player, and any other read, draws the same edit.
 *
 * The player, a frame reader and a photo's processor each run a GL context of their own over one plan. A caption drawn
 * in one of them must not reach another, so these cover the three ways they overlap: the first read after the player's
 * first draw, a read after the player has drawn again, and reads landing while the player is drawing into a surface.
 */
class AndroidCaptionReadMediaTest {
  @Test
  fun theFirstReadOnAFreshEngineDrawsTheCaptionTheNextReadAndTheFileDraw() =
    contractTest { scope ->
      listOf(1.0, MIDDLE_SPEED).forEach { speed -> assertFirstRead(scope, speed) }
    }

  private suspend fun assertFirstRead(
    scope: CoroutineScope,
    speed: Double,
  ) {
    val edit = captionedEdit(speed)
    val at = middleSlotOf(edit)
    withLoadedEngine(scope, edit) { engine, _ ->
      val first = engine.readback.awaitFrame(at)
      val next = engine.readback.awaitFrame(at).asTestFrame()
      assertFramesSimilar(next, first.asTestFrame(), message = "the first read at $speed against the next")

      // A read that close to a frame's own time can land either side of it, for the reason the contract gives.
      val frameLength = outputLengthOf(1.seconds / FIXTURE_FRAME_RATE, speed)
      val shown = first.asTestFrame().bt709Luma()
      val written =
        listOf(first.presentationTime - frameLength, first.presentationTime, first.presentationTime + frameLength)
          .map { androidExportFrame(edit, it).bt709Luma() }
          .maxBy { compareFrames(it, shown).psnrDb }
      assertFramesSimilar(
        expected = written,
        actual = shown,
        minSsim = ENCODED_MIN_SSIM,
        message = "the first read at $speed against the file",
      )
    }
  }

  @Test
  fun aReadAfterThePlayerDrawsAgainStillDrawsTheCaption() =
    contractTest { scope ->
      val edit = captionedEdit(speed = 1.0)
      val at = middleSlotOf(edit)
      withLoadedEngine(scope, edit) { engine, recorder ->
        engine.readback.awaitFrame(at)
        val before = engine.readback.awaitFrame(at).asTestFrame()

        engine.seekAndWait(recorder, at + SEEK_AWAY)
        // The seek resolves when media3 accepts it, and the player draws after that.
        delay(SETTLE)

        assertFramesSimilar(
          before,
          engine.readback.awaitFrame(at).asTestFrame(),
          message = "a read after the player drew again against one before",
        )
      }
    }

  @Test
  fun readsWhileThePlayerDrawsLeaveTheCaptionOnTheSurfaceAndInEachRead() =
    runTest(timeout = TIMEOUT) {
      withContext(Dispatchers.Default) {
        val edit = captionedEdit(speed = SLOW_SPEED)
        ActivityScenario.launch(SurfaceHostActivity::class.java).use { scenario ->
          var host: SurfaceHostActivity? = null
          scenario.onActivity { activity -> host = activity }
          val activity = withClue("the host activity never started") { host.shouldNotBeNull() }
          withClue("the host window produced no surface") { activity.awaitSurface().shouldBeTrue() }

          val player = Filmstrip(contractContext()) { playerBackend() }.preview(edit)
          try {
            player.attachPreviewSurface(activity.surfaceView)
            awaitOrFail("the preview to become ready") { player.state.value.status == PlaybackStatus.Ready }
            player.play()
            delay(FIRST_DRAWS)

            assertCaptionHolds(player, activity.surfaceView, middleSlotOf(edit))
          } finally {
            player.close()
          }
        }
      }
    }

  private suspend fun assertCaptionHolds(
    player: VideoPlayer,
    surface: SurfaceView,
    at: Duration,
  ) = coroutineScope {
    val shown = surface.copyPixels().captionArea()
    val read = player.readback.awaitFrame(at).asTestFrame()
    val reads =
      async {
        repeat(READS) {
          assertFramesSimilar(
            read,
            player.readback.awaitFrame(at).asTestFrame(),
            message = "read $it while the player drew against the frame before them",
          )
        }
      }

    var copies = 0
    while (reads.isActive) {
      assertFramesSimilar(
        shown,
        surface.copyPixels().captionArea(),
        minPsnrDb = SURFACE_MIN_PSNR_DB,
        minSsim = SURFACE_MIN_SSIM,
        message = "copy $copies off the surface while frames were read",
      )
      copies++
    }
    reads.await()
    withClue("the reads ended before any copy off the surface") { copies shouldBeGreaterThan 0 }
  }

  private suspend fun withLoadedEngine(
    scope: CoroutineScope,
    edit: EditComposition,
    body: suspend (Media3PlayerEngine, ContractRecorder) -> Unit,
  ) {
    val engine = Media3PlayerEngine(scope, contractContext(), Media3PreviewPlanner(CONTRACT_COMPONENTS), PlayerConfig())
    withEngine(engine) { recorder ->
      engine.setQualityPolicy(PreviewQualityPolicy.Full)
      engine.awaitComposition(edit).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the edit to be presentable") { recorder.lastState.hasComposition }
      recorder.lastState.duration shouldBe edit.duration
      body(engine, recorder)
    }
  }

  private fun middleSlotOf(edit: EditComposition): Duration {
    val slot = outputSlotOf(checkNotNull(edit.duration) / 2, FIXTURE_FRAME_RATE)
    return outputSlotTime(slot, FIXTURE_FRAME_RATE)
  }

  private fun captionedEdit(speed: Double): EditComposition =
    EditComposition(
      tracks = listOf(Track(listOf(Clip(androidFixtureClip(), TRIM, speed = speed)))),
      effects = listOf(TextOverlay(text = "Speed", anchor = Anchor.Center)),
    )

  // The middle of the frame, where the caption is anchored, so a caption drawn wrong is most of what this reads.
  private fun TestFrame.captionArea(): TestFrame {
    val width = size.width / 4
    val height = size.height / 4
    val left = (size.width - width) / 2
    val top = (size.height - height) / 2
    val area = ByteArray(width * height * CHANNELS)
    for (row in 0 until height) {
      val from = ((top + row) * size.width + left) * CHANNELS
      pixels.copyInto(area, row * width * CHANNELS, from, from + width * CHANNELS)
    }
    return TestFrame(area, Size(width, height))
  }

  private suspend fun SurfaceView.copyPixels(): TestFrame {
    val bitmap = Bitmap.createBitmap(FIXTURE_FRAME.width, FIXTURE_FRAME.height, Bitmap.Config.ARGB_8888)
    val status =
      suspendCancellableCoroutine { continuation ->
        PixelCopy.request(this, bitmap, { result -> continuation.resume(result) }, Handler(Looper.getMainLooper()))
      }
    if (status != PixelCopy.SUCCESS) fail("PixelCopy off the surface answered $status")

    val colors = IntArray(bitmap.width * bitmap.height)
    bitmap.getPixels(colors, 0, bitmap.width, 0, 0, bitmap.width, bitmap.height)
    val pixels = ByteArray(colors.size * CHANNELS)
    for (index in colors.indices) {
      val color = colors[index]
      val base = index * CHANNELS
      pixels[base] = (color shr RED_SHIFT).toByte()
      pixels[base + 1] = (color shr GREEN_SHIFT).toByte()
      pixels[base + 2] = color.toByte()
      pixels[base + 3] = OPAQUE
    }
    return TestFrame(pixels, FIXTURE_FRAME)
  }

  private suspend fun awaitOrFail(
    description: String,
    condition: () -> Boolean,
  ) {
    val met =
      withTimeoutOrNull(BUDGET) {
        while (!condition()) delay(POLL)
        true
      }
    if (met != true) fail("Timed out after $BUDGET waiting for $description.")
  }

  private companion object {
    const val MIDDLE_SPEED = 0.7

    // Slow enough for the reads below to finish while the clip still plays.
    const val SLOW_SPEED = 0.1
    const val READS = 14

    val TRIM = TimeRange.of(200.milliseconds, 1_300.milliseconds)
    val SEEK_AWAY = 100.milliseconds
    val SETTLE = 700.milliseconds
    val FIRST_DRAWS = 300.milliseconds

    val TIMEOUT = 3.minutes
    val BUDGET = 30.seconds
    val POLL = 50.milliseconds

    // Two copies off one surface a few frames apart. The picture moving behind a right caption reads 35 dB and 0.90,
    // and a caption drawn from another graph's texture name reads 13 dB and 0.73.
    const val SURFACE_MIN_PSNR_DB = 28.0
    const val SURFACE_MIN_SSIM = 0.8

    const val CHANNELS = 4
    const val RED_SHIFT = 16
    const val GREEN_SHIFT = 8
    const val OPAQUE = 0xFF.toByte()
  }
}
