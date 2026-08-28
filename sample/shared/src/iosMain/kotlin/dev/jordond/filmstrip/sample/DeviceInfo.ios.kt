@file:OptIn(ExperimentalForeignApi::class)

package dev.jordond.filmstrip.sample

import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.toKString
import platform.Foundation.NSProcessInfo
import platform.UIKit.UIDevice
import platform.posix.uname
import platform.posix.utsname

public actual fun deviceInfo(): DeviceInfo {
  val device = UIDevice.currentDevice
  val simulator = NSProcessInfo.processInfo.environment["SIMULATOR_DEVICE_NAME"] as? String
  val identifier = hardwareModel()

  return DeviceInfo(
    platform = "iOS",
    model = simulator ?: identifier,
    operatingSystem = "${device.systemName} ${device.systemVersion}",
    details = mapOf(
      "identifier" to identifier,
      "simulator" to (simulator != null).toString(),
      "cores" to NSProcessInfo.processInfo.processorCount.toString(),
    ),
  )
}

/**
 * What `uname` calls the machine, such as `iPhone16,1`.
 *
 * The marketing name is not readable at runtime, and on a simulator this reports the host's
 * architecture rather than the device being simulated, which is why the simulator name is
 * preferred over it where there is one.
 */
private fun hardwareModel(): String = memScoped {
  val info = alloc<utsname>()
  if (uname(info.ptr) != 0) return@memScoped "unknown"
  info.machine.toKString()
}
