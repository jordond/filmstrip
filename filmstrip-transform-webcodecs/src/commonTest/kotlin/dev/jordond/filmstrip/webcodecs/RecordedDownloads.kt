package dev.jordond.filmstrip.webcodecs

import kotlin.js.JsAny
import kotlin.js.JsArray
import kotlin.js.get

/**
 * One download a test started, as the shared Karma download guard recorded it instead of letting the browser save it.
 */
internal external interface RecordedDownload : JsAny {
  val name: String
}

/**
 * Every download started in this page so far, oldest first. The shared Karma config loads the guard that fills it
 * before any test runs.
 */
internal external val filmstripDownloads: JsArray<RecordedDownload>

/**
 * The names of every download started in this page so far, oldest first.
 */
internal fun recordedDownloads(): List<String> = List(filmstripDownloads.length) { filmstripDownloads[it]!!.name }
