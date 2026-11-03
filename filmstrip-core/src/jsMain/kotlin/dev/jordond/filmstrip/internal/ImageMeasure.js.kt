@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.internal

internal actual fun orientedBitmapOptions(): JsAny = js("({ imageOrientation: 'from-image' })").unsafeCast<JsAny>()
