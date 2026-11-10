package dev.jordond.filmstrip.convention.testmedia

import java.io.Serializable

/**
 * One generated test clip.
 *
 * Everything a clip needs lives on this record, never in an ffmpeg command line, so the same one
 * drives generation, the ffprobe verification pass and the manifest.
 *
 * @param bitrateKbps Target video bitrate in kbps, pinned so CRF cannot move it. Decode cost tracks
 * bitrate, and the realtime benchmark measures decode and effects together, so a fixture encoded
 * at a quarter of a real camera's bitrate would flatter the pipeline.
 * @param hue Degrees of hue rotation applied to the test pattern, so clips in a concat are told
 * apart at a glance on a screen recording.
 * @param transfer The HDR transfer function to write the clip in, as 10-bit HEVC in BT.2020, or
 * null for 8-bit AVC in BT.709. The verification pass checks the color tags, so a clip that asked
 * for one grade and came back with another fails the build.
 * @param rotationDegrees Rotation written into the container as a display matrix, leaving the
 * frames landscape. This is what a phone camera writes for a portrait recording, and the only way
 * to make a fixture whose coded size and display size disagree.
 * @param patch A flat square painted over the middle of the frame, or null to leave the pattern
 * alone. A test that reads one pixel and predicts what an operation did to it needs somewhere the
 * reading does not depend on how the encoder handled the pixel next door.
 * @param audioTracks The audio tracks the clip carries, in the order they are written. One track with no language
 * writes the file exactly as a single-audio clip always has, and more than one is what a test choosing between the
 * audio tracks of a source reads.
 * @param clickSeconds When set, the clip's one audio track carries silence with one click on the sample at this time in
 * place of the tone. A test that asks where a source frame landed needs a frame it can find, and a steady tone repeats
 * every period.
 * @param container The container the clip is written in, which also names its file's extension.
 */
data class FixtureSpec(
  val name: String,
  val width: Int,
  val height: Int,
  val frameRate: Int,
  val durationSeconds: Double,
  val sampleRate: Int,
  val channelCount: Int,
  val bitrateKbps: Int,
  val audioTracks: List<AudioTrackSpec> = listOf(AudioTrackSpec()),
  val hue: Int = 0,
  val transfer: FixtureTransfer? = null,
  val rotationDegrees: Int = 0,
  val patch: FixturePatch? = null,
  val clickSeconds: Double? = null,
  val container: FixtureContainer = FixtureContainer.Mp4,
) : Serializable {
  val fileName: String get() = "$name.${container.extension}"

  init {
    require(audioTracks.isNotEmpty()) { "$name has no audio track" }
    require(clickSeconds == null || audioTracks.size == 1) { "$name clicks on more than one audio track" }
  }

  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

/**
 * One audio track of a fixture.
 *
 * @param toneHz The frequency of the sine the track carries. A test that mixes two sources, or picks one track of
 * several, and has to say which one it is hearing needs them at different frequencies, since two tones at the same one
 * sum by their relative phase and the level that reaches the file stops being predictable.
 * @param language The ISO 639-2 code written into the track's media header, or null to leave it undetermined.
 * @param isDefault Whether the track is written as the default audio track, which an MP4 records as the enabled flag
 * in its track header and Matroska as the track's default flag. An MPEG transport stream carries no such flag. Only
 * read when the fixture carries more than one audio track.
 */
data class AudioTrackSpec(
  val toneHz: Int = 440,
  val language: String? = null,
  val isDefault: Boolean = false,
) : Serializable {
  companion object {
    private const val serialVersionUID: Long = 1L
  }
}

/**
 * The container a fixture is written in.
 *
 * @param extension The file extension ffmpeg picks the muxer by.
 */
enum class FixtureContainer(
  val extension: String,
) {
  Mp4("mp4"),
  Matroska("mkv"),
  MpegTs("ts"),
}

/**
 * The HDR transfer function a fixture is graded in.
 *
 * @param tag What ffmpeg and x265 both call it. The container flag and the bitstream parameter take
 * the same spelling.
 */
enum class FixtureTransfer(
  val tag: String,
) {
  Pq("smpte2084"),
  Hlg("arib-std-b67"),
}

/**
 * A flat square of one color, painted over the middle of the frame after the noise.
 *
 * `testsrc` is a pattern of hard edges and the middle of a 1280x720 frame lands on one. A 4:2:0
 * chroma sample there spans both sides of the edge, so what a single pixel reads back as moves with
 * the encoder and with the ffmpeg build. A point on the pattern drifts between 6 and 61 ten-bit
 * code values from what the transfer functions predict, where a flat patch of the same colors
 * drifts by 1 to 4.
 *
 * @param red Eight-bit channel value, since the pattern is generated in RGB and graded afterwards.
 * @param size The square's side in pixels, wide enough that a chroma sample at the centre reads
 * nothing but the patch.
 */
data class FixturePatch(
  val red: Int,
  val green: Int,
  val blue: Int,
  val size: Int = 160,
) : Serializable {
  val hex: String get() = "0x%02X%02X%02X".format(red, green, blue)

  companion object {
    private const val serialVersionUID: Long = 1L

    /**
     * Channels far enough apart to tell a per-channel operation from one applied to luminance, with
     * the brightest high enough that a factor above one runs out of format and the rest low enough
     * that they do not.
     */
    val Graded = FixturePatch(red = 240, green = 150, blue = 90)
  }
}
