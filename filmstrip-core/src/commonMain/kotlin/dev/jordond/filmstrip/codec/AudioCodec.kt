package dev.jordond.filmstrip.codec

import dev.jordond.filmstrip.edit.AudioSpec

/**
 * An audio codec filmstrip can produce, whether by encoding to it or by copying it across from a
 * source untouched.
 */
public enum class AudioCodec {
  /**
   * Let filmstrip choose.
   */
  Auto,

  /**
   * AAC.
   */
  Aac,

  /**
   * Apple Lossless.
   */
  Alac,

  /**
   * Opus. No backend encodes it, so it only ever reaches an output by copy.
   */
  Opus,

  /**
   * MP3. No backend encodes it, so it only ever reaches an output by copy.
   */
  Mp3,

  /**
   * FLAC. No backend encodes it, so it only ever reaches an output by copy.
   */
  Flac,

  /**
   * Vorbis. No backend encodes it, so it only ever reaches an output by copy.
   */
  Vorbis,

  /**
   * Write no audio track. To keep a silent track instead, see [AudioSpec].
   */
  None,
}
