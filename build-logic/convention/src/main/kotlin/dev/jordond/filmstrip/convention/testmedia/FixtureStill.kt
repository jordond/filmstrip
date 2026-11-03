package dev.jordond.filmstrip.convention.testmedia

/**
 * One generated still picture of a single flat color.
 *
 * A test that needs a picture in a format the JDK cannot write reads one of these instead of encoding its own, so its
 * input does not move with whatever tool the runner has installed.
 *
 * @param color The picture's color as `0xRRGGBB`, drawn in RGB before the container's encoder sees it.
 * @param container What the picture is written as, which also names its file extension.
 */
data class FixtureStill(
  override val name: String,
  val width: Int,
  val height: Int,
  val color: Int,
  val container: StillContainer,
) : Fixture {
  override val fileName: String get() = "$name.${container.extension}"

  val hex: String get() = "0x%06X".format(color and RGB_MASK)

  companion object {
    private const val serialVersionUID: Long = 1L

    private const val RGB_MASK = 0xFFFFFF
  }
}

/**
 * A still picture's container, and the codec ffprobe has to find inside it.
 *
 * @param extension The file extension, which is also the one the published object is keyed under.
 * @param mediaType The content type the object is served with.
 * @param codec What ffprobe calls the picture's codec, checked by the verification pass.
 * @param tool The host program that writes the container, or null when ffmpeg writes it alone. A host without it on
 * its `PATH` skips the still when generating and keeps whatever copy it already has.
 */
enum class StillContainer(
  val extension: String,
  val mediaType: String,
  val codec: String,
  val tool: String?,
) {
  /**
   * HEVC in a HEIF container, written by macOS `sips` since ffmpeg has no HEIF muxer. Generating one needs a Mac, while
   * reading one back needs only an ffmpeg that decodes HEIC. Any other host skips it when generating.
   */
  Heic("heic", "image/heic", "hevc", tool = "sips"),
}
