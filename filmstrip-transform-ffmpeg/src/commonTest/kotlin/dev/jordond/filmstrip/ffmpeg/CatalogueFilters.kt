package dev.jordond.filmstrip.ffmpeg

/**
 * The filters the built-in effects lower onto, for a build a test hands the planner by hand.
 *
 * A blur's `gblur` and a dim's `colorchannelmixer` are left out, since the fill's own refusals are read without them.
 */
internal val CATALOGUE_FILTERS: Set<String> =
  setOf(
    "amix",
    "concat",
    "crop",
    "drawtext",
    "eq",
    "format",
    "geq",
    "hflip",
    "hue",
    "lut3d",
    "lutrgb",
    "lutyuv",
    "overlay",
    "pad",
    "rotate",
    "scale",
    "setpts",
    "split",
    "transpose",
    "vflip",
    "zoompan",
  )

/**
 * The filters a Vhs fragment lowers onto, every name its own check has to find.
 */
internal val VHS_FILTERS: Set<String> =
  setOf("scale", "format", "split", "pad", "geq", "gblur", "lut2", "crop", "setpts")

/**
 * The filters a Film fragment lowers onto at 720p, every name its own check has to find.
 */
internal val FILM_FILTERS: Set<String> =
  setOf("scale", "format", "split", "mergeplanes", "geq", "gblur", "lut2", "setpts")

/**
 * The filters a Crt fragment lowers onto, every name its own check has to find.
 */
internal val CRT_FILTERS: Set<String> =
  setOf(
    "scale",
    "format",
    "split",
    "premultiply",
    "unpremultiply",
    "remap",
    "maskedmerge",
    "mergeplanes",
    "blend",
    "lutrgb",
    "movie",
    "null",
  )

/**
 * The filters a Lut fragment lowers onto, every name its own check has to find.
 */
internal val LUT_FILTERS: Set<String> = setOf("scale", "format", "lut3d")

/**
 * The filters a NightVision fragment lowers onto at 720p, every name its own check has to find.
 */
internal val NIGHT_VISION_FILTERS: Set<String> =
  setOf(
    "scale",
    "format",
    "split",
    "geq",
    "extractplanes",
    "mergeplanes",
    "colorchannelmixer",
    "blend",
    "lut1d",
    "movie",
    "lutrgb",
    "setpts",
  )

/**
 * The filters a Glitch fragment lowers onto, every name its own check has to find.
 */
internal val GLITCH_FILTERS: Set<String> = setOf("scale", "format", "geq", "setpts")
