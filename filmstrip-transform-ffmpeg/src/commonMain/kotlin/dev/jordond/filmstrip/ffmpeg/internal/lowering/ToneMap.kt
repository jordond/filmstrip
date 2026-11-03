package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain

/**
 * Which filter chain this build can bring an HDR grade down to SDR with.
 *
 * [Zscale] undoes the transfer into linear light, runs the curve, then writes BT.709 back out. It
 * needs `zscale`, which wants ffmpeg built with `--enable-libzimg`. [Libplacebo] does the same job
 * in one node and needs `libplacebo`, which reads PQ and HLG natively and ships in more static and
 * distro builds than zimg does.
 */
internal enum class ToneMapRoute {
  Zscale,
  Libplacebo,
}

/**
 * The tone-map route this toolchain can take, or null when it has neither filter.
 *
 * Prefers [ToneMapRoute.Zscale] when a build carries both, since that is the chain this backend has
 * tested and pinned.
 */
internal fun toneMapRoute(toolchain: Toolchain): ToneMapRoute? =
  when {
    toolchain.hasFilter("zscale") -> ToneMapRoute.Zscale
    toolchain.hasFilter("libplacebo") -> ToneMapRoute.Libplacebo
    else -> null
  }

/**
 * The grade stage, run on a clip that carries HDR before anything else touches it.
 *
 * On [ToneMapRoute.Zscale], linear light is where a tone curve belongs, so the transfer is undone,
 * the curve applied and the result written back out as BT.709. On [ToneMapRoute.Libplacebo], one
 * node does the whole job: it reads the source transfer natively and delivers BT.709 output
 * directly. Either way every resolver downstream is handed BT.709 attributes.
 */
internal fun toneMapNodes(route: ToneMapRoute): List<FilterNode> =
  when (route) {
    ToneMapRoute.Zscale -> {
      listOf(
        FilterNode("zscale", "transfer" to "linear", "npl" to "100"),
        FilterNode("tonemap", "tonemap" to "hable", "desat" to "0"),
        FilterNode("zscale", "primaries" to "bt709", "transfer" to "bt709", "matrix" to "bt709"),
      )
    }
    ToneMapRoute.Libplacebo -> {
      listOf(
        FilterNode(
          "libplacebo",
          "tonemapping" to "bt.2390",
          "colorspace" to "bt709",
          "color_primaries" to "bt709",
          "color_trc" to "bt709",
          "range" to "tv",
        ),
      )
    }
  }
