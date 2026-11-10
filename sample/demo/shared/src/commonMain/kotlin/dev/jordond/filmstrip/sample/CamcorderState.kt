package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderBuilder

/**
 * The camcorder display the editor draws, which elements it shows and whether the tape records it.
 *
 * When [burned] is set the display goes ahead of the VHS look and is degraded with the picture. Otherwise it goes after
 * every look and stays clean.
 */
@Stable
public class CamcorderState {
  public var enabled: Boolean by mutableStateOf(false)
  public var rec: Boolean by mutableStateOf(true)
  public var dateAndClock: Boolean by mutableStateOf(true)
  public var counter: Boolean by mutableStateOf(false)
  public var battery: Boolean by mutableStateOf(false)
  public var burned: Boolean by mutableStateOf(true)

  internal val shown: Boolean
    get() = enabled && (rec || dateAndClock || counter || battery)

  internal fun reset() {
    enabled = false
    rec = true
    dateAndClock = true
    counter = false
    battery = false
    burned = true
  }

  internal val elements: CamcorderBuilder.() -> Unit = {
    if (rec) rec()
    if (counter) counter(withFrames = true)
    if (battery) battery(level = LOW_BATTERY)
    if (dateAndClock) {
      date()
      clock()
    }
  }

  private companion object {
    const val LOW_BATTERY = 0.25f
  }
}
