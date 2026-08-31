@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.playback.internal

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

// The page globals the preview engine reads: the wall clock its silent branch counts on, the frame
// callback its decoder is pumped from, and the one backgrounding signal a page gets.

/**
 * Schedules [callback] for the next time the page paints.
 *
 * Throttled to about once a second in a background tab, and stopped altogether in some, which is
 * exactly why nothing that has to keep time is driven from it.
 */
internal external fun requestAnimationFrame(callback: () -> Unit): Int

internal external fun cancelAnimationFrame(handle: Int)

/**
 * The page's monotonic wall clock.
 */
internal external interface Performance : JsAny {
  /**
   * Milliseconds since the page's time origin.
   */
  fun now(): Double
}

internal external val performance: Performance

/**
 * As much of the document as the engine watches: whether the page is on screen, and the event that
 * says it stopped being.
 */
internal external interface PageDocument : JsAny {
  val hidden: Boolean

  fun addEventListener(
    type: String,
    listener: () -> Unit,
  )

  fun removeEventListener(
    type: String,
    listener: () -> Unit,
  )
}

internal external val document: PageDocument
