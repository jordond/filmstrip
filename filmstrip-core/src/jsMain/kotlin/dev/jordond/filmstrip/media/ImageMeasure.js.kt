@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

internal actual fun orientedBitmapOptions(): JsAny = js("({ imageOrientation: 'from-image' })").unsafeCast<JsAny>()
