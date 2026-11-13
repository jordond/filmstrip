@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyStep
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.Promise

internal actual fun <T : JsAny> stubIterator(
  next: () -> Promise<MediabunnyStep<T>>,
  close: () -> Promise<MediabunnyStep<T>>,
): MediabunnyIterator<T> = js("({ next: next, 'return': close })").unsafeCast<MediabunnyIterator<T>>()

internal actual fun <T : JsAny> pendingStep(): Promise<MediabunnyStep<T>> =
  js("new Promise(function () {})").unsafeCast<Promise<MediabunnyStep<T>>>()

internal actual fun <T : JsAny> rejectedStep(message: String): Promise<MediabunnyStep<T>> =
  js("Promise.reject(new Error(message))").unsafeCast<Promise<MediabunnyStep<T>>>()

internal actual fun <T : JsAny> spyOnClose(
  sample: T,
  closed: (T) -> Unit,
): () -> Unit =
  js(
    """
    (function () {
      var proto = Object.getPrototypeOf(sample);
      var original = proto.close;
      proto.close = function () {
        closed(this);
        return original.apply(this, arguments);
      };
      return function () { proto.close = original; };
    })()
    """,
  ).unsafeCast<() -> Unit>()
