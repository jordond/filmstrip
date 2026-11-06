@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyStep
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.unsafeCast

/**
 * An iterator shaped like a mediabunny sink's, answering from [next] and from [close] for its `return`.
 */
internal expect fun <T : JsAny> stubIterator(
  next: () -> Promise<MediabunnyStep<T>>,
  close: () -> Promise<MediabunnyStep<T>>,
): MediabunnyIterator<T>

/**
 * A promise for one step that stays pending, which is a decode that has not produced anything yet.
 */
internal expect fun <T : JsAny> pendingStep(): Promise<MediabunnyStep<T>>

/**
 * A promise for one step that rejects with [message], which is a decode that failed.
 */
internal expect fun <T : JsAny> rejectedStep(message: String): Promise<MediabunnyStep<T>>

/**
 * Reports every `close` called on [sample]'s class until the returned function is called.
 *
 * @param closed Called with each instance, before it is closed.
 */
internal expect fun <T : JsAny> spyOnClose(
  sample: T,
  closed: (T) -> Unit,
): () -> Unit

/**
 * A step resolved already, holding [value].
 */
internal fun <T : JsAny> resolvedStep(
  done: Boolean,
  value: T?,
): Promise<MediabunnyStep<T>> =
  Promise.resolve(
    JsOptions()
      .put("done", done)
      .put("value", value)
      .build()
      .unsafeCast<MediabunnyStep<T>>(),
  )
