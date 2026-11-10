package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effect.WebGlTable
import kotlin.time.Duration

/**
 * A [camcorder] as one browser pass that lays the display [CamcorderOsd.rasterAt] draws over the frame.
 *
 * The raster is a table at the frame's own size, read texel for texel, and a new one is only built when the display
 * changes, drawing only the region that changed, so the compositor uploads once per state.
 */
internal fun camcorderPass(
  camcorder: Camcorder,
  frameRate: Float?,
): WebGlPass {
  val tables = OsdTables(CamcorderOsd(camcorder, frameRate))
  return WebGlPass(
    Camcorder.ID,
    listOf(
      WebGlStep(
        name = "display",
        fragmentSource = CamcorderShader.FRAGMENT,
        reads = mapOf(CamcorderShader.FRAME to WebGlStep.INPUT),
        texturesAt = { time, width, height -> mapOf(CamcorderShader.DISPLAY to tables.at(time, width, height)) },
      ) { _, _, _ -> emptyMap() },
    ),
  )
}

// The table for the last display drawn, held so frames sharing a state hand the compositor the same instance. A new
// state copies the last table's bytes and draws only the region that changed over them, since a table is never changed
// once the compositor holds it.
private class OsdTables(
  private val osd: CamcorderOsd,
) {
  private var key: Triple<OsdState, Int, Int>? = null
  private var table: WebGlTable? = null

  fun at(
    time: Duration,
    width: Int,
    height: Int,
  ): WebGlTable {
    val next = Triple(osd.stateAt(time), width, height)
    val held = table
    val last = key
    if (held != null && next == last) return held
    val bytes =
      if (held != null && last != null && last.second == width && last.third == height) {
        val region = osd.changedRegion(last.first, next.first, width, height) ?: return remember(next, held)
        held.bytes.copyOf().also { osd.redrawInto(it, next.first, width, height, region) }
      } else {
        osd.rasterOf(next.first, width, height).rgbaBytes()
      }
    return remember(next, WebGlTable(width, height, bytes))
  }

  private fun remember(
    next: Triple<OsdState, Int, Int>,
    drawn: WebGlTable,
  ): WebGlTable {
    key = next
    table = drawn
    return drawn
  }
}
