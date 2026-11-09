@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.webcodecs

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.audioCodecOf
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.webcodecs.internal.BROWSER_OPUS_ENCODER
import dev.jordond.filmstrip.webcodecs.internal.BrowserCapabilities
import dev.jordond.filmstrip.webcodecs.internal.BrowserExportEngine
import dev.jordond.filmstrip.webcodecs.internal.BrowserProber
import dev.jordond.filmstrip.webcodecs.internal.BrowserRender
import dev.jordond.filmstrip.webcodecs.internal.browserRenderOf
import dev.jordond.filmstrip.webcodecs.internal.interop.JsOptions
import dev.jordond.filmstrip.webcodecs.internal.interop.VideoSample
import dev.jordond.filmstrip.webcodecs.internal.interop.toUint8Array
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.js.ExperimentalWasmJsInterop

/**
 * The render the planner lowers [composition] to, the same one an export of it would run.
 */
internal suspend fun renderOf(
  composition: Composition,
  spec: ExportSpec = ExportSpec(videoCodec = VideoCodec.H264, audioCodec = AudioCodec.None),
): BrowserRender {
  val resolved = engine().resolve(composition, spec).shouldBeInstanceOf<ResolveResult.Resolved>().composition
  return browserRenderOf(resolved, composition)
}

/**
 * An H.264 export that leaves the audio codec to the planner, which picks one this browser encodes and the file
 * carries.
 */
internal fun autoAudioSpec(): ExportSpec = ExportSpec(videoCodec = VideoCodec.H264)

/**
 * The audio codec an export of [composition] under [spec] writes, read off the plan this browser makes for it.
 *
 * Every caller has audio to hear, so a plan that writes none fails the test here.
 */
internal suspend fun plannedAudioOf(
  composition: Composition,
  spec: ExportSpec,
): AudioCodec {
  val codec = renderOf(composition, spec).audioCodec
  withClue("this browser encodes no audio, so the plan drops it") { codec shouldNotBe AudioCodec.None }
  return codec
}

/**
 * Checks that the file behind [success] carries its audio in [planned].
 */
internal fun shouldCarryAudioIn(
  success: ExportStatus.Success,
  planned: AudioCodec,
) {
  val written = withClue("the export reported no audio track") { success.info.audio.shouldNotBeNull() }
  withClue("the plan named $planned and the file carries ${written.codec}") {
    audioCodecOf(written.codec.kind) shouldBe planned
  }
}

/**
 * An engine reading this browser's own encoders with only Opus left for audio, which is what a browser without AAC
 * reports.
 */
internal fun opusOnlyEngine(): BrowserExportEngine =
  BrowserExportEngine(
    ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build(),
    BrowserProber(),
  ) {
    val device = BrowserCapabilities().capabilities()
    DeviceCapabilities(
      video = device.video,
      audio = listOf(BROWSER_OPUS_ENCODER),
      supportsHdrEncoding = device.supportsHdrEncoding,
      concurrentSessionBudget = device.concurrentSessionBudget,
    )
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
