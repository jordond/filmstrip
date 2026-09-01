package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import dev.jordond.filmstrip.compose.ScrubState
import dev.jordond.filmstrip.compose.ui.FilmstripTimelineDefaults
import dev.jordond.filmstrip.compose.ui.TimelineState
import kotlinx.coroutines.launch
import kotlin.time.Duration

/**
 * Keyboard control over a timeline: arrows seek, home and end jump to the ends, and plus and minus step the zoom
 * ladder.
 *
 * Left and right seek by [step], and by five times [step] with shift held. A held arrow repeats through
 * [ArrowScrubGesture] rather than issuing a settling seek per repeat, which is what keeps the scrub relaxed for the
 * whole burst instead of cancelling itself dozens of times a second. Zooming holds the middle of the viewport still,
 * which is the one focal point a key press has. A null [scrub] leaves the seeking keys doing nothing, matching how
 * [dev.jordond.filmstrip.compose.ui.FilmstripTimeline] treats a null scrub as a timeline a drag does not seek either;
 * the zoom keys work regardless.
 *
 * This makes the modified element focusable, but does not request focus itself: composing it is not enough to
 * receive a key press. A host gives it focus the ordinary way, chaining `Modifier.focusRequester` ahead of this and
 * calling `requestFocus()` on it from wherever the host decides the timeline should become the keyboard's target,
 * such as a click on it. A component that took focus on composition would steal it from whatever the host had
 * focused before.
 *
 * @param state The timeline the zoom keys step.
 * @param scrub The protocol a seek is driven through, or null for a timeline the keys do not seek.
 * @param position Where the playhead sits, read once when an arrow burst begins so a repeat inside it advances from
 * its own last target rather than a clock the scrub has not caught up to yet.
 * @param step How far left and right seek. Shift multiplies it by five.
 * @param enabled Whether the keys are read at all.
 */
public fun Modifier.timelineKeys(
  state: TimelineState,
  scrub: ScrubState? = null,
  position: () -> Duration,
  step: Duration = FilmstripTimelineDefaults.KeyStep,
  enabled: Boolean = true,
): Modifier = if (!enabled) this else focusable().then(TimelineKeysElement(state, scrub, position, step))

internal class TimelineKeysElement(
  private val state: TimelineState,
  private val scrub: ScrubState?,
  private val position: () -> Duration,
  private val step: Duration,
) : ModifierNodeElement<TimelineKeysNode>() {
  override fun create(): TimelineKeysNode = TimelineKeysNode(state, scrub, position, step)

  override fun update(node: TimelineKeysNode) {
    node.update(state, scrub, position, step)
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "timelineKeys"
    properties["state"] = state
    properties["scrub"] = scrub
    properties["position"] = position
    properties["step"] = step
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is TimelineKeysElement) return false

    return state == other.state &&
      scrub == other.scrub &&
      position == other.position &&
      step == other.step
  }

  override fun hashCode(): Int {
    var result = state.hashCode()
    result = 31 * result + scrub.hashCode()
    result = 31 * result + position.hashCode()
    result = 31 * result + step.hashCode()
    return result
  }
}

internal class TimelineKeysNode(
  private var state: TimelineState,
  private var scrub: ScrubState?,
  private var position: () -> Duration,
  private var step: Duration,
) : Modifier.Node(),
  KeyInputModifierNode {
  private var arrowScrub: ArrowScrubGesture? = null
  private var idleSettle: ArrowIdleSettle? = null

  fun update(
    state: TimelineState,
    scrub: ScrubState?,
    position: () -> Duration,
    step: Duration,
  ) {
    this.state = state
    this.position = position
    this.step = step

    if (this.scrub != scrub) {
      endGesture()
      this.scrub = scrub
    }
  }

  override fun onDetach() {
    // A gesture left open by a KeyUp the platform never delivers, or by focus moving away
    // mid-press, would leave the scrub permanently relaxed rather than merely a little late to
    // settle. Ending it here, on top of the idle timeout, is the same belt-and-braces the timeout
    // itself is for.
    endGesture()
  }

  override fun onPreKeyEvent(event: KeyEvent): Boolean = false

  override fun onKeyEvent(event: KeyEvent): Boolean =
    when (event.type) {
      KeyEventType.KeyDown -> onKeyDown(event)
      KeyEventType.KeyUp -> onKeyUp(event)
      else -> false
    }

  private fun onKeyDown(event: KeyEvent): Boolean =
    when (event.key) {
      Key.ZoomIn, Key.Plus, Key.Equals, Key.NumPadAdd -> {
        coroutineScope.launch { state.zoomIn(state.viewportCenterX()) }
        true
      }
      Key.ZoomOut, Key.Minus, Key.NumPadSubtract -> {
        coroutineScope.launch { state.zoomOut(state.viewportCenterX()) }
        true
      }
      Key.DirectionLeft, Key.DirectionRight -> {
        val gesture = scrubGesture()
        if (gesture == null) {
          false
        } else {
          val duration = state.scale.duration
          val arrowStep = if (event.isShiftPressed) step * SHIFT_STEP_MULTIPLIER else step
          val delta = if (event.key == Key.DirectionLeft) -arrowStep else arrowStep

          gesture.advance(delta, position) { it.coerceIn(Duration.ZERO, duration) }
          idleSettle?.ping()
          true
        }
      }
      Key.MoveHome, Key.MoveEnd -> {
        val scrubbing = scrub
        if (scrubbing == null) {
          false
        } else {
          val target = if (event.key == Key.MoveHome) Duration.ZERO else state.scale.duration

          scrubbing.onScrubStart()
          scrubbing.onScrubTo(target)
          scrubbing.onScrubEnd()
          true
        }
      }
      else -> {
        false
      }
    }

  private fun onKeyUp(event: KeyEvent): Boolean =
    when (event.key) {
      Key.DirectionLeft, Key.DirectionRight -> {
        val gesture = scrubGesture()
        if (gesture == null) {
          false
        } else {
          idleSettle?.cancel()
          gesture.end()
          true
        }
      }
      else -> {
        false
      }
    }

  /**
   * The burst gesture and its idle timer over the current [scrub], built the first time a key asks for them.
   */
  private fun scrubGesture(): ArrowScrubGesture? {
    arrowScrub?.let { return it }
    val scrubbing = scrub ?: return null

    val gesture =
      ArrowScrubGesture(onStart = scrubbing::onScrubStart, onSeek = scrubbing::onScrubTo, onEnd = scrubbing::onScrubEnd)
    arrowScrub = gesture
    idleSettle = ArrowIdleSettle(coroutineScope, gesture)
    return gesture
  }

  private fun endGesture() {
    idleSettle?.cancel()
    arrowScrub?.end()
    idleSettle = null
    arrowScrub = null
  }
}

/**
 * The middle of the timeline's viewport, in viewport pixels, held still by a keyboard zoom step.
 */
private fun TimelineState.viewportCenterX(): Float = listState.layoutInfo.viewportSize.width / 2f

/**
 * How many times [FilmstripTimelineDefaults.KeyStep] a shifted arrow seeks by.
 */
private const val SHIFT_STEP_MULTIPLIER = 5
