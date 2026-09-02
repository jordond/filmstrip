package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.transform.internal.Mp4Copy.AUDIO
import dev.jordond.filmstrip.transform.internal.Mp4Copy.VIDEO
import dev.jordond.filmstrip.transform.internal.Mp4Copy.accepts

/**
 * Which source streams an mp4 muxer will take without re-encoding them.
 *
 * Every backend here writes mp4, so they all answer this the same way and read the answer from one
 * place. A muxer that takes more than the baseline passes its own additions to [accepts] rather
 * than keeping a second list.
 */
@InternalFilmstripApi
public object Mp4Copy {
  /**
   * Video codecs an mp4 muxer carries.
   */
  public val VIDEO: Set<CodecKind> =
    setOf(CodecKind.H264, CodecKind.Hevc, CodecKind.Vp9, CodecKind.Av1)

  /**
   * Audio codecs an mp4 muxer carries.
   */
  public val AUDIO: Set<CodecKind> =
    setOf(CodecKind.Aac, CodecKind.Mp3, CodecKind.Opus, CodecKind.Flac)

  /**
   * Whether a source's streams can be copied into mp4 rather than encoded.
   *
   * A source with no audio track passes on the audio half, since there is nothing to carry.
   *
   * @param info The source to copy from.
   * @param alsoVideo Video codecs this backend's muxer takes on top of [VIDEO].
   * @param alsoAudio Audio codecs this backend's muxer takes on top of [AUDIO].
   */
  public fun accepts(
    info: MediaInfo,
    alsoVideo: Set<CodecKind> = emptySet(),
    alsoAudio: Set<CodecKind> = emptySet(),
  ): Boolean {
    val video = info.video?.codec?.kind
    val audio = info.audio?.codec?.kind
    return (video in VIDEO || video in alsoVideo) &&
      (info.audio == null || audio in AUDIO || audio in alsoAudio)
  }
}
