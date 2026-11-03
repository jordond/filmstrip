package dev.jordond.filmstrip.still

/**
 * An image format a still can be encoded in.
 *
 * [Png] and [Jpeg] are writable on every target. [Webp] is not, and says so on its own entry.
 */
public enum class StillFormat {
  /**
   * PNG. Lossless, so [StillSpec.quality] has no effect on it.
   */
  Png,

  /**
   * JPEG. Lossy, and [StillSpec.quality] chooses how lossy.
   */
  Jpeg,

  /**
   * WebP, the one format that is not writable everywhere.
   *
   * Android and a browser encode it. The JDK ships no WebP writer, and Apple's ImageIO reads WebP
   * on systems where it will not write it, so both refuse with
   * [dev.jordond.filmstrip.FilmstripError.UnsupportedStillFormat] naming the target rather
   * than handing back a file that is not WebP. Ask for [Png] or [Jpeg] in code that has to run on
   * all four.
   */
  Webp,
}
