package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.CapabilitiesResult
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
 * device is read once, and where a copy of each cut could open is read once per source and cut. A device read that
 * failed is not held, so the next caller asks again.
 *
 * @param readDevice Reads what this device's encoders can do, or why it could not.
 * @param readOpening Reads the sync sample at or before a cut in a source, or why it could not be read.
 */
@InternalFilmstripApi
public class PlanningReads(
  prober: MediaProber,
  private val readDevice: suspend () -> CapabilitiesResult,
  private val readOpening: suspend (MediaSource, Duration) -> OpeningRead,
) {
  private val probes = ProbeCache(prober)
  private var device: DeviceCapabilities? = null
  private val openings = mutableMapOf<Pair<MediaSource, Duration>, Duration?>()

  /**
   * What this device's encoders can do, read the first time anything asks, or why it could not be read.
   */
  public suspend fun device(): CapabilitiesResult =
    device?.let { CapabilitiesResult.Success(it) }
      ?: readDevice().also { if (it is CapabilitiesResult.Success) device = it.capabilities }

  /**
   * Reads what every source of [composition] is, the device, and where a copy of it could open.
   *
   * @return Everything read, or the first failure a probe, the device read or an opening read reported.
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
    val device =
      when (val result = device()) {
        is CapabilitiesResult.Success -> result.capabilities
        is CapabilitiesResult.Failure -> return PlanningReadResult.Failed(result.error)
      }
    var failed: FilmstripError? = null
    val openings =
      copyOpenings(composition) { source, cut ->
        when (val opening = openingFor(source, cut)) {
          is OpeningRead.At -> opening.opening
          is OpeningRead.Failed -> null.also { failed = opening.error }
        }
      }
    failed?.let { return PlanningReadResult.Failed(it) }
    return PlanningReadResult.Read(infos, device, openings)
  }

  // A failed read is not held, so the next negotiation asks again.
  private suspend fun openingFor(
    source: MediaSource,
    cut: Duration,
  ): OpeningRead {
    val key = source to cut
    if (key in openings) return OpeningRead.At(openings[key])
    return readOpening(source, cut).also { if (it is OpeningRead.At) openings[key] = it.opening }
  }
}

/**
 * What reading the sync sample at or before a cut came back with.
 */
@InternalFilmstripApi
public sealed interface OpeningRead {
  /**
   * The read finished.
   *
   * @property opening The sync sample at or before the cut, or null when there is none this engine can name.
   */
  @InternalFilmstripApi
  public class At(
    public val opening: Duration?,
  ) : OpeningRead

  /**
   * The read could not finish.
   *
   * @property error Why it could not.
   */
  @InternalFilmstripApi
  public class Failed(
    public val error: FilmstripError,
  ) : OpeningRead
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
   * A source, the device or an opening could not be read.
   *
   * @property error Why it could not.
   */
  @InternalFilmstripApi
  public class Failed(
    public val error: FilmstripError,
  ) : PlanningReadResult
}
