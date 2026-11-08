package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.FilterArgument
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots

/**
 * The size stage, which places the clip at [rect] in the resolved output frame.
 *
 * Not an effect and not in `ExportPlan.effectOrder`: it is what every clip carries so `concat` sees
 * uniform inputs, and it is why the scale effect emits nothing of its own.
 *
 * [padColor] is the fill's own color, or transparent while that color is waiting for composition
 * effects to run before it is painted in. The caller decides which. [slots] is how many frames the
 * clip is cut to, as [trailingNodes] takes it. [grid] is the chroma grid of the chain the clip is placed from.
 */
internal fun tailNodes(
  outputSize: Size,
  rect: PixelRect,
  padColor: String,
  frameRate: Int,
  pixelFormat: String,
  slots: Long,
  grid: ChromaGrid,
): List<FilterNode> = placedNodes(rect, outputSize, padColor, grid) + trailingNodes(frameRate, pixelFormat, slots)

/**
 * An `overlay` drawing on a frame written in [pixelFormat], on the chroma [grid] of the chain it draws on.
 *
 * The filter runs in the format [overlayFormat] picks. Every overlay this backend writes is built here or passed
 * through [onFrame], so the option cannot be set one way in one place and another way in the next.
 */
internal fun overlayNode(
  pixelFormat: String,
  grid: ChromaGrid,
  vararg arguments: Pair<String, String>,
): FilterNode = FilterNode("overlay", *arguments, "format" to overlayFormat(pixelFormat, grid))

/**
 * The format an `overlay` runs in on a frame written in [pixelFormat], on the chroma [grid] of the chain it draws on.
 *
 * An 8-bit 4:2:0 frame on a [ChromaGrid.Subsampled] chain runs in `yuv420`, so the frame under the picture is blended
 * in its own planes. Under `auto` ffmpeg takes that frame to RGB to meet a picture with alpha and back again, and its
 * 8-bit scaler truncates both ways. A 10-bit frame keeps `auto`, which holds the depth it arrived at where the filter's
 * own `yuv420` would take it down to 8 bits and back. So does a [ChromaGrid.Full] chain, which `auto` keeps at full
 * chroma.
 *
 * `overlay` in `yuv420` puts `x` and `y` on the 4:2:0 grid, so an odd position lands on the even pixel below it. That
 * is ffmpeg's limit. [onFrame] and [cropped] move a written position onto that grid, so the graph says where the
 * picture lands. A position an animation sends as a command is moved the same way by the filter itself.
 */
internal fun overlayFormat(
  pixelFormat: String,
  grid: ChromaGrid,
): String = if (grid == ChromaGrid.Subsampled && pixelFormat in EIGHT_BIT_420) "yuv420" else "auto"

/**
 * This fragment merge as it runs on a frame written in [pixelFormat], on the chroma [grid] of the chain it draws on.
 *
 * An `overlay` takes the format [overlayFormat] picks. One running in `yuv420` places its picture on the 4:2:0 grid,
 * so its `x` and `y` are moved down onto that grid here rather than by ffmpeg without saying so. Any other merge is
 * handed back as it is.
 */
internal fun FilterNode.onFrame(
  pixelFormat: String,
  grid: ChromaGrid,
): FilterNode {
  if (name.substringBefore('@') != "overlay") return this
  val format = overlayFormat(pixelFormat, grid)
  val placedOn = if (format == "yuv420") ChromaGrid.Subsampled else ChromaGrid.Full
  val placed =
    arguments.filter { it.key != "format" }.map { argument ->
      if (argument.key == "x" ||
        argument.key == "y"
      ) {
        FilterArgument(argument.key, placedOn.snap(argument.value))
      } else {
        argument
      }
    }
  return FilterNode(name, placed + FilterArgument("format", format))
}

private val EIGHT_BIT_420 = setOf("yuv420p", "yuva420p")

/**
 * The stage every tail ends on, blurred letterbox or not.
 *
 * Pins the sample aspect, the frame rate and the pixel format so `concat` sees uniform inputs
 * across every clip, and cuts the clip to exactly [slots] frames, the output slots its span covers.
 * The clip's last frame is cloned ahead of the cut, so a span whose end runs past the source's last
 * frame still has a frame for every slot it covers. A branch that ends on its last frame's own time
 * has that end moved a slot on before `fps` reads it, so the frame is written rather than dropped.
 * [pixelFormat] is the encoder's own 10-bit format for an HDR export, or `yuv420p` for everything
 * else.
 */
internal fun trailingNodes(
  frameRate: Int,
  pixelFormat: String,
  slots: Long,
): List<FilterNode> =
  listOf(
    // scale does not reset the sample aspect ratio and concat refuses to join clips whose sample
    // aspects differ, so this is not cosmetic.
    FilterNode("setsar", "r" to "1"),
    // ffmpeg 6.1.1 ends an overlay on its last frame's own time rather than after it, and fps drops
    // the frame its input ends on. setpts evaluates the end too, and only there does PTS equal the
    // frame before it, so that end moves a slot on and every frame keeps its time. Builds that end
    // after the frame pass through unchanged.
    FilterNode("setpts", "expr" to "PTS+eq(PTS,PREV_INPTS)/($frameRate*TB)"),
    FilterNode("fps", "fps" to frameRate.toString()),
    FilterNode("tpad", "stop_mode" to "clone", "stop" to HELD_FRAMES.toString()),
    FilterNode("trim", "end_frame" to slots.toString()),
    FilterNode("setpts", "expr" to "PTS-STARTPTS"),
    FilterNode("format", "pix_fmts" to pixelFormat),
  )

// ffmpeg's limit rather than the grid's: a snapped end runs at most half a slot past the clip's own
// end, and fps drops the frame it is part way through when its input ends, so two clones cover the
// furthest a clip's frames can fall short of its slots. The cut after them drops whatever is spare.
private const val HELD_FRAMES = 2

/**
 * The alpha-carrying format at [pixelFormat]'s own bit depth and chroma layout, for a clip tail
 * that has to pad or composite transparently rather than write an opaque frame outright.
 *
 * `p010le` and `yuv420p10le` are both 10-bit 4:2:0, one packed and one planar, and both convert
 * losslessly to and from the same planar alpha format, `yuva420p10le`. The trailing
 * `format=<pixelFormat>` after the overlay converts back to whichever one the encoder actually
 * wants. Fails loudly on a format this has no equivalent for, so a new HDR format shows up as a
 * build failure instead of a black bar nobody notices.
 */
internal fun alphaPixelFormat(pixelFormat: String): String =
  when (pixelFormat) {
    "yuv420p" -> "yuva420p"
    "yuv420p10le", "p010le" -> "yuva420p10le"
    else -> error("No alpha pixel format known for $pixelFormat.")
  }

/**
 * Clones the frame on the primary's last covered slot over the held tail, then cuts the video to one frame per slot
 * the composition covers.
 *
 * Sits after the composition effects and the deferred fill, so the frame held is the finished one with every effect
 * frozen where it was on that slot. Nothing when no audio track outlasts the primary.
 */
internal fun ResolvedComposition.heldTailNodes(): List<FilterNode> {
  val tail = heldTail
  if (tail.isEmpty()) return emptyList()

  return listOf(
    FilterNode("tpad", "stop_mode" to "clone", "stop" to (tail.last - tail.first + 1).toString()),
    FilterNode("trim", "end_frame" to videoSlots.toString()),
  )
}
