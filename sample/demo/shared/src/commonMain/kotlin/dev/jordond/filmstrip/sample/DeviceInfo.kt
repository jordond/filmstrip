package dev.jordond.filmstrip.sample

/**
 * What the app is running on, for the header of a bug report.
 *
 * @property platform The target, as the issue tracker spells it.
 * @property model The hardware, or the browser where there is no hardware to name.
 * @property operatingSystem The OS and its version.
 * @property details Anything else worth a line, such as the ABI or the core count.
 */
public class DeviceInfo(
  public val platform: String,
  public val model: String,
  public val operatingSystem: String,
  public val details: Map<String, String>,
)

/**
 * Reads what this device is.
 */
public expect fun deviceInfo(): DeviceInfo
