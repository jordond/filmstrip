package dev.jordond.filmstrip.player.internal

import android.os.Handler
import androidx.media3.common.Player
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.mockk.every
import io.mockk.mockk
import kotlinx.coroutines.test.TestScope
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * A seek coordinator and the pin it moves over a mocked player, with the player's thread run inline unless deferred.
 *
 * Slots [HELD_AT] and [LATER_AT] show the sample written on slot [FIRST_AT], so a seek to either is handed to media3 at
 * [FIRST_AT].
 */
internal class HeldPinRig(
  scope: TestScope,
  var playing: Boolean,
  var scrubbing: Boolean = false,
  private val deferred: Boolean = false,
  restingMs: Long = FIRST_AT.inWholeMilliseconds,
  playbackDeferred: Boolean = false,
) {
  val pins = mutableListOf<Duration?>()
  var resting: Duration? = null
  var drawn: Duration? = null
  private val playbackQueued = ArrayDeque<Runnable>()

  /**
   * Runs what was posted to media3's playback thread, [turns] of it or all there is.
   */
  fun runPlayback(turns: Int = Int.MAX_VALUE) {
    var left = turns
    while (playbackQueued.isNotEmpty() && left-- > 0) playbackQueued.removeFirst().run()
  }

  private val playback =
    mockk<Handler>()
      .also {
        every { it.post(any()) } answers {
          playbackQueued += firstArg<Runnable>()
          true
        }
      }.takeIf { playbackDeferred }
  var platformMs: Long = FIRST_AT.inWholeMilliseconds
  private val queued = ArrayDeque<Runnable>()

  /**
   * Runs what was posted to the player's thread, and what that posts, as the thread gets to it.
   */
  fun runQueued() {
    while (queued.isNotEmpty()) queued.removeFirst().run()
  }

  private val handler =
    mockk<Handler>().also {
      every { it.post(any()) } answers {
        if (deferred) queued += firstArg<Runnable>() else firstArg<Runnable>().run()
        true
      }
    }

  val player =
    mockk<Player>().also {
      every { it.currentPosition } returns restingMs
      every { it.playbackState } returns Player.STATE_READY
      every { it.isPlaying } returns false
      every { it.playWhenReady } answers { playing }
      every { it.playWhenReady = any() } answers { playing = firstArg() }
      every { it.seekTo(any<Long>()) } returns Unit
    }

  private val composition =
    mockk<ResolvedComposition>().also {
      every { it.duration } returns LENGTH
      every { it.frameRate } returns RATE
    }

  val pin =
    Media3HeldPin(
      player = { player },
      frameRate = { RATE },
      firstSlotOf = ::firstSlotOf,
      pinSlot = { pins += it },
      drawn = { drawn },
    )

  val seeks =
    Media3SeekCoordinator(
      scope = scope,
      handler = handler,
      player = { player },
      playback = { playback },
      resolved = { composition },
      guard = WrapGuard(),
      platformPositionMs = { platformMs },
      loopWrap = { null },
      scrubbing = { this.scrubbing },
      showsFrames = { true },
      onIssued = { },
      onRedirected = { },
      firstSlotOf = ::firstSlotOf,
      pin = pin,
      resting = { resting },
      onRepresented = { },
    )

  private fun firstSlotOf(slot: Duration): Duration = if (slot in FIRST_AT..<AFTER_AT) FIRST_AT else slot

  companion object {
    const val RATE = 30
    const val CLOCK_BEHIND_MS = 40L
    const val ELSEWHERE_MS = 100L
    val LENGTH = 3.seconds
    val FIRST_AT = outputSlotTime(20, RATE)
    val HELD_AT = outputSlotTime(21, RATE)
    val LATER_AT = outputSlotTime(22, RATE)
    val AFTER_AT = outputSlotTime(23, RATE)
    val INTO_SLOT = 10.milliseconds
  }
}
