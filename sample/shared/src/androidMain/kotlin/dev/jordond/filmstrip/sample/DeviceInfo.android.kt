package dev.jordond.filmstrip.sample

import android.os.Build

public actual fun deviceInfo(): DeviceInfo = DeviceInfo(
  platform = "Android",
  model = "${Build.MANUFACTURER} ${Build.MODEL}",
  operatingSystem = "Android ${Build.VERSION.RELEASE} (API ${Build.VERSION.SDK_INT})",
  details = buildMap {
    put("device", Build.DEVICE)
    put("abis", Build.SUPPORTED_ABIS.joinToString(", "))
    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) put("soc", Build.SOC_MODEL)
    put("emulator", isEmulator().toString())
  },
)

// The fingerprint of every image built from AOSP without a vendor signature starts this way, which
// is what an emulator boots.
private fun isEmulator(): Boolean =
  Build.FINGERPRINT.startsWith("generic") ||
    Build.FINGERPRINT.startsWith("unknown") ||
    Build.MODEL.contains("Emulator") ||
    Build.MODEL.contains("Android SDK built for")
