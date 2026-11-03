package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.media.HdrTransfer

/**
 * The color primaries every HDR grade this backend writes carries.
 */
internal const val HDR_PRIMARIES: String = "bt2020"

/**
 * The color matrix every HDR grade this backend writes carries.
 */
internal const val HDR_MATRIX: String = "bt2020nc"

/**
 * How ffmpeg spells [HdrTransfer] in a color tag.
 *
 * The same string names the transfer on `-color_trc`, inside `-x265-params` and on the `setparams`
 * node that labels a generated frame, and all three read it from here.
 */
internal val HdrTransfer.ffmpegTag: String
  get() =
    when (this) {
      HdrTransfer.Pq -> "smpte2084"
      HdrTransfer.Hlg -> "arib-std-b67"
    }
