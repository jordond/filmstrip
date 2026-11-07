@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.player

import io.kotest.matchers.shouldBe
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Runs [body] on this real page with the globals in [names] taken off it, and puts back the ones it had.
 */
internal inline fun withoutGlobals(
  vararg names: String,
  body: () -> Unit,
) {
  val taken = names.filter { Reflect.has(globalThis, it) }.associateWith { Reflect.get(globalThis, it) }
  try {
    names.forEach { Reflect.deleteProperty(globalThis, it) shouldBe true }
    body()
  } finally {
    taken.forEach { (name, global) -> Reflect.set(globalThis, name, global) }
  }
}

/**
 * The page's global scope, which is where the WebCodecs interfaces are read from.
 */
internal external val globalThis: JsAny

/**
 * As much of `Reflect` as taking a global off the page and putting it back takes.
 */
internal external object Reflect {
  fun has(
    target: JsAny,
    key: String,
  ): Boolean

  fun get(
    target: JsAny,
    key: String,
  ): JsAny?

  fun set(
    target: JsAny,
    key: String,
    value: JsAny?,
  ): Boolean

  fun deleteProperty(
    target: JsAny,
    key: String,
  ): Boolean
}
