package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.FilmstripVersion
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effects.builtInEffectSerializers
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.sample.ui.asClock
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonNull
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import kotlinx.serialization.json.putJsonArray
import kotlinx.serialization.json.putJsonObject

/**
 * Both halves of a report: one to paste into an issue, one to replay from.
 *
 * @property markdown The report a human reads, in the order the bug template asks for it.
 * @property json The composition, the spec and the session log, machine-readable.
 */
public class DiagnosticsReport(
  public val markdown: String,
  public val json: String,
)

/**
 * Builds a report out of everything this session knows.
 *
 * Paths never reach it. A picked file's name is the user's, and on Android it is a `content://` uri
 * pointing at their library, so every source is reduced to a hash, an extension and whether it came
 * from the sample's own clip list, which is the part anybody else can reproduce from.
 */
public fun SampleAppState.diagnosticsReport(): DiagnosticsReport =
  DiagnosticsReport(markdown = diagnosticsMarkdown(), json = diagnosticsJson())

private val json = Json {
  prettyPrint = true
  encodeDefaults = true
  serializersModule = builtInEffectSerializers
}

private fun SampleAppState.diagnosticsMarkdown(): String = buildString {
  val device = deviceInfo()

  section("Version")
  line(FilmstripVersion.name)

  section("Targets")
  if (backends.isEmpty()) {
    line("No export backend is registered.")
  } else {
    backends.forEach { backend -> line("* ${backend.name} (`${backend.artifact}`)") }
  }

  section("Device")
  line("* ${device.platform}: ${device.model}")
  line("* ${device.operatingSystem}")
  device.details.forEach { (key, value) -> line("* $key: $value") }

  section("Media")
  line(sourceSummary())
  probeSummary()?.let { line(it) }

  section("Steps to reproduce")
  line("Spec: ${spec().summary()}")
  composition()?.let { composition ->
    val effects = composition.effectIds()
    line("Effects: ${if (effects.isEmpty()) "none" else effects.joinToString(", ")}")
    line("Trim: ${edit.trimRange(sourceDuration).summary()}")
  }

  section("What happened")
  line(outcomeSummary())
  val adjustments = exportAdjustments.ifEmpty { (verdict as? Verdict.Degraded)?.adjustments.orEmpty() }
  if (adjustments.isNotEmpty()) {
    line("")
    line("Adjustments:")
    adjustments.forEach { line("* ${it.summary()}") }
  }
  (verdict as? Verdict.Capable)?.plan?.summary()?.let { line(it) }
  (verdict as? Verdict.Degraded)?.plan?.summary()?.let { line(it) }

  section("Device capabilities")
  line(capabilitiesSummary())

  section("Session log")
  line("```text")
  if (recorder.events.isEmpty()) {
    line("Nothing recorded.")
  } else {
    recorder.events.forEach { event ->
      val detail = event.detail.entries.joinToString(" ") { (key, value) -> "$key=${value.oneLine()}" }
      line("${event.elapsed.inWholeMilliseconds}ms  ${event.label}${if (detail.isEmpty()) "" else "  $detail"}")
    }
  }
  line("```")
}

private fun SampleAppState.diagnosticsJson(): String {
  val device = deviceInfo()
  val composition = composition()?.redacted()

  val document = buildJsonObject {
    put("version", FilmstripVersion.name)
    putJsonArray("backends") {
      backends.forEach { backend ->
        add(
          buildJsonObject {
            put("name", backend.name)
            put("artifact", backend.artifact)
          },
        )
      }
    }
    putJsonObject("device") {
      put("platform", device.platform)
      put("model", device.model)
      put("operatingSystem", device.operatingSystem)
      device.details.forEach { (key, value) -> put(key, value) }
    }
    put("source", sourceSummary())
    put("spec", json.encodeToJsonElement(ExportSpec.serializer(), spec()))
    put(
      key = "composition",
      element = composition?.let { json.encodeToJsonElement(EditComposition.serializer(), it) } ?: JsonNull,
    )
    put("outcome", outcomeSummary())
    putJsonArray("log") {
      recorder.events.forEach { event ->
        add(
          buildJsonObject {
            put("at", event.elapsed.toString())
            put("label", event.label)
            putJsonObject("detail") {
              event.detail.forEach { (key, value) -> put(key, value) }
            }
          },
        )
      }
    }
  }

  return json.encodeToString(JsonElement.serializer(), document)
}

private fun SampleAppState.sourceSummary(): String {
  val preset = sourcePreset
  if (preset != null) return "Sample clip \"${preset.name}\" (`${preset.fileName}`)"

  val source = source ?: return "Nothing loaded."
  return "A picked file, ${source.redactedName()}"
}

private fun SampleAppState.probeSummary(): String? =
  when (val result = probe) {
    null -> null
    is ProbeResult.Failure -> "The probe failed: ${result.error.line()}"
    is ProbeResult.Success -> result.info.summary()
  }

private fun SampleAppState.outcomeSummary(): String {
  exportFailure?.let { return "The export failed: ${it.line()}" }
  exported?.let { return "The export succeeded. Output: ${exportedInfo.outputSummary()}" }

  return when (val current = verdict) {
    null -> if (exporting) "An export is still running." else "Nothing has been planned yet."
    is Verdict.Incapable -> "The device refused the plan: ${current.reasons.joinToString("; ") { it.line() }}"
    is Verdict.Degraded -> "The device will run it with changes."
    is Verdict.Capable -> "The device will run it as asked."
  }
}

private fun SampleAppState.capabilitiesSummary(): String =
  when (val result = capabilities) {
    null -> "Not asked."
    is CapabilitiesResult.Failure -> "Could not be asked: ${result.error.line()}"
    is CapabilitiesResult.Success -> {
      val capabilities = result.capabilities
      buildString {
        appendLine("* HDR encoding: ${capabilities.supportsHdrEncoding}")
        capabilities.concurrentSessionBudget?.let { appendLine("* Concurrent sessions: $it") }
        capabilities.video.forEach { video ->
          val hardware = when (video.isHardwareAccelerated) {
            true -> "hardware"
            false -> "software"
            null -> "unknown path"
          }
          appendLine(
            "* ${video.codec.name}: ${video.encoderName ?: "unnamed"}, $hardware, " +
                "up to ${video.maxSize.width}x${video.maxSize.height}, alignment ${video.sizeAlignment}",
          )
        }
        capabilities.audio.forEach { audio ->
          appendLine("* ${audio.codec.name}: up to ${audio.maxChannelCount} channels")
        }
      }.trimEnd()
    }
  }

private fun ProbeResult?.outputSummary(): String =
  (this as? ProbeResult.Success)?.info?.summary() ?: "could not be probed"

private fun MediaInfo.summary(): String {
  val track = video ?: return "No video track, ${duration.asClock()} long"
  val fps = track.frameRate?.let { ", ${it} fps" }.orEmpty()
  val hdr = track.hdrTransfer?.let { ", HDR $it" }.orEmpty()
  return "${track.displaySize.width}x${track.displaySize.height} ${track.codec.name}$fps$hdr, " +
      "rotation ${track.rotationDegrees}, ${duration.asClock()}, exportable=$isExportable"
}

private fun ExportSpec.summary(): String =
  listOfNotNull(
    targetHeight?.let { "height $it" },
    bitrate?.let { "${it.bitsPerSecond / 1_000_000} Mbps" },
    "video $videoCodec",
    "audio $audioCodec",
    frameRate?.let { "$it fps" },
    "hdr $hdr",
    "trim $trim",
    if (strict) "strict" else null,
  ).joinToString(", ")

private fun ExportPlan.summary(): String =
  "Plan: $path, ${output.size.width}x${output.size.height} ${output.videoCodec}, " +
      "${effectOrder.size} effects, parity $parity"

private fun Adjustment.summary(): String = "$kind: $requested became $resolved. $message"

private fun TimeRange?.summary(): String {
  val range = this ?: return "none"
  val end = range.endExclusive?.asClock() ?: "the end"
  return "${range.start.asClock()} to $end"
}

private fun ExportError.line(): String = "${this::class.simpleName ?: "Error"}: $message"

private fun EditComposition.effectIds(): List<String> =
  (effects + tracks.flatMap { track -> track.effects + track.clips.flatMap { it.effects } }).map { it.id }

/**
 * The same composition with every source reduced to something that names no file.
 */
private fun EditComposition.redacted(): EditComposition = EditComposition(
  tracks = tracks.map { track ->
    Track(
      clips = track.clips.map { clip ->
        Clip(
          source = MediaSource.of(clip.source.redactedName()),
          trim = clip.trim,
          effects = clip.effects,
          audio = clip.audio,
        )
      },
      content = track.content,
      effects = track.effects,
      audio = track.audio,
      start = track.start,
      looping = track.looping,
    )
  },
  effects = effects,
  audio = audio,
  fill = fill,
)

/**
 * The source reduced to something that names no file.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun MediaSource.redactedName(): String =
  when (this) {
    is MediaSource.Path -> path.redactLocation()
    is MediaSource.Uri -> uri.redactLocation()
    is MediaSource.Bytes -> "bytes-${bytes.size}"
    is MediaSource.Image -> "image-${image.redactedName()}"
  }

private fun ImageSource.redactedName(): String =
  when (this) {
    is ImageSource.Path -> path.redactLocation()
    is ImageSource.Uri -> uri.redactLocation()
    is ImageSource.Bytes -> "bytes-${bytes.size}"
  }

// The extension is the part that matters for a container bug, and the hash keeps two different
// clips in one composition distinguishable without saying what either one is.
private fun String.redactLocation(): String {
  val name = substringAfterLast('/')
  val extension = name.substringAfterLast('.', "")
  val hash = hashCode().toUInt().toString(16)
  return if (extension.isEmpty()) "clip-$hash" else "clip-$hash.$extension"
}

private fun String.oneLine(): String = replace('\n', ' ').trim()

private fun StringBuilder.line(text: String) {
  appendLine(text)
}

private fun StringBuilder.section(title: String) {
  if (isNotEmpty()) appendLine()
  appendLine("### $title")
  appendLine()
}
