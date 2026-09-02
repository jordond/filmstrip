@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

internal actual fun orientedBitmapOptions(): JsAny = js("({ imageOrientation: 'from-image' })").unsafeCast<JsAny>()
