@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.negotiation.encodesAudio
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The render the planner lowers [composition] to, the same one an export of it would run.
 */
internal suspend fun renderOf(
  composition: EditComposition,
  spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
): BrowserRender {
  val resolved = engine().resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
  return browserRenderOf(resolved, composition)
}

/**
 * Whether an export from this browser asking for AAC writes audio. Chrome hands AAC to the platform, so Linux runners
 * have none.
 */
internal suspend fun browserEncodesAac(): Boolean {
  val capabilities = engine().capabilities().shouldBeInstanceOf<CapabilitiesResult.Success>().capabilities
  return capabilities.encodesAudio(ExportSpec(audioCodec = AudioCodec.Aac))
}

/**
 * One flat grey frame of [width] by [height] at output slot [index] of a [frameRate] grid, for a test
 * that feeds an encoder directly. The caller closes it.
 */
internal fun greyFrame(
  index: Int,
  width: Int = 64,
  height: Int = 64,
  frameRate: Int = 30,
): VideoSample {
  val pixels = ByteArray(width * height * 4) { if (it % 4 == 3) -1 else GREY }
  return VideoSample(
    pixels.toUint8Array(),
    JsOptions()
      .put("format", "RGBA")
      .put("codedWidth", width)
      .put("codedHeight", height)
      .put("timestamp", index.toDouble() / frameRate)
      .put("duration", 1.0 / frameRate)
      .build(),
  )
}

private fun engine(): BrowserExportEngine =
  BrowserExportEngine(ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(), BrowserProber())

private const val GREY: Byte = 120
