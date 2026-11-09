package dev.jordond.filmstrip.player.contract

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.player.PlayerConfig
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerFeature
import dev.jordond.filmstrip.player.ReadbackFrame
import dev.jordond.filmstrip.player.ReadbackResult
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.player.frameAt
import dev.jordond.filmstrip.player.readbackPositions
import io.kotest.matchers.comparables.shouldBeGreaterThan
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * Checks a surface drawing through [dev.jordond.filmstrip.player.VideoPlayer.readback] stays current on a paused
 * player.
 *
 * Reads the way that surface does, from a player whose load has not finished when it first asks, and must hold the
 * first frame with no play or seek. Then darkens the frame with a parameter edit, and the read following the edit must
 * carry it with the playhead standing still. Darkens because the fixtures are saturated and hide a brighten.
 *
 * On the browser and ffmpeg this read is what reaches the screen. Android and Apple present through a platform surface,
 * so here they only prove the read agrees, and cover that surface in their own tests.
 *
 * @param claimsLiveParameterRedraw Whether this backend says it redraws a parameter edit in place.
 */
internal suspend fun EngineUnderTest.surfaceReadStaysCurrentWhilePaused(
  scope: CoroutineScope,
  fixture: Composition,
  frameRate: Int,
  claimsLiveParameterRedraw: Boolean,
) = withEngine(engine) { recorder ->
  engine.features.supports(PlayerFeature.FrameReadback) shouldBe true
  engine.features.supports(PlayerFeature.LiveParameterRedraw) shouldBe claimsLiveParameterRedraw

  val held = HeldLoadEngine(engine)
  val player = Filmstrip { addPlayerEngineFactory { _, _ -> held } }.preview(fixture, PlayerConfig())
  var read: ReadbackResult? = null
  val surface =
    scope.launch {
      player.readbackPositions(READBACK_TICK).collectLatest { position ->
        read = player.readback.frameAt(position)
      }
    }
  try {
    delay(READBACK_TICK * 3)
    held.release()

    awaitContract("the surface to hold the first frame") { read is ReadbackResult.Success }
    val frame = read.shouldBeInstanceOf<ReadbackResult.Success>().frame
    frame.presentationTime shouldBeLessThanOrEqualTo outputSlotTime(1, frameRate)
    player.currentPosition() shouldBe Duration.ZERO
    recorder.lastState.playWhenReady shouldBe false

    val darkened =
      Composition(fixture.tracks, fixture.effects + Brightness(DARKENED), fixture.audio, fixture.fill)
    diff(fixture, darkened) shouldBe CompositionDiff.ParametersOnly
    val lit = frame.meanChannel()
    lit shouldBeGreaterThan MIN_LIT_MEAN

    player.setComposition(darkened).shouldBeInstanceOf<SetCompositionResult.Success>()
    awaitContract("the surface to read the darkened frame") {
      (read as? ReadbackResult.Success)?.let { it.frame.meanChannel() < lit * DARKENED_READS_BELOW } == true
    }
    player.currentPosition() shouldBe Duration.ZERO
    recorder.lastState.playWhenReady shouldBe false
  } finally {
    surface.cancel()
  }
}

private fun ReadbackFrame.meanChannel(): Double {
  var sum = 0L
  for (index in pixels.indices) {
    if (index % BYTES_PER_PIXEL != ALPHA_OFFSET) sum += pixels[index].toInt() and CHANNEL_MASK
  }
  return sum.toDouble() / (pixels.size / BYTES_PER_PIXEL * COLOUR_CHANNELS)
}

/**
 * [engine] holding back the first load it is handed until [release], so a host can act on a player whose graph is not
 * up yet.
 */
private class HeldLoadEngine(
  private val engine: PlayerEngine,
) : PlayerEngine by engine {
  private var released = false
  private var held: (() -> Unit)? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    if (released) return engine.setComposition(request, callback)
    held = { engine.setComposition(request, callback) }
    return Cancellable { }
  }

  fun release() {
    released = true
    held?.invoke()
    held = null
  }
}

// What a surface snaps the playhead to before it reads a frame.
private val READBACK_TICK = 33.milliseconds

// The gain the parameter edit applies, and how far below the lit frame's mean its read has to land.
private const val DARKENED = 0.28f
private const val DARKENED_READS_BELOW = 0.7

// A frame darker than this has nothing for a darkening edit to take away.
private const val MIN_LIT_MEAN = 8.0

private const val BYTES_PER_PIXEL = 4
private const val ALPHA_OFFSET = 3
private const val COLOUR_CHANNELS = 3
private const val CHANNEL_MASK = 0xFF
