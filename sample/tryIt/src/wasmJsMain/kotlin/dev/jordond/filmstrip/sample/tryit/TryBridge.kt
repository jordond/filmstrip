@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample.tryit

import kotlinx.browser.document
import kotlinx.browser.window
import org.w3c.dom.MessageEvent
import org.w3c.dom.events.Event
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsBoolean
import kotlin.js.JsString
import kotlin.js.toBoolean
import kotlin.js.toJsString

/**
 * The app's end of the message channel to the page that framed it.
 *
 * Takes messages only from the parent window on this origin, and ignores any it does not know.
 *
 * @param onControl Receives each change the page asks for.
 * @param onPageHidden Receives whether the page is hidden, once now and on every change after.
 * @param onGesture Runs inside the handler of each gesture on the app's own document.
 */
internal class TryBridge(
  private val onControl: (Control) -> Unit,
  private val onPageHidden: (Boolean) -> Unit,
  private val onGesture: () -> Unit,
) {
  /**
   * Starts listening.
   */
  fun start() {
    window.addEventListener("message", ::receive)
    document.addEventListener("visibilitychange", { onPageHidden(pageIsHidden()) })
    onPageHidden(pageIsHidden())
    // The events a browser counts as a gesture, heard ahead of the picture since it may keep them to itself.
    GESTURES.forEach { name -> window.addEventListener(name, { onGesture() }, true) }
  }

  /**
   * Sends one encoded message to the page.
   */
  fun send(message: String) {
    window.parent.postMessage(message.toJsString(), window.location.origin)
  }

  private fun receive(event: Event) {
    val message = event as? MessageEvent ?: return
    if (!isFromParent(message)) return
    val text = (message.data as? JsString)?.toString() ?: return
    decodeControl(text)?.let(onControl)
  }
}

/**
 * Parses one message from the page with the browser's own parser, or null for anything it does not know.
 */
internal fun decodeControl(text: String): Control? {
  if (text.length > MAX_WIRE_CHARS) return null
  val parsed = parseOrNull(text) ?: return null
  return controlOf(JsonFields(parsed))
}

private class JsonFields(
  private val parsed: JsAny,
) : WireFields {
  override fun string(name: String): String? = stringField(parsed, name)?.toString()

  override fun boolean(name: String): Boolean? = booleanField(parsed, name)?.toBoolean()
}

private val GESTURES: List<String> = listOf("click", "touchend", "keydown")

private fun pageIsHidden(): Boolean = js("document.hidden")

private fun isFromParent(event: MessageEvent): Boolean =
  js("event.origin === window.location.origin && event.source === window.parent")

// Null for text that is not JSON, and for JSON that is not an object.
private fun parseOrNull(text: String): JsAny? =
  js(
    """
    {
      try {
        const parsed = JSON.parse(text)
        return parsed !== null && typeof parsed === 'object' && !Array.isArray(parsed) ? parsed : null
      } catch (error) {
        return null
      }
    }
    """,
  )

private fun stringField(
  parsed: JsAny,
  name: String,
): JsString? = js("typeof parsed[name] === 'string' ? parsed[name] : null")

private fun booleanField(
  parsed: JsAny,
  name: String,
): JsBoolean? = js("typeof parsed[name] === 'boolean' ? parsed[name] : null")
