@file:OptIn(ExperimentalWasmJsInterop::class)

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
 * Reads what this browser is.
 *
 * There is no hardware to name, so the model is the browser's platform string and the rest comes off `navigator`.
 */
public fun deviceInfo(): DeviceInfo =
  DeviceInfo(
    platform = "Browser",
    model = platformName().toString(),
    operatingSystem = userAgent().toString(),
    details =
      mapOf(
        "cores" to hardwareConcurrency().toString(),
        "deviceMemoryGb" to deviceMemory().toString(),
        "crossOriginIsolated" to crossOriginIsolated().toString(),
        "webCodecs" to hasWebCodecs().toString(),
      ),
  )

private fun userAgent(): JsString = js("navigator.userAgent")

private fun platformName(): JsString = js("navigator.platform")

private fun hardwareConcurrency(): JsString = js("String(navigator.hardwareConcurrency ?? 'unknown')")

// Chromium only, and absent everywhere else, which is itself worth reporting.
private fun deviceMemory(): JsString = js("String(navigator.deviceMemory ?? 'unknown')")

private fun crossOriginIsolated(): JsString = js("String(self.crossOriginIsolated)")

private fun hasWebCodecs(): JsString = js("String(typeof VideoEncoder !== 'undefined')")
