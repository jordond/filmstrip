package dev.jordond.filmstrip.ffmpeg.internal.picture

import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain

// The resolvers the planner lowers with, with the built-in catalogue handed a way to read an overlay
// picture through [toolchain]'s ffmpeg, the binary that opens the same picture on an SDR grade. Only a
// resolver that is exactly the built-in catalogue is swapped, since one wrapping it has no seam to reach.
internal expect fun decodingOverlayPictures(
  resolvers: List<EffectResolver>,
  toolchain: Toolchain,
): List<EffectResolver>
