@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyIterator
import dev.jordond.filmstrip.webcodecs.internal.interop.MediabunnyStep
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.Promise
import kotlin.js.unsafeCast

private fun iteratorOf(
  next: () -> JsAny,
  close: () -> JsAny,
): JsAny = js("({ next: next, 'return': close })")

private fun pendingPromise(): JsAny = js("new Promise(function () {})")

private fun rejectedPromise(message: String): JsAny = js("Promise.reject(new Error(message))")

private fun patchClose(
  sample: JsAny,
  closed: (JsAny) -> Unit,
): () -> Unit =
  js(
    """{
    var proto = Object.getPrototypeOf(sample);
    var original = proto.close;
    proto.close = function () {
      closed(this);
      return original.apply(this, arguments);
    };
    return function () { proto.close = original; };
    }""",
  )

internal actual fun <T : JsAny> stubIterator(
  next: () -> Promise<MediabunnyStep<T>>,
  close: () -> Promise<MediabunnyStep<T>>,
): MediabunnyIterator<T> = iteratorOf({ next() }, { close() }).unsafeCast<MediabunnyIterator<T>>()

internal actual fun <T : JsAny> pendingStep(): Promise<MediabunnyStep<T>> =
  pendingPromise().unsafeCast<Promise<MediabunnyStep<T>>>()

internal actual fun <T : JsAny> rejectedStep(message: String): Promise<MediabunnyStep<T>> =
  rejectedPromise(message).unsafeCast<Promise<MediabunnyStep<T>>>()

internal actual fun <T : JsAny> spyOnClose(
  sample: T,
  closed: (T) -> Unit,
): () -> Unit = patchClose(sample) { closed(it.unsafeCast<T>()) }
