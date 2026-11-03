package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.copy.copyOpenings
import kotlin.time.Duration

/**
 * Reads everything [dev.jordond.filmstrip.transform.internal.ExportPlanner.negotiate] needs from outside the edit,
 * holding each answer for the life of the engine.
 *
 * An engine negotiates the same edit to plan it, to resolve it for a preview and again to export
 * it, and none of these answers can move between those. Sources go through a [ProbeCache], the
 * device is read once, and where a copy of each cut could open is read once per source and cut.
 *
 * @param readDevice Reads what this device's encoders can do.
 * @param readOpening Reads the sync sample at or before a cut in a source, or null when there is
 *   none this engine can name.
 */
@InternalFilmstripApi
public class PlanningReads(
  prober: MediaProber,
  private val readDevice: suspend () -> DeviceCapabilities,
  private val readOpening: suspend (MediaSource, Duration) -> Duration?,
) {
  private val probes = ProbeCache(prober)
  private var device: DeviceCapabilities? = null
  private val openings = mutableMapOf<Pair<MediaSource, Duration>, Duration?>()

  /**
   * What this device's encoders can do, read the first time anything asks.
   */
  public suspend fun device(): DeviceCapabilities = device ?: readDevice().also { device = it }

  /**
   * Reads what every source of [composition] is, the device, and where a copy of it could open.
   *
   * @return Everything read, or the first failure a probe reported.
   */
  public suspend fun read(composition: EditComposition): PlanningReadResult {
    val infos = mutableMapOf<MediaSource, MediaInfo>()
    for (clip in composition.tracks.flatMap { it.clips }) {
      if (clip.source in infos) continue
      when (val result = probes.probe(clip.source)) {
        is ProbeResult.Success -> infos[clip.source] = result.info
        is ProbeResult.Failure -> return PlanningReadResult.Failed(result.error)
      }
    }
    return PlanningReadResult.Read(infos, device(), copyOpenings(composition, ::openingFor))
  }

  private suspend fun openingFor(
    source: MediaSource,
    cut: Duration,
  ): Duration? {
    val key = source to cut
    if (key in openings) return openings[key]
    return readOpening(source, cut).also { openings[key] = it }
  }
}

/**
 * What [PlanningReads.read] came back with.
 */
@InternalFilmstripApi
public sealed interface PlanningReadResult {
  /**
   * Everything a negotiation is asked against.
   *
   * @property infos What each source the composition names turned out to be.
   * @property openings Where a stream copy of each source could open, keyed the way
   *   [dev.jordond.filmstrip.transform.internal.ExportPlanner.negotiate] reads it.
   */
  @InternalFilmstripApi
  public class Read(
    public val infos: Map<MediaSource, MediaInfo>,
    public val device: DeviceCapabilities,
    public val openings: Map<MediaSource, Duration>,
  ) : PlanningReadResult

  /**
   * A source could not be read.
   *
   * @property error Why it could not.
   */
  @InternalFilmstripApi
  public class Failed(
    public val error: FilmstripError,
  ) : PlanningReadResult
}
