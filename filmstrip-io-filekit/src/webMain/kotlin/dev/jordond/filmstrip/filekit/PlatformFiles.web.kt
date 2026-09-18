@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import io.github.vinceglb.filekit.name
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Publishes this file as an object URL, which is the only handle WebCodecs reads.
 *
 * Every call mints a new URL, so call it once per file and keep the result. The URL belongs to the
 * caller: it holds the file alive until `URL.revokeObjectURL` is called on it, and nothing in
 * filmstrip revokes one.
 *
 * @throws IllegalArgumentException on a picked directory, which has no bytes to read.
 */
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.ofUri(objectUrl())

/**
 * The same, for a still image. A new URL every call, and the caller revokes it.
 *
 * @throws IllegalArgumentException on a picked directory, which has no bytes to read.
 */
public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.ofUri(objectUrl())

// A browser cannot write into a picked file, so the export engine downloads its result instead and
// the file only supplies the name it is saved under.
public actual fun PlatformFile.toMediaSink(): MediaSink = MediaSink.of(name)

private fun PlatformFile.objectUrl(): String =
  when (val file = webFile) {
    is WebFile.FileWrapper -> URL.createObjectURL(file.file)
    is WebFile.DirectoryWrapper -> throw IllegalArgumentException("A directory cannot be read as media.")
  }

/**
 * Object URL minting, declared here rather than taken from a DOM binding so that js and wasmJs
 * share one implementation.
 */
private external object URL : JsAny {
  fun createObjectURL(obj: JsAny): String
}
