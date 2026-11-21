package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.VideoQuality
import kotlin.time.Duration.Companion.seconds

/**
 * The rows of the export menu, and the one place an [ExportSpec] is built from them.
 *
 * A spec that names both a bitrate and a quality tier is refused, so setting either one clears the other and the menu
 * never shows a value the export would not use.
 */
@Stable
public class ExportOptions {
  public var targetHeight: Int? by mutableStateOf(DEFAULT_HEIGHT)
  public var videoCodec: VideoCodec by mutableStateOf(VideoCodec.Auto)
  public var audioCodec: AudioCodec by mutableStateOf(AudioCodec.Auto)
  public var frameRate: Int? by mutableStateOf(null)
  public var hdr: HdrMode by mutableStateOf(HdrMode.Auto)
  public var strict: Boolean by mutableStateOf(false)
  public var keyFrameSeconds: Int? by mutableStateOf(null)

  private var bitrateState: Int? by mutableStateOf(DEFAULT_MBPS)
  private var qualityState: VideoQuality? by mutableStateOf(null)

  public var bitrateMbps: Int?
    get() = bitrateState
    set(value) {
      bitrateState = value
      if (value != null) qualityState = null
    }

  public var quality: VideoQuality?
    get() = qualityState
    set(value) {
      qualityState = value
      if (value != null) bitrateState = null
    }

  /**
   * The spec the rows add up to.
   */
  public fun spec(): ExportSpec =
    ExportSpec(
      heightPx = targetHeight,
      bitrate = bitrateMbps?.let(Bitrate::mbps),
      quality = quality,
      videoCodec = videoCodec,
      audioCodec = audioCodec,
      frameRate = frameRate,
      hdr = hdr,
      strict = strict,
      keyFrameInterval = keyFrameSeconds?.seconds,
    )

  /**
   * Copies a preset into the rows, so the rows stay the only source of [spec] and can be changed from there.
   */
  public fun apply(preset: ExportSpec) {
    targetHeight = preset.heightPx
    videoCodec = preset.videoCodec
    audioCodec = preset.audioCodec
    frameRate = preset.frameRate
    hdr = preset.hdr
    strict = preset.strict
    keyFrameSeconds = preset.keyFrameInterval?.inWholeSeconds?.toInt()
    bitrateState = preset.bitrate?.let { (it.bitsPerSecond / BITS_PER_MBPS).toInt() }
    qualityState = preset.quality
  }

  public companion object {
    private const val DEFAULT_HEIGHT = 1080
    private const val DEFAULT_MBPS = 4
    private const val BITS_PER_MBPS = 1_000_000L

    /**
     * The library's presets under the names the menu shows.
     */
    public val Presets: List<Pair<String, ExportSpec>> =
      listOf(
        "Upload" to ExportSpec.Upload,
        "Share" to ExportSpec.Share,
        "Archive" to ExportSpec.Archive,
        "Original" to ExportSpec.Original,
      )
  }
}
