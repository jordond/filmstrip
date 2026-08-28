package dev.jordond.filmstrip.sample

public actual fun deviceInfo(): DeviceInfo = DeviceInfo(
  platform = "Desktop JVM",
  model = property("os.arch"),
  operatingSystem = "${property("os.name")} ${property("os.version")}",
  details = mapOf(
    "java" to property("java.version"),
    "vm" to "${property("java.vm.name")} ${property("java.vm.version")}",
    "cores" to Runtime.getRuntime().availableProcessors().toString(),
    "maxHeapMb" to (Runtime.getRuntime().maxMemory() / 1024 / 1024).toString(),
  ),
)

private fun property(name: String): String = System.getProperty(name) ?: "unknown"
