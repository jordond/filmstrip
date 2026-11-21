package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.Filmstrip
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.launch

/**
 * What the device's encoders said they can do, read on request and kept until the next read.
 */
@Stable
public class DeviceCapabilities internal constructor(
  private val filmstrip: Filmstrip,
  private val scope: CoroutineScope,
  private val recorder: DiagnosticsRecorder,
) {
  var result: CapabilitiesResult? by mutableStateOf(null)
    private set
  var loading: Boolean by mutableStateOf(false)
    private set

  /**
   * Why this device can't export at all, or null when it can or hasn't answered yet.
   */
  val exportRefusal: String? get() = exportRefusalOf(result)

  /**
   * Asks the device again, unless it is already being asked.
   */
  fun refresh() {
    if (loading) return
    loading = true
    scope.launch {
      try {
        val read = filmstrip.capabilities()
        result = read
        recorder.record("capabilities.read", "result" to (read::class.simpleName ?: "unknown"))
      } finally {
        loading = false
      }
    }
  }
}
