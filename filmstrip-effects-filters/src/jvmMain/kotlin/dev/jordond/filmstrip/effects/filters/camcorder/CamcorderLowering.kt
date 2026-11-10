package dev.jordond.filmstrip.effects.filters.camcorder

import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.geometry.Size
import kotlin.math.ceil
import kotlin.time.Duration

/**
 * Resolves [camcorder] to its ffmpeg graph over the frames [attributes] describes.
 *
 * The sidecar has to know when the display stops, so a span with no end is refused by name.
 */
internal fun camcorderResolution(
  camcorder: Camcorder,
  attributes: Attributes,
): EffectResolution {
  val until =
    attributes.span.endExclusive
      ?: return EffectResolution.Unsupported(
        Camcorder.ID,
        "A camcorder display on ffmpeg needs to know where its frames end, and this span runs on with no end.",
      )
  val osd = CamcorderOsd(camcorder, attributes.frameRate)
  return EffectResolution.Resolved(
    PlatformEffect(camcorderFragment(osd, attributes.inputSize, attributes.span.start, until)),
  )
}

/**
 * The graph that draws a [Camcorder] on ffmpeg, laying an animated PNG of its display over the frame.
 *
 * The display only changes now and then, so the sidecar holds one PNG frame for each state [changesWithin] finds from
 * [from] until [until], held for exactly as long as that state is. Every change lands on the state clock, which the
 * frame delays name exactly, so the overlay switches on the same frame [CamcorderOsd.rasterAt] does. A frame after the
 * first holds only the region that changed.
 *
 * A counter's frame count changes every frame, but it repeats every second, so [osdFrameLoops] splits it off as a
 * sidecar of one second for each stretch the counts hold their place through, each looped on the clock, and the first
 * sidecar only changes a few times a second. The first is laid onto the loops in RGBA, which gives the display's raster
 * back pixel for pixel before it meets the frame, so the frame sees the same display either way.
 *
 * The frames run on the composition's clock, which the sidecars start at zero on, so a windowed preview lays the state
 * its own time shows. The overlay works on 4:2:0 codes, so luma lands on the raster's own code and chroma is filtered
 * over the pixels round each sample, which blends a dot's coloured edge with the pixels next to it.
 */
internal fun camcorderFragment(
  osd: CamcorderOsd,
  frame: Size,
  from: Duration,
  until: Duration,
): FilterFragment {
  val loops = osdFrameLoops(osd, frame, from, until)
  val sidecar = Sidecar(osdAnimation(osd, frame, from, until), "apng")
  val counts = loops.map { Sidecar(it.animation, "apng") }
  val display =
    if (loops.isEmpty()) {
      listOf(FilterBranch(emptyList(), listOf(movieOf(sidecar)) + toCodes(), listOf(DISPLAY)))
    } else {
      listOf(FilterBranch(emptyList(), listOf(movieOf(sidecar)), listOf(REST))) +
        countBranches(loops, counts, frame, wholeSecondOf(from), until) +
        FilterBranch(listOf(COUNTS, REST), listOf(overlayOf("repeat")) + toCodes(), listOf(DISPLAY))
    }
  return FilterFragment(
    branches =
      display +
        listOf(
          FilterBranch(listOf(FilterBranch.INPUT), RgbRoundTrip.opening(), listOf(PICTURE)),
          FilterBranch(
            listOf(PICTURE, DISPLAY),
            listOf(
              FilterNode("overlay", "format" to "yuv420", "alpha" to "straight", "eof_action" to "repeat"),
            ),
            listOf(LAID),
          ),
        ),
    sidecars = listOf(sidecar) + counts,
    readsCompositionClock = true,
  )
}

// The frame counts from [start] until [until] as one stream, which the rest of the display is laid onto. A single loop
// covering the whole span is that stream as it is. Otherwise each loop is laid over a clear stream the first loop
// times, only through its own stretch, so a count shows where its stretch puts it and nowhere else.
private fun countBranches(
  loops: List<OsdFrameLoop>,
  sidecars: List<Sidecar>,
  frame: Size,
  start: Duration,
  until: Duration,
): List<FilterBranch> {
  val only = loops.singleOrNull()
  if (only != null && only.from == start && only.until == until) {
    return listOf(FilterBranch(emptyList(), looped(sidecars[0], only.region, frame, start, until), listOf(COUNTS)))
  }
  val clear =
    FilterBranch(
      emptyList(),
      looped(sidecars[0], loops[0].region, frame, start, until) +
        FilterNode("colorchannelmixer", "rr" to "0", "gg" to "0", "bb" to "0", "aa" to "0"),
      listOf(UNDER),
    )
  val stretches =
    loops.mapIndexed { index, loop ->
      FilterBranch(
        emptyList(),
        looped(sidecars[index], loop.region, frame, loop.from, loop.until),
        listOf("$STRETCH$index"),
      )
    }
  val laid =
    loops.indices.map { index ->
      val under = if (index == 0) UNDER else "$COUNTS$index"
      val out = if (index == loops.lastIndex) COUNTS else "$COUNTS${index + 1}"
      FilterBranch(listOf(under, "$STRETCH$index"), listOf(overlayOf("pass")), listOf(out))
    }
  return listOf(clear) + stretches + laid
}

// Lays one RGBA stream over another as it is, which leaves the raster's opaque and clear pixels exact.
private fun overlayOf(eofAction: String): FilterNode =
  FilterNode("overlay", "format" to "rgb", "alpha" to "straight", "eof_action" to eofAction)

// The one second sidecar played once for each second from the whole second [from] falls in until [until], each pass
// starting a whole second after the last, cut to [from] until [until] and padded out to the [frame] it sits in at
// [region]. Its delays add up to exactly a second, which is the length `loop` moves each pass by.
private fun looped(
  sidecar: Sidecar,
  region: PixelRect,
  frame: Size,
  from: Duration,
  until: Duration,
): List<FilterNode> {
  val first = wholeSecondOf(from).inWholeMicroseconds
  val passes = ceil((until.inWholeMicroseconds - first).toDouble() / MICROS_PER_SECOND).toLong().coerceAtLeast(1)
  return listOf(
    movieOf(sidecar),
    FilterNode("loop", "loop" to "${passes - 1}", "size" to "$LOOP_FRAMES_CEILING", "start" to "0"),
    FilterNode("setpts", "expr" to "PTS+${first / UNIT_MICROS}"),
    FilterNode(
      "trim",
      "start_pts" to "${from.inWholeMicroseconds / UNIT_MICROS}",
      "end_pts" to "${(until.inWholeMicroseconds + UNIT_MICROS - 1) / UNIT_MICROS}",
    ),
    FilterNode(
      "pad",
      "width" to "${frame.width}",
      "height" to "${frame.height}",
      "x" to "${region.left}",
      "y" to "${region.top}",
      "color" to "black@0",
    ),
  )
}

// Named by demuxer so a path is only ever a path, and read with no frame rate cap so every delay holds as written.
private fun movieOf(sidecar: Sidecar): FilterNode =
  FilterNode("movie", "filename" to sidecar.placeholder, "f" to "apng", "format_opts" to "max_fps=0")

private fun toCodes(): List<FilterNode> =
  listOf(
    FilterNode("format", "pix_fmts" to "rgba"),
    FilterNode(
      "scale",
      "out_color_matrix" to "bt709",
      "out_range" to "tv",
      "flags" to "accurate_rnd+full_chroma_int",
    ),
    FilterNode("format", "pix_fmts" to "yuva420p"),
  )

private const val DISPLAY = "display"
private const val PICTURE = "picture"
private const val LAID = "laid"
private const val REST = "rest"
private const val COUNTS = "counts"
private const val UNDER = "under"
private const val STRETCH = "stretch"

// The APNG demuxer's time base is ten microseconds, which is what trim counts in.
private const val UNIT_MICROS = 10L

// The most frames `loop` holds. It stops at the sidecar's end, so a ceiling over any one second's count is enough.
private const val LOOP_FRAMES_CEILING = 32_767
