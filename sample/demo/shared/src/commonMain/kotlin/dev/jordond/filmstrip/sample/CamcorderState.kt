package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderBuilder
import dev.jordond.filmstrip.effects.filters.camcorder.ClockStyle
import dev.jordond.filmstrip.effects.filters.camcorder.DateStyle
import dev.jordond.filmstrip.effects.filters.camcorder.RecordedAt
import dev.jordond.filmstrip.effects.filters.camcorder.TransportMode
import dev.jordond.filmstrip.effects.filters.tape.TapeStock

/**
 * What the display says the deck is doing. [Rec] is the red dot, the rest are the transport words a deck shows on
 * playback, and [Off] leaves the corner empty.
 */
public enum class CamcorderTransport {
  Rec,
  Play,
  Pause,
  Stop,
  Off,
}

/**
 * The camcorder display the editor draws, which elements it shows and whether the tape records it.
 *
 * When [burned] is set the display goes ahead of the VHS look and is degraded with the picture. Otherwise it goes after
 * every look and stays clean.
 */
@Stable
public class CamcorderState {
  public var enabled: Boolean by mutableStateOf(false)
  public var transport: CamcorderTransport by mutableStateOf(CamcorderTransport.Rec)
  public var dateAndClock: Boolean by mutableStateOf(true)
  public var dateStyle: DateStyle by mutableStateOf(DateStyle.MonthDayYear)
  public var clockStyle: ClockStyle by mutableStateOf(ClockStyle.TwelveHour)
  public var recordedAt: RecordedAt? by mutableStateOf(null)
  public var counter: Boolean by mutableStateOf(false)
  public var battery: Boolean by mutableStateOf(false)
  public var tapeBadge: Boolean by mutableStateOf(false)
  public var label: String by mutableStateOf("")
  public var burned: Boolean by mutableStateOf(true)

  internal val shown: Boolean
    get() =
      enabled &&
        (transport != CamcorderTransport.Off || dateAndClock || counter || battery || tapeBadge || label.isNotBlank())

  internal fun reset() {
    enabled = false
    transport = CamcorderTransport.Rec
    dateAndClock = true
    dateStyle = DateStyle.MonthDayYear
    clockStyle = ClockStyle.TwelveHour
    recordedAt = null
    counter = false
    battery = false
    tapeBadge = false
    label = ""
    burned = true
  }

  /**
   * The display as builder calls, with [stock] being the tape the badge names.
   */
  internal fun elements(stock: TapeStock): CamcorderBuilder.() -> Unit =
    {
      this@CamcorderState.recordedAt?.let { recordedAt = it }
      when (transport) {
        CamcorderTransport.Rec -> rec()
        CamcorderTransport.Play -> transport(TransportMode.Play)
        CamcorderTransport.Pause -> transport(TransportMode.Pause)
        CamcorderTransport.Stop -> transport(TransportMode.Stop)
        CamcorderTransport.Off -> Unit
      }
      if (counter) counter(withFrames = true)
      if (battery) battery(level = LOW_BATTERY)
      if (tapeBadge) tape(stock)
      if (dateAndClock) {
        date(style = dateStyle)
        clock(style = clockStyle)
      }
      if (label.isNotBlank()) label(label)
    }

  public companion object {
    private const val LOW_BATTERY = 0.25f

    /**
     * The dates the editor offers for the display, beside the library's own.
     */
    public val RecordedDates: List<Pair<String, RecordedAt?>> =
      listOf(
        "Default" to null,
        "Xmas 1989" to RecordedAt(1989, 12, 25, 8, 15, 0),
        "Y2K" to RecordedAt(1999, 12, 31, 23, 59, 30),
      )
  }
}
