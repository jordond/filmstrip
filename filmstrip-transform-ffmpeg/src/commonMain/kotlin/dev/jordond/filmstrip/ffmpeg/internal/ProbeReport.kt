@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.ffmpeg.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.export.Bitrate
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.displaySizeOf
import dev.jordond.filmstrip.media.trackCodecOf
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds

// Pure parsing of `ffprobe -show_streams -show_format -of default`. The flat key=value form is used
// and never JSON, so this module needs no serialiser and the parser has no schema to drift from.

internal val PROBE_ARGUMENTS: List<String> =
  listOf("-v", "error", "-show_streams", "-show_format", "-of", "default")

/**
 * One `[SECTION] ... [/SECTION]` block.
 *
 * Repeated keys are kept in order, because a stream can carry several `TAG:` entries and a side
 * data block repeats keys the stream block already used.
 */
internal class ProbeSection(
  val name: String,
  val values: Map<String, String>,
  val children: List<ProbeSection>,
) {
  operator fun get(key: String): String? = values[key]

  fun child(name: String): ProbeSection? = children.firstOrNull { it.name == name }
}

internal fun parseProbeSections(output: String): List<ProbeSection> {
  val roots = mutableListOf<ProbeSection>()
  val stack = ArrayDeque<Pair<String, MutableList<Pair<String, String>>>>()
  val childrenOf = mutableMapOf<Int, MutableList<ProbeSection>>()

  output.lineSequence().forEach { raw ->
    val line = raw.trim()
    when {
      line.startsWith("[/") -> {
        val (name, values) = stack.removeLastOrNull() ?: return@forEach
        val section = ProbeSection(name, values.toMap(), childrenOf.remove(stack.size + 1).orEmpty())
        if (stack.isEmpty()) roots += section else childrenOf.getOrPut(stack.size) { mutableListOf() } += section
      }
      line.startsWith("[") && line.endsWith("]") -> {
        stack.addLast(line.trim('[', ']') to mutableListOf())
      }
      line.contains('=') -> {
        stack.lastOrNull()?.second?.add(line.substringBefore('=') to line.substringAfter('='))
      }
    }
  }

  return roots
}

/**
 * Reads a probe into a [MediaInfo], or null when there is no readable track in it.
 */
internal fun parseMediaInfo(output: String): MediaInfo? {
  val sections = parseProbeSections(output)
  val streams = sections.filter { it.name == "STREAM" }
  val format = sections.firstOrNull { it.name == "FORMAT" }

  val video = streams.firstOrNull { it["codec_type"] == "video" }
  val audio = streams.firstOrNull { it["codec_type"] == "audio" }
  if (video == null && audio == null) return null

  val durationSeconds =
    format?.get("duration")?.toDoubleOrNull()
      ?: video?.get("duration")?.toDoubleOrNull()
      ?: audio?.get("duration")?.toDoubleOrNull()
      ?: 0.0

  return MediaInfo(
    duration = durationSeconds.seconds,
    video = video?.toVideoTrack(),
    audio = audio?.toAudioTrack(),
    // ffmpeg reads what it can open. It has no notion of a rights-protected asset it can decode
    // but must not write, so there is nothing here to report as false.
    isExportable = true,
  )
}

/**
 * The container's own tag for the codec, falling back to ffmpeg's name for it.
 *
 * A tag is the four-character code the file stores, which is what the Apple backend reports, so it
 * is preferred. Not every container has one, and ffprobe spells its absence as four bracketed
 * zeroes, not as an empty field.
 */
private fun ProbeSection.codecName(): String =
  this["codec_tag_string"]?.takeIf { it.isNotBlank() && it != UNTAGGED } ?: this["codec_name"].orEmpty()

/**
 * Bits per colour channel read off the pixel format, or null when ffprobe named no format.
 *
 * A pixel format names its depth only when it is deeper than eight, so a format with no digits in
 * it is eight bit, not unknown.
 */
private fun ProbeSection.pixelFormatDepth(): Int? {
  val format = this["pix_fmt"]?.takeIf { it.isNotBlank() } ?: return null
  return DEEP_FORMATS.firstOrNull { (marker, _) -> format.contains(marker) }?.second ?: EIGHT_BIT
}

private fun ProbeSection.toVideoTrack(): VideoTrackInfo {
  val coded = Size(this["width"]?.toIntOrNull() ?: 0, this["height"]?.toIntOrNull() ?: 0)
  val rotation = rotationDegrees()
  val pixelAspect = ratio(this["sample_aspect_ratio"]) ?: 1f

  return VideoTrackInfo(
    codedSize = coded,
    displaySize = displaySizeOf(coded, rotation, pixelAspect),
    rotationDegrees = rotation,
    pixelAspectRatio = pixelAspect,
    frameRate = ratio(this["avg_frame_rate"]) ?: ratio(this["r_frame_rate"]),
    codec = trackCodecOf(codecName()),
    bitDepth = this["bits_per_raw_sample"]?.toIntOrNull() ?: pixelFormatDepth(),
    colorSpace =
      when (this["color_space"]) {
        "bt709" -> ColorSpace.Bt709
        "bt2020nc", "bt2020c" -> ColorSpace.Bt2020
        "smpte170m", "bt470bg" -> ColorSpace.Bt601
        else -> ColorSpace.Unknown
      },
    hdrTransfer =
      when (this["color_transfer"]) {
        "arib-std-b67" -> HdrTransfer.Hlg
        "smpte2084" -> HdrTransfer.Pq
        else -> null
      },
    bitrate = this["bit_rate"]?.toLongOrNull()?.takeIf { it > 0 }?.let(::Bitrate),
  )
}

private fun ProbeSection.toAudioTrack(): AudioTrackInfo =
  AudioTrackInfo(
    codec = trackCodecOf(codecName()),
    sampleRate = this["sample_rate"]?.toIntOrNull() ?: 0,
    channelCount = this["channels"]?.toIntOrNull() ?: 0,
    bitrate = this["bit_rate"]?.toLongOrNull()?.takeIf { it > 0 }?.let(::Bitrate),
  )

// A display matrix reports the rotation to undo, so -90 in the side data is a 90 degree clockwise
// source. The legacy `rotate` tag reports the same thing with the opposite sign.
private fun ProbeSection.rotationDegrees(): Int {
  val fromSideData = child("SIDE_DATA")?.get("rotation")?.toDoubleOrNull()?.let { -it }
  val fromTag = this["TAG:rotate"]?.toDoubleOrNull()
  val degrees = fromSideData ?: fromTag ?: return 0
  return ((degrees.roundToInt() % FULL_TURN) + FULL_TURN) % FULL_TURN
}

private fun ratio(value: String?): Float? {
  val text = value ?: return null
  val parts = text.split('/', ':')
  if (parts.size != 2) return text.toFloatOrNull()?.takeIf { it > 0f }
  val numerator = parts[0].toFloatOrNull() ?: return null
  val denominator = parts[1].toFloatOrNull() ?: return null
  if (numerator <= 0f || denominator <= 0f) return null
  return numerator / denominator
}

private const val FULL_TURN = 360
private const val EIGHT_BIT = 8

// How ffprobe spells a container that carries no four-character code.
private const val UNTAGGED = "[0][0][0][0]"

// Ordered, so twelve is tried before ten would match the ten in a twelve-bit format's name.
private val DEEP_FORMATS = listOf("12" to 12, "10" to 10)
