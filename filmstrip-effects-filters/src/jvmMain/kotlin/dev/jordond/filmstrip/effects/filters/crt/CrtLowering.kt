package dev.jordond.filmstrip.effects.filters.crt

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.effects.AHEAD_OF_THE_CLIP
import dev.jordond.filmstrip.effects.RgbRoundTrip
import dev.jordond.filmstrip.geometry.Size
import java.lang.ref.WeakReference

/**
 * The graph that draws a [Crt] on ffmpeg, on [frame], with every figure read from [CrtSignal] through [CrtFrames].
 *
 * The frames take a moment and a fair amount of memory to build, so every clip of one size and strength shares one
 * fragment for as long as anything holds it. The picture is scaled to [frame] first, since the maps are that size and
 * a clip with non-square pixels reaches here as it is stored.
 *
 * The way back to `yuva420p` is ffmpeg's frame format, and 4:2:0 cannot hold the mask's stripes of colour. Luma keeps
 * them and chroma keeps their mean over a sample. A frame comes out with its alpha as it read, opaque if it had none.
 */
internal fun crtFragment(
  crt: Crt,
  frame: Size,
): FilterFragment = CrtFragments.of(crt, frame)

/**
 * The branches that draw [CrtSignal.pixelAt] on a `gbrap16le` frame swscale widened from `gbrap` at the pad
 * [TUBE_READ], writing [TUBE_WRITE] at 65535 full scale.
 *
 * A read between pixels is four `remap`s of the corners around it and three `maskedmerge`s between them, on
 * premultiplied frames so a read between a picture and a clear bar weighs by alpha, with every plane of a share frame
 * holding the share so alpha moves with the colour. Past the frame the clamped columns and rows hold the edge pixel, as
 * a clamped texture does, so alpha comes from there. The gain carries colour to full scale through
 * [RgbRoundTrip.scaledOfWidened] and stops there, alpha lands back on its code through
 * [RgbRoundTrip.keptOfWidened], and coverage fades colour alone.
 */
internal fun crtTube(
  params: CrtParams,
  frames: CrtFrames,
): List<FilterBranch> =
  listOf(
    FilterBranch(listOf(TUBE_READ), emptyList(), listOf("near", "nearright", "nearbelow", "nearboth")),
    gray(frames.leftColumn, "left"),
    FilterBranch(listOf("left"), emptyList(), listOf("left1", "left2")),
    gray(frames.rightColumn, "right"),
    FilterBranch(listOf("right"), emptyList(), listOf("right1", "right2")),
    gray(frames.topRow, "top"),
    FilterBranch(listOf("top"), emptyList(), listOf("top1", "top2")),
    gray(frames.bottomRow, "bottom"),
    FilterBranch(listOf("bottom"), emptyList(), listOf("bottom1", "bottom2")),
    share(frames.across, "across"),
    FilterBranch(listOf("across"), emptyList(), listOf("across1", "across2")),
    share(frames.down, "down"),
    sourceOf(frames.multiplier, listOf(RGB_FORMAT), "multiplier"),
    share(frames.coverage, "coverage"),
    FilterBranch(listOf("near", "left1", "top1"), listOf(REMAP), listOf("readlt")),
    FilterBranch(listOf("nearright", "right1", "top2"), listOf(REMAP), listOf("readrt")),
    FilterBranch(listOf("nearbelow", "left2", "bottom1"), listOf(REMAP), listOf("readlb")),
    FilterBranch(listOf("nearboth", "right2", "bottom2"), listOf(REMAP), listOf("readrb")),
    FilterBranch(listOf("readlt", "readrt", "across1"), listOf(MERGE), listOf("uppers")),
    FilterBranch(listOf("readlb", "readrb", "across2"), listOf(MERGE), listOf("lowers")),
    FilterBranch(
      listOf("uppers", "lowers", "down"),
      listOf(MERGE, FilterNode("unpremultiply", "inplace" to "1")),
      listOf("straight"),
    ),
    FilterBranch(
      listOf("straight", "multiplier"),
      listOf(
        FilterNode(
          "blend",
          "all_expr" to "min(${RgbRoundTrip.scaledOfWidened("A")}*B*${params.gain}/$FULL_CODE,$FULL_CODE)",
          "c3_expr" to RgbRoundTrip.keptOfWidened("A"),
        ),
      ),
      listOf("lit"),
    ),
    FilterBranch(
      listOf("lit", "coverage"),
      listOf(FilterNode("blend", "all_expr" to "A*B/$FULL_CODE", ALPHA_THROUGH)),
      listOf(TUBE_WRITE),
    ),
  )

/**
 * The pad [crtTube] reads.
 */
internal const val TUBE_READ = "tubein"

/**
 * The pad [crtTube] writes.
 */
internal const val TUBE_WRITE = "tubeout"

private object CrtFragments {
  private val built = HashMap<Pair<Size, Float>, WeakReference<FilterFragment>>()

  @Synchronized
  fun of(
    crt: Crt,
    frame: Size,
  ): FilterFragment {
    val key = frame to crt.intensity
    built[key]?.get()?.let { return it }
    built.values.removeAll { it.get() == null }
    return drawn(CrtParams.of(crt), frame).also { built[key] = WeakReference(it) }
  }

  private fun drawn(
    params: CrtParams,
    frame: Size,
  ): FilterFragment {
    val frames = CrtFrames.of(params, frame)
    val toSize = FilterNode("scale", "w" to frame.width.toString(), "h" to frame.height.toString())
    val opening =
      listOf(toSize) + RgbRoundTrip.opening() + RgbRoundTrip.toRgb() +
        FilterNode("format", "pix_fmts" to RGB_FORMAT) + FilterNode("premultiply", "inplace" to "1")
    return FilterFragment(
      branches =
        listOf(FilterBranch(listOf(FilterBranch.INPUT), opening, listOf(TUBE_READ))) +
          crtTube(params, frames) +
          FilterBranch(
            listOf(TUBE_WRITE),
            // swscale's way to 4:2:0 lands chroma up to 6 codes off the mean from sixteen bits and 2.4 from eight, so
            // it starts on eight.
            RgbRoundTrip.toEightBits() + RgbRoundTrip.toCodes(),
            listOf("out"),
          ),
      sidecars = frames.sidecars,
    )
  }
}

private const val RGB_FORMAT = "gbrap16le"
private const val GRAY_FORMAT = "gray16le"

private val REMAP = FilterNode("remap")
private val MERGE = FilterNode("maskedmerge")
private val ALPHA_THROUGH = "c3_expr" to "A"

// Every plane takes the one gray plane, so a merge moves alpha with the colour and no swscale step can move a share.
private val ON_EVERY_PLANE =
  FilterNode(
    "mergeplanes",
    "map0s" to "0",
    "map0p" to "0",
    "map1s" to "0",
    "map1p" to "0",
    "map2s" to "0",
    "map2p" to "0",
    "map3s" to "0",
    "map3p" to "0",
    "format" to RGB_FORMAT,
  )

private fun gray(
  sidecar: Sidecar,
  write: String,
): FilterBranch = sourceOf(sidecar, listOf(GRAY_FORMAT), write)

private fun share(
  sidecar: Sidecar,
  write: String,
): FilterBranch =
  FilterBranch(
    emptyList(),
    listOf(movieOf(sidecar), AHEAD_OF_THE_CLIP, FilterNode("format", "pix_fmts" to GRAY_FORMAT), ON_EVERY_PLANE),
    listOf(write),
  )

private fun sourceOf(
  sidecar: Sidecar,
  formats: List<String>,
  write: String,
): FilterBranch =
  FilterBranch(
    emptyList(),
    listOf(movieOf(sidecar), AHEAD_OF_THE_CLIP) + formats.map { FilterNode("format", "pix_fmts" to it) },
    listOf(write),
  )

// Named by demuxer so a path is only ever a path and never an image sequence's pattern.
private fun movieOf(sidecar: Sidecar): FilterNode =
  FilterNode("movie", "filename" to sidecar.placeholder, "f" to "${sidecar.extension}_pipe")
