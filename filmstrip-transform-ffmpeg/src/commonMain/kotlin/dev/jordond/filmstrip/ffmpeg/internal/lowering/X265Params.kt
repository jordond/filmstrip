package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.ffmpeg.FfmpegConfig

/**
 * A caller's extra arguments, split into what joins filmstrip's own `-x265-params` and what stays ahead of the output.
 *
 * @property x265Params The value of every `-x265-params` pair lifted out, in the order given.
 * @property tail Everything else, in the order given.
 */
internal class ExtraArguments(
  val x265Params: List<String>,
  val tail: List<String>,
)

/**
 * Splits [config]'s extra arguments for this encode.
 *
 * ffmpeg keeps only the last value of a repeated option, so a caller's `-x265-params` after filmstrip's would drop the
 * keys filmstrip wrote. When libx265 encodes and filmstrip writes keys of its own, the caller's values are lifted out
 * to go ahead of them inside the one option, where x265 lets a later key win. Any other encode keeps every argument
 * where the caller put it.
 */
internal fun Invocation.extraArguments(config: FfmpegConfig): ExtraArguments {
  val merges = !copy && videoLabel != null && videoEncoder == "libx265" && x265Params().isNotEmpty()
  if (!merges) return ExtraArguments(emptyList(), config.extraArgs)

  val lifted = mutableListOf<String>()
  val tail = mutableListOf<String>()
  var index = 0
  while (index < config.extraArgs.size) {
    val argument = config.extraArgs[index]
    if (argument == X265_PARAMS_FLAG && index + 1 < config.extraArgs.size) {
      lifted += config.extraArgs[index + 1]
      index += 2
    } else {
      tail += argument
      index++
    }
  }
  return ExtraArguments(lifted, tail)
}

internal fun Invocation.x265Params(): List<String> =
  buildList {
    // The -color_* flags tag the container. libx265 does not pick them up on its own, so HDR10 signalling has to be
    // repeated in its own params or a player reading the elementary stream sees SDR.
    hdrTransfer?.let {
      add("colorprim=$HDR_PRIMARIES")
      add("transfer=${it.ffmpegTag}")
      add("colormatrix=$HDR_MATRIX")
      add("hdr10=1")
      add("repeat-headers=1")
    }
    if (output.size.width in X265_CUTREE_CRASH_WIDTHS) add("cutree=0")
    if (output.keyFrameSpacing != null) add("scenecut=0")
  }

// A limit of libx265 itself. Version 4.1 corrupts its heap and aborts on a frame this wide, at any height, rate or bit
// depth, unless cutree is off. 4.3 encodes these widths either way.
private val X265_CUTREE_CRASH_WIDTHS = 34..48

internal const val X265_PARAMS_FLAG = "-x265-params"
