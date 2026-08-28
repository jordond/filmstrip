@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.sample

public actual fun deviceInfo(): DeviceInfo = DeviceInfo(
  platform = "Browser",
  model = platformName().toString(),
  operatingSystem = userAgent().toString(),
  details = mapOf(
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
