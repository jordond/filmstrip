package dev.jordond.filmstrip.compose.ui.component

import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.platform.testTag
import androidx.compose.ui.test.ExperimentalTestApi
import androidx.compose.ui.test.onNodeWithTag
import androidx.compose.ui.test.onRoot
import androidx.compose.ui.test.performTouchInput
import androidx.compose.ui.test.v2.runComposeUiTest
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.ui.CLIP_LENGTH
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.testComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackEvent
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PlayerState
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.VideoPlayer
import io.kotest.matchers.comparables.shouldBeLessThan
import kotlin.math.absoluteValue
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Every stage here is given a 400x400 box on a density of one, so the expected figures are the letterbox of a known
 * aspect inside a square of 400 pixels.
 */
@OptIn(ExperimentalTestApi::class)
class VideoStageTest {
  @Test
  fun `without a player the stage takes the aspect it was given`() =
    runComposeUiTest {
      var stage: Stage? = null

      setContent {
        StageBox {
          VideoStage(
            player = null,
            outputAspect = 2f,
          ) { stage = record() }
        }
      }
      waitForIdle()

      val laidOut = stage!!
      laidOut.aspect shouldBeCloseTo 2f
      laidOut.width shouldBeCloseTo 400f
      laidOut.height shouldBeCloseTo 200f
    }

  @Test
  fun `the aspect a player is presenting wins over the one the edit asks for`() =
    runComposeUiTest {
      var stage: Stage? = null

      setContent {
        val player = playerPresenting(Size(1920, 1080))
        StageBox {
          VideoStage(
            player = player,
            outputAspect = 1f,
          ) { stage = record() }
        }
      }
      waitForIdle()

      val laidOut = stage!!
      laidOut.aspect shouldBeCloseTo WIDE_ASPECT
      laidOut.width shouldBeCloseTo 400f
      laidOut.height shouldBeCloseTo 400f / WIDE_ASPECT
    }

  @Test
  fun `a player presenting nothing yet leaves the edit's aspect in place`() =
    runComposeUiTest {
      var stage: Stage? = null

      setContent {
        val player = playerPresenting(Size(0, 0))
        StageBox {
          VideoStage(
            player = player,
            outputAspect = 2f,
          ) { stage = record() }
        }
      }
      waitForIdle()

      val laidOut = stage!!
      laidOut.aspect shouldBeCloseTo 2f
      laidOut.height shouldBeCloseTo 200f
    }

  @Test
  fun `the fallback is drawn where there is no player`() =
    runComposeUiTest {
      setContent {
        StageBox {
          VideoStage(
            player = null,
            outputAspect = 2f,
            fallback = { Box(Modifier.fillMaxSize().testTag(FALLBACK)) },
          )
        }
      }
      waitForIdle()

      onNodeWithTag(FALLBACK).assertExists()
    }

  @Test
  fun `a player takes the fallback's place`() =
    runComposeUiTest {
      setContent {
        val player = playerPresenting(Size(1920, 1080))
        StageBox {
          VideoStage(
            player = player,
            outputAspect = 2f,
            fallback = { Box(Modifier.fillMaxSize().testTag(FALLBACK)) },
          )
        }
      }
      waitForIdle()

      onNodeWithTag(FALLBACK).assertDoesNotExist()
    }

  @Test
  fun `an aspect past the stage's range letterboxes the picture inside the box it will lay out`() =
    runComposeUiTest {
      var stage: Stage? = null

      // Ten to one, against a stage that lays no box out wider than five to one.
      setContent {
        StageBox {
          VideoStage(
            player = null,
            outputAspect = 10f,
          ) { stage = record() }
        }
      }
      waitForIdle()

      val laidOut = stage!!
      laidOut.width shouldBeCloseTo 400f
      // The box stops at five to one, so it is 80 tall, and the picture fills 40 in the middle of it.
      laidOut.height shouldBeCloseTo 40f
      laidOut.top shouldBeCloseTo 20f
    }

  @Test
  fun `a crop overlay in the stage drags against the stage's own frame`() =
    runComposeUiTest {
      var rect by mutableStateOf(NormalizedRect(0.2f, 0.2f, 0.8f, 0.8f))

      setContent {
        StageBox {
          VideoStage(player = null, outputAspect = 1f) {
            CropOverlay(rect = rect, onRectChange = { rect = it })
          }
        }
      }

      // The stage is the whole 400x400 box, so the crop's top-left corner sits at (80, 80), exactly
      // where it sits for the overload that measures its own letterbox.
      onRoot().performTouchInput {
        down(Offset(80f, 80f))
        advanceEventTime(16L)
        moveTo(Offset(95f, 95f))
      }
      waitForIdle()
      val afterSlop = rect

      onRoot().performTouchInput {
        advanceEventTime(16L)
        moveBy(Offset(20f, 20f))
        up()
      }
      waitForIdle()

      (rect.left - afterSlop.left) shouldBeCloseTo 0.05f
      (rect.top - afterSlop.top) shouldBeCloseTo 0.05f
      rect.right shouldBeCloseTo 0.8f
    }

  private companion object {
    const val FALLBACK = "fallback"
    const val WIDE_ASPECT = 1920f / 1080f

    // Every expected value here is hand-computed independently of the code under test, and the
    // mapping runs through Float, so the arrival is compared within a small tolerance.
    const val EPSILON = 0.01f

    infix fun Float.shouldBeCloseTo(expected: Float) {
      (this - expected).absoluteValue shouldBeLessThan EPSILON
    }
  }
}

/**
 * What a stage was laid out to, read outside composition so an assertion can look at it.
 */
private class Stage(
  val aspect: Float,
  val width: Float,
  val height: Float,
  val top: Float,
)

private fun VideoStageScope.record(): Stage =
  Stage(
    aspect = aspect,
    width = frame.contentRect.width,
    height = frame.contentRect.height,
    top = frame.contentRect.top,
  )

@Composable
private fun StageBox(content: @Composable () -> Unit) {
  Box(Modifier.size(BOX_SIZE)) { content() }
}

/**
 * A player whose engine reports [outputSize] and nothing else.
 */
@Composable
private fun playerPresenting(outputSize: Size): VideoPlayer =
  rememberFilmstripPlayer(
    previews = Filmstrip { addPlayerEngineFactory { _, _ -> StageEngine(outputSize) } },
    composition = testComposition(),
  )

/**
 * An engine with no platform under it, reporting one output size as soon as anything listens.
 */
private class StageEngine(
  outputSize: Size,
) : PlayerEngine {
  private val info =
    PreviewInfo(
      outputSize = outputSize,
      renderScale = 1f,
      parity = EffectParity.Exact,
      parityNotes = emptyList(),
      fidelity = emptyList(),
    )

  override val id: String = "filmstrip.compose-ui-stage-test"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    callback.onResult(SetCompositionResult.Success(CLIP_LENGTH))
    return Cancellable { }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listener.onStateChanged(READY)
    listener.onPreviewInfo(info)
    listener.onEvent(PlaybackEvent.FirstFrameRendered)
    return Cancellable { }
  }

  override fun play(): Unit = Unit

  override fun pause(): Unit = Unit

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun beginScrub(): Unit = Unit

  override fun endScrub(): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit

  override fun dispose(): Unit = Unit

  private companion object {
    val READY = PlayerState(PlaybackStatus.Ready, false, false, false, CLIP_LENGTH)
  }
}

private val BOX_SIZE = 400.dp
