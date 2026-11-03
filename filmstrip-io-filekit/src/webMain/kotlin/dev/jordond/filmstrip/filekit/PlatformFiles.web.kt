@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.filekit

import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.github.vinceglb.filekit.ExperimentalFileKitApi
import io.github.vinceglb.filekit.PlatformFile
import io.github.vinceglb.filekit.WebFile
import io.github.vinceglb.filekit.name
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny

/**
 * Publishes this file as an object URL, which is the only handle WebCodecs reads.
 *
 * Every call mints a new URL, so call it once per file and keep the result. The URL holds the
 * picked file until [release] frees it or the page unloads.
 *
 * @throws IllegalArgumentException on a picked directory, which has no bytes to read.
 */
public actual fun PlatformFile.toMediaSource(): MediaSource = MediaSource.ofUri(objectUrl())

/**
 * The same, for a still image.
 *
 * A new URL every call, held until [release] frees it or the page unloads.
 *
 * @throws IllegalArgumentException on a picked directory, which has no bytes to read.
 */
public actual fun PlatformFile.toImageSource(): ImageSource = ImageSource.ofUri(objectUrl())

// A browser cannot write into a picked file, so the export engine downloads its result instead and
// the file only supplies the name it is saved under.
public actual fun PlatformFile.toMediaSink(): MediaSink = MediaSink.of(name)

/**
 * Revokes the object URL [toMediaSource] minted, which is what lets the browser drop the file
 * behind it.
 *
 * Every other arm names something this module never minted, so it is left alone.
 */
public actual fun MediaSource.release() {
  if (this is MediaSource.Uri) revokeObjectUrl(uri)
}

/**
 * Revokes the object URL [toImageSource] minted, the way [MediaSource.release] does.
 */
public actual fun ImageSource.release() {
  if (this is ImageSource.Uri) revokeObjectUrl(uri)
}

@OptIn(ExperimentalFileKitApi::class)
private fun PlatformFile.objectUrl(): String =
  when (val file = webFile) {
    is WebFile.FileWrapper -> {
      URL.createObjectURL(file.file)
    }
    is WebFile.DirectoryWrapper, is WebFile.OriginPrivateDirectory -> {
      throw IllegalArgumentException("A directory cannot be read as media.")
    }
    // filekit keeps the origin private file handle internal. Its only public read copies the whole file into memory
    // and an object URL can't be minted from that.
    is WebFile.OriginPrivateFile -> {
      throw IllegalArgumentException("A file in the origin private file system cannot be read as media.")
    }
  }

// Only an object URL is revoked. A uri on any other scheme reached this module from somewhere else
// and revoking it would either do nothing or free a URL its owner still holds.
private fun revokeObjectUrl(uri: String) {
  if (uri.startsWith(OBJECT_URL_SCHEME)) URL.revokeObjectURL(uri)
}

private const val OBJECT_URL_SCHEME = "blob:"

/**
 * Object URL minting and revoking, declared here once for both js and wasmJs.
 */
private external object URL : JsAny {
  fun createObjectURL(obj: JsAny): String

  fun revokeObjectURL(url: String)
}
