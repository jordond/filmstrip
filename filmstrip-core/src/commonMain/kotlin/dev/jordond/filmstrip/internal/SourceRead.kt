package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult

/**
 * What a prober read from a source, with the one refusal a planner can still work around kept apart from the rest.
 *
 * A source whose video codec has no decoder here still has whatever audio it carries, so its read keeps the
 * [MediaInfo] next to the refusal. [toProbeResult] gives the answer [MediaProber.probe] reports, where that read is a
 * failure.
 */
@InternalFilmstripApi
public sealed interface SourceRead {
  /**
   * The answer [MediaProber.probe] gives for this read.
   */
  public fun toProbeResult(): ProbeResult

  /**
   * The source was read and its video, if any, decodes.
   *
   * @property info What the source turned out to be.
   */
  @InternalFilmstripApi
  public class Read(
    public val info: MediaInfo,
  ) : SourceRead {
    override fun toProbeResult(): ProbeResult = ProbeResult.Success(info)
  }

  /**
   * The source was read but its video codec has no decoder on this platform.
   *
   * @property info What the source turned out to be. Its video track is listed where the platform could read one.
   * @property error The refusal [MediaProber.probe] reports, built with [missingVideoDecoder].
   */
  @InternalFilmstripApi
  public class NoVideoDecoder(
    public val info: MediaInfo,
    public val error: FilmstripError.DecoderRejectedInput,
  ) : SourceRead {
    override fun toProbeResult(): ProbeResult = ProbeResult.Failure(error)
  }

  /**
   * The source could not be read.
   *
   * @property error Why the read failed.
   */
  @InternalFilmstripApi
  public class Failed(
    public val error: FilmstripError,
  ) : SourceRead {
    override fun toProbeResult(): ProbeResult = ProbeResult.Failure(error)
  }
}

/**
 * A [MediaProber] that answers with a [SourceRead], so a planner can tell a source with no video decoder from one it
 * cannot read at all.
 */
@InternalFilmstripApi
public interface SourceProber : MediaProber {
  /**
   * Reads [source], keeping the source's info when only its video has no decoder.
   */
  public suspend fun read(source: MediaSource): SourceRead

  override suspend fun probe(source: MediaSource): ProbeResult = read(source).toProbeResult()
}

/**
 * Reads [source] through this prober, as a [SourceRead] whether or not it is a [SourceProber].
 *
 * A prober that only answers [MediaProber.probe] never reports [SourceRead.NoVideoDecoder].
 */
@InternalFilmstripApi
public suspend fun MediaProber.readSource(source: MediaSource): SourceRead =
  if (this is SourceProber) read(source) else probe(source).toSourceRead()

/**
 * This answer as a [SourceRead], which is never [SourceRead.NoVideoDecoder] since a [ProbeResult] keeps no info with
 * its failure.
 */
@InternalFilmstripApi
public fun ProbeResult.toSourceRead(): SourceRead =
  when (this) {
    is ProbeResult.Success -> SourceRead.Read(info)
    is ProbeResult.Failure -> SourceRead.Failed(error)
  }

/**
 * The read of a source with [info] whose video codec has no decoder, named [codec] where it is known.
 */
@InternalFilmstripApi
public fun noVideoDecoder(
  info: MediaInfo,
  codec: String?,
): SourceRead.NoVideoDecoder = SourceRead.NoVideoDecoder(info, missingVideoDecoder(codec))
