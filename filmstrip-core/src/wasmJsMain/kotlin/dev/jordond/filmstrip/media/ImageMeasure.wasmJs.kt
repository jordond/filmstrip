@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.media

internal actual fun orientedBitmapOptions(): JsAny = newOrientedBitmapOptions()

@JsFun("() => ({ imageOrientation: 'from-image' })")
private external fun newOrientedBitmapOptions(): JsAny
