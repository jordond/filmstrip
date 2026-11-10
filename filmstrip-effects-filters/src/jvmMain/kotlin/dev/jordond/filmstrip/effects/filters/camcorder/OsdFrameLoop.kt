package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration
import kotlin.time.Duration.Companion.microseconds

// The frame counts of a display from [from] until [until] as a one second animated PNG of [region], looped on ffmpeg
// under the rest of the display. A frame count is a function of the fraction of its second alone, so one second of it
// repeats for as long as the counts hold their place.
internal class OsdFrameLoop(
  val from: Duration,
  val until: Duration,
  val region: PixelRect,
  val animation: ByteArray,
)

// The frame counts of [osd] from [from] until [until] split off as loops, one for each stretch the counts hold their
// place through, and none when the display has no frame count inside the frame. The first loop starts at the whole
// second [from] falls in.
//
// A loop draws only the frame counts and the rest of the display draws everything else over it, the order the raster
// draws them in, so laid together they are the raster. A count only moves when a counter in a left corner grows an
// hour digit, so the stretches are few. A count wholly past the frame's edge draws nothing and is left out.
//
// The rest of the display is laid onto the loop, so a loop also holds a frame wherever the rest changes within a
// second, which the span's own changes say.
internal fun osdFrameLoops(
  osd: CamcorderOsd,
  frame: Size,
  from: Duration,
  until: Duration,
): List<OsdFrameLoop> {
  val counters = osd.frameCountLines
  if (counters.isEmpty()) return emptyList()
  val base = wholeSecondOf(from)
  val checked = osd.changesWithin(base, maxOf(until, base + ONE_SECOND), withFrames = false)
  val restChanges = checked.map { it.at.inWholeMicroseconds % MICROS_PER_SECOND }.distinct()
  val bounds = checked.map { countBounds(osd, it.state, frame, counters) }
  val firsts = checked.indices.filter { it == 0 || bounds[it] != bounds[it - 1] }
  return firsts.mapIndexedNotNull { order, index ->
    val start = if (index == 0) base else checked[index].at
    val end = firsts.getOrNull(order + 1)?.let { minOf(checked[it].at, until) } ?: until
    val drawn = bounds[index].values.filterNot { it.isEmpty }
    if (start >= end || drawn.isEmpty()) return@mapIndexedNotNull null
    val region = drawn.reduce(PixelRect::union)
    OsdFrameLoop(start, end, region, loopAnimation(osd, frame, region, checked[index].state, restChanges))
  }
}

// The pixels each counter's frame count can touch, edge included, by line.
private fun countBounds(
  osd: CamcorderOsd,
  state: OsdState,
  frame: Size,
  counters: List<Int>,
): Map<Int, PixelRect> =
  counters.associateWith { line ->
    val length = state.lines[line].runs.sumOf { it.text.length }
    osd.charBounds(state, frame.width, frame.height, line, length - frameDigits(osd.rate) until length)
  }

// One second of the frame counts inside [region] laid out as [layout] has them, with the rest of the display hidden,
// starting at a whole second, a frame each time a count changes and each time in [restChanges].
private fun loopAnimation(
  osd: CamcorderOsd,
  frame: Size,
  region: PixelRect,
  layout: OsdState,
  restChanges: List<Long>,
): ByteArray {
  val counters = osd.frameCountLines
  val starts = listOf(0L) + frameStartsWithinSecond(osd.rate)
  val counts =
    counters.flatMap { line ->
      val offset = (osd.spec.elements[line] as OsdElement.Counter).start.inWholeMicroseconds
      starts.map { start -> ceilToClock(Math.floorMod(start - offset, MICROS_PER_SECOND)) % MICROS_PER_SECOND }
    }
  val times = (listOf(0L) + counts + restChanges).distinct().sorted()
  val writer = ApngWriter(Size(region.width, region.height), times.size)
  val whole = PixelRect(0, 0, region.width, region.height)
  times.forEachIndexed { index, at ->
    val next = times.getOrNull(index + 1) ?: MICROS_PER_SECOND
    val state = framesOnly(countsOf(layout, osd.stateAt(at.microseconds), counters), counters)
    val raster = osd.rasterOf(state, frame.width, frame.height, region)
    writer.frame(whole, raster.rgbaBytes(), Delay(((next - at) / OsdParams.STATE_CLOCK_MICROS).toInt(), TICKS))
  }
  return writer.finish()
}

// [layout] with the frame count of each of [counters] taken from [shown], which keeps every count where [layout] puts
// it whatever second [shown] reads.
private fun countsOf(
  layout: OsdState,
  shown: OsdState,
  counters: List<Int>,
): OsdState =
  OsdState(
    layout.lines.mapIndexed { index, line ->
      if (index in counters) line.copy(runs = line.runs.dropLast(1) + shown.lines[index].runs.last()) else line
    },
  )

private fun ceilToClock(micros: Long): Long =
  (micros + OsdParams.STATE_CLOCK_MICROS - 1) / OsdParams.STATE_CLOCK_MICROS * OsdParams.STATE_CLOCK_MICROS

internal fun wholeSecondOf(time: Duration): Duration =
  (time.inWholeMicroseconds.coerceAtLeast(0) / MICROS_PER_SECOND * MICROS_PER_SECOND).microseconds

private val ONE_SECOND = MICROS_PER_SECOND.microseconds
private const val TICKS = TICKS_PER_SECOND.toInt()
