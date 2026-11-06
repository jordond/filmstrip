package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.ffmpeg.FfmpegConfig
import dev.jordond.filmstrip.ffmpeg.ffmpegBackend
import dev.jordond.filmstrip.ffmpeg.internal.hdrEncoder
import dev.jordond.filmstrip.ffmpeg.internal.process.MINIMUM_MAJOR
import dev.jordond.filmstrip.ffmpeg.internal.process.MINIMUM_MINOR
import dev.jordond.filmstrip.ffmpeg.internal.process.ProcessRunner
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainLocator
import dev.jordond.filmstrip.ffmpeg.internal.process.ToolchainResult
import dev.jordond.filmstrip.test.encodesHdr
import kotlinx.coroutines.runBlocking
import kotlin.system.exitProcess

/**
 * Why this host cannot run the ffmpeg suite, or null when it can.
 *
 * The toolchain is looked up the way the backend looks it up, so a missing ffmpeg or ffprobe, or one below the floor,
 * is refused by the same check the library runs. A toolchain that passes must also write HDR, since every HDR test
 * reads the ten-bit encoder the floor build always carries. The message names the encoder the HDR probe reached, which
 * on a Mac is `hevc_videotoolbox` rather than libx265.
 */
internal suspend fun suiteToolchainProblem(config: FfmpegConfig = FfmpegConfig()): String? {
  val located = ToolchainLocator(config, ProcessRunner()).toolchain()
  if (located is ToolchainResult.Unavailable) {
    val error = located.error
    return "the ffmpeg tests need ffmpeg and ffprobe $FLOOR or newer, and found `${error.tool}` " +
      "${error.foundVersion ?: "nowhere"}. ${error.message} Set $VARIABLE to an ffmpeg $FLOOR build to run them."
  }

  val capabilities = Filmstrip { ffmpegBackend(config) }.capabilities()
  if (capabilities.encodesHdr()) return null

  val version = (located as ToolchainResult.Available).toolchain.version.printed
  val encoder =
    (capabilities as? CapabilitiesResult.Success)
      ?.capabilities
      ?.video
      ?.hdrEncoder()
      ?.name
  val found =
    if (encoder == null) "ffmpeg $version has no encoder for it" else "$encoder on ffmpeg $version failed the HDR probe"
  return "the ffmpeg tests need an ffmpeg that writes ten-bit HEVC, and $found. " +
    "Set $VARIABLE to an ffmpeg $FLOOR build whose HDR encoder works to run them."
}

/**
 * Exits non-zero naming the problem when [suiteToolchainProblem] finds one, so the build stops before a single test
 * runs.
 */
fun main() {
  val problem = runBlocking { suiteToolchainProblem() } ?: return
  System.err.println(problem)
  exitProcess(1)
}

private const val FLOOR = "$MINIMUM_MAJOR.$MINIMUM_MINOR"

private const val VARIABLE = "FILMSTRIP_FFMPEG"
