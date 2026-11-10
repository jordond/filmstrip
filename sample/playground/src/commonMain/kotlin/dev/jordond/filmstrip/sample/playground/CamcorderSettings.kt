package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.effects.filters.camcorder.CamcorderBuilder

/**
 * Where the VHS look's camcorder display goes. [OnTape] records it under the look so it degrades with the picture,
 * [Clean] draws it after the look.
 */
internal enum class CamcorderMode(
  val label: String,
) {
  Off("OFF"),
  OnTape("ON TAPE"),
  Clean("CLEAN"),
}

/**
 * One piece of the camcorder display the filters sample can show, with the calls that add it in the code listing and
 * on the builder.
 */
internal enum class CamcorderPart(
  val label: String,
  val code: List<String>,
  val add: CamcorderBuilder.() -> Unit,
) {
  Rec("REC", listOf("rec()"), { rec() }),
  DateAndClock("DATE", listOf("date()", "clock()"), {
    date()
    clock()
  }),
  Counter("COUNTER", listOf("counter(withFrames = true)"), { counter(withFrames = true) }),
  Battery("BATTERY", listOf("battery(${floatLiteral(LOW_BATTERY)})"), { battery(LOW_BATTERY) }),
}

/**
 * The camcorder display the filters sample draws with its VHS look, where it goes and which parts it shows.
 */
internal data class CamcorderSettings(
  val mode: CamcorderMode = CamcorderMode.Off,
  val parts: Set<CamcorderPart> = setOf(CamcorderPart.Rec, CamcorderPart.DateAndClock),
) {
  /**
   * Whether any display is drawn, which needs a mode other than [CamcorderMode.Off] and at least one part.
   */
  val shown: Boolean get() = mode != CamcorderMode.Off && parts.isNotEmpty()

  /**
   * The shown parts in the order the builder adds them.
   */
  val ordered: List<CamcorderPart> get() = CamcorderPart.entries.filter { it in parts }

  /**
   * These settings with [part] shown if it was hidden, or hidden if it was shown.
   */
  fun toggle(part: CamcorderPart): CamcorderSettings = copy(parts = if (part in parts) parts - part else parts + part)

  /**
   * Adds every shown part to [builder].
   */
  fun addTo(builder: CamcorderBuilder) {
    ordered.forEach { it.add(builder) }
  }
}

private const val LOW_BATTERY = 0.25f
