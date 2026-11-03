package dev.jordond.filmstrip

import dev.drewhamilton.poko.Poko
import dev.jordond.filmstrip.capability.DeviceCapabilities

/**
 * The outcome of asking what this device can encode.
 *
 * A missing export backend is a [Failure] naming the artifact, not an empty encoder list.
 */
public sealed interface CapabilitiesResult {
  /**
   * The device was probed.
   *
   * @property capabilities What this device's encoders turned out to support.
   */
  @Poko
  public class Success(
    public val capabilities: DeviceCapabilities,
  ) : CapabilitiesResult

  /**
   * The device could not be probed.
   *
   * @property error Why the probe failed.
   */
  @Poko
  public class Failure(
    public val error: FilmstripError,
  ) : CapabilitiesResult
}
