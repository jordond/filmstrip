@file:JsQualifier("Intl")
@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.effects.overlay

import kotlin.js.ExperimentalWasmJsInterop
import kotlin.js.JsAny
import kotlin.js.JsQualifier

/**
 * `Intl.Segmenter` at its default grapheme granularity, which splits a word too wide for its line
 * without parting a base letter from its marks.
 */
internal external class Segmenter : JsAny {
  fun segment(input: String): Segments
}

/**
 * The segments of one string, looked up by the code unit they cover.
 */
internal external interface Segments : JsAny {
  fun containing(index: Int): SegmentData?
}

/**
 * One grapheme of the string that was segmented.
 */
internal external interface SegmentData : JsAny {
  val segment: String
}
