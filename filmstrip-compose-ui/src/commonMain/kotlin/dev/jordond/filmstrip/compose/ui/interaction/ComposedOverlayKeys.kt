package dev.jordond.filmstrip.compose.ui.interaction

import androidx.compose.foundation.focusable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.input.key.Key
import androidx.compose.ui.input.key.KeyEvent
import androidx.compose.ui.input.key.KeyEventType
import androidx.compose.ui.input.key.KeyInputModifierNode
import androidx.compose.ui.input.key.isShiftPressed
import androidx.compose.ui.input.key.key
import androidx.compose.ui.input.key.type
import androidx.compose.ui.node.ModifierNodeElement
import androidx.compose.ui.platform.InspectorInfo
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.component.stepped

/**
 * Keyboard control over a composed overlay element: arrows move it, plus and minus scale it, and the bracket keys
 * turn it.
 *
 * An arrow moves the centre by [ComposedOverlayDefaults.NUDGE] of the frame, and by
 * [ComposedOverlayDefaults.COARSE_NUDGE] with Shift held. Plus and minus multiply and divide the scale by
 * [ComposedOverlayDefaults.WHEEL_STEP]. The closing bracket turns the element clockwise by
 * [ComposedOverlayDefaults.KEY_TURN_STEP] degrees and the opening bracket turns it back, each by
 * [ComposedOverlayDefaults.COARSE_KEY_TURN_STEP] with Shift held. Every press is held to the limits a drag and a pinch
 * are held to, and a held key repeats. Any other key passes through, so deleting, deselecting and undo stay with the
 * host.
 *
 * This makes the modified element focusable, but does not request focus itself. A host chains
 * `Modifier.focusRequester` ahead of this and calls `requestFocus()` when it wants the keys to reach the element, such
 * as when one is selected.
 *
 * ```
 * Box(
 *   Modifier
 *     .focusRequester(focus)
 *     .composedOverlayKeys(transform, onTransformChange = { transform = it }, enabled = selected),
 * ) {
 *   VideoStage(player = player, outputAspect = outputAspect) {
 *     ComposedOverlay(transform, canvas, outputSize, onTransformChange = { transform = it }, selected = selected) {
 *       Text("Day one")
 *     }
 *   }
 * }
 * ```
 *
 * @param transform Where the element the keys move sits now.
 * @param onTransformChange Called with the transform a key press asks for.
 * @param enabled Whether the keys are read at all.
 */
public fun Modifier.composedOverlayKeys(
  transform: OverlayTransform,
  onTransformChange: (OverlayTransform) -> Unit,
  enabled: Boolean = true,
): Modifier = if (!enabled) this else focusable().then(ComposedOverlayKeysElement(transform, onTransformChange))

internal class ComposedOverlayKeysElement(
  private val transform: OverlayTransform,
  private val onTransformChange: (OverlayTransform) -> Unit,
) : ModifierNodeElement<ComposedOverlayKeysNode>() {
  override fun create(): ComposedOverlayKeysNode = ComposedOverlayKeysNode(transform, onTransformChange)

  override fun update(node: ComposedOverlayKeysNode) {
    node.transform = transform
    node.onTransformChange = onTransformChange
  }

  override fun InspectorInfo.inspectableProperties() {
    name = "composedOverlayKeys"
    properties["transform"] = transform
    properties["onTransformChange"] = onTransformChange
  }

  override fun equals(other: Any?): Boolean {
    if (this === other) return true
    if (other !is ComposedOverlayKeysElement) return false

    return transform == other.transform && onTransformChange == other.onTransformChange
  }

  override fun hashCode(): Int = 31 * transform.hashCode() + onTransformChange.hashCode()
}

internal class ComposedOverlayKeysNode(
  var transform: OverlayTransform,
  var onTransformChange: (OverlayTransform) -> Unit,
) : Modifier.Node(),
  KeyInputModifierNode {
  override fun onPreKeyEvent(event: KeyEvent): Boolean = false

  override fun onKeyEvent(event: KeyEvent): Boolean {
    val next = stepFor(event) ?: return false
    if (event.type == KeyEventType.KeyDown) onTransformChange(next)
    // The key up of a bound key is taken too, so a host does not see half a press.
    return event.type == KeyEventType.KeyDown || event.type == KeyEventType.KeyUp
  }

  private fun stepFor(event: KeyEvent): OverlayTransform? {
    val coarse = event.isShiftPressed
    val nudge = if (coarse) ComposedOverlayDefaults.COARSE_NUDGE else ComposedOverlayDefaults.NUDGE
    val turn = if (coarse) ComposedOverlayDefaults.COARSE_KEY_TURN_STEP else ComposedOverlayDefaults.KEY_TURN_STEP
    return when (event.key) {
      Key.DirectionLeft -> transform.stepped(pan = Offset(-nudge, 0f))
      Key.DirectionRight -> transform.stepped(pan = Offset(nudge, 0f))
      Key.DirectionUp -> transform.stepped(pan = Offset(0f, -nudge))
      Key.DirectionDown -> transform.stepped(pan = Offset(0f, nudge))
      Key.ZoomIn, Key.Plus, Key.Equals, Key.NumPadAdd -> transform.stepped(zoom = ComposedOverlayDefaults.WHEEL_STEP)
      Key.ZoomOut, Key.Minus, Key.NumPadSubtract -> transform.stepped(zoom = 1f / ComposedOverlayDefaults.WHEEL_STEP)
      Key.LeftBracket -> transform.stepped(turn = -turn)
      Key.RightBracket -> transform.stepped(turn = turn)
      else -> null
    }
  }
}
