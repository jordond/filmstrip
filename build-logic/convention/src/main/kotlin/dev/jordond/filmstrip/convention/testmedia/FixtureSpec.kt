package dev.jordond.filmstrip.convention.testmedia

import java.io.Serializable

/**
 * One generated test fixture.
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
 * null for 8-bit AVC in BT.709. The verification pass checks the colour tags, so a clip that asked
 * for one grade and came back with another fails the build.
 * @param rotationDegrees Rotation written into the container as a display matrix, leaving the
 * frames landscape. This is what a phone camera writes for a portrait recording, and the only way
 * to make a fixture whose coded size and display size disagree.
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
  val hue: Int = 0,
  val transfer: FixtureTransfer? = null,
  val rotationDegrees: Int = 0,
) : Serializable {
  val fileName: String get() = "$name.mp4"

  companion object {
    private const val serialVersionUID: Long = 1L
  }
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
