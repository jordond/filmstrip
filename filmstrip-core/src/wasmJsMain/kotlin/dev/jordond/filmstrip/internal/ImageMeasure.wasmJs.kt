@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.internal

internal actual fun orientedBitmapOptions(): JsAny = newOrientedBitmapOptions()

@JsFun("() => ({ imageOrientation: 'from-image' })")
private external fun newOrientedBitmapOptions(): JsAny
