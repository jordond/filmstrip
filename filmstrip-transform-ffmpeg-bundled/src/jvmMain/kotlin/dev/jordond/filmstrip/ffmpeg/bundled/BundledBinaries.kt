package dev.jordond.filmstrip.ffmpeg.bundled

import dev.jordond.filmstrip.ffmpeg.FfmpegBinaries
import dev.jordond.filmstrip.ffmpeg.FfmpegBinariesResult
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import org.bytedeco.javacpp.Loader
import java.io.File
import java.io.IOException

/**
 * The ffmpeg and ffprobe bytedeco's platform jars carry, unpacked into javacpp's cache.
 *
 * A `-gpl` jar on the classpath is used ahead of the LGPL one this artifact depends on. Nothing is loaded into the
 * JVM: the directory is copied out and the two programs are marked executable.
 *
 * @param platform The javacpp name of the platform to find binaries for.
 * @param cache Copies a resource directory under `/org/bytedeco/ffmpeg` out and returns it, or null when the
 *   classpath has no such directory.
 */
internal class BundledBinaries(
  private val platform: String = Loader.getPlatform(),
  private val cache: (name: String) -> File? = ::cacheDirectory,
) : FfmpegBinaries {
  override suspend fun locate(): FfmpegBinariesResult =
    withContext(Dispatchers.IO) {
      val directory =
        try {
          cache("$platform$GPL_SUFFIX") ?: cache(platform)
        } catch (failure: IOException) {
          return@withContext FfmpegBinariesResult.Unavailable(unpackFailed(failure))
        } ?: return@withContext FfmpegBinariesResult.Unavailable(uncovered())

      val suffix = if (platform.startsWith("windows")) ".exe" else ""
      val ffmpeg = File(directory, "ffmpeg$suffix")
      val ffprobe = File(directory, "ffprobe$suffix")
      ffmpeg.setExecutable(true)
      ffprobe.setExecutable(true)
      FfmpegBinariesResult.Found(ffmpeg.absolutePath, ffprobe.absolutePath)
    }

  private fun uncovered(): String =
    "No ffmpeg for ${System.getProperty("os.name")} ${System.getProperty("os.arch")} ($platform) is on the " +
      "classpath. filmstrip-transform-ffmpeg-bundled carries ${PLATFORMS.joinToString()}. $WAY_OUT"

  private fun unpackFailed(failure: IOException): String =
    "The ffmpeg filmstrip-transform-ffmpeg-bundled carries for $platform could not be unpacked: " +
      "${failure.message}. $WAY_OUT"

  companion object {
    /**
     * The one source every instance shares, so they share one runtime.
     */
    val Default = BundledBinaries()

    /**
     * Every platform this artifact depends on a binary for, by its javacpp name.
     */
    val PLATFORMS = listOf("linux-arm64", "linux-x86_64", "macosx-arm64", "macosx-x86_64", "windows-x86_64")

    const val GPL_SUFFIX = "-gpl"
    const val RESOURCE_ROOT = "/org/bytedeco/ffmpeg"

    private const val WAY_OUT =
      "Point FfmpegConfig.executablePath at an ffmpeg for this machine, or set FILMSTRIP_FFMPEG."
  }
}

private fun cacheDirectory(name: String): File? =
  Loader.cacheResources(BundledBinaries::class.java, "${BundledBinaries.RESOURCE_ROOT}/$name").firstOrNull()
