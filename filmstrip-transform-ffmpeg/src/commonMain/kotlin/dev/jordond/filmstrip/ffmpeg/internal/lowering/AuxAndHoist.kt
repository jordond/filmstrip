package dev.jordond.filmstrip.ffmpeg.internal.lowering

import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.geometry.PixelRect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import kotlin.time.Duration

internal val RESTART_PTS: FilterNode = FilterNode("setpts", "expr" to "PTS-STARTPTS")

/**
 * The extra inputs a merging effect composites, each declared on [inputs] and prepared on a pad of its own.
 *
 * An input naming a timeline is looped for a frame longer than the run it covers. Overshooting
 * costs nothing, since the graph's own `trim=duration=` is what cuts the output, while
 * undershooting drops the overlay from the frame the branch runs out with no diagnostic at all.
 *
 * Every branch closes on a `setpts` back to its own first frame. An export reads the input from
 * zero and the node changes nothing, while a preview opens the input at the scrub instead, so
 * anything ahead of the node reads the composition time the frames belong to and the merge below
 * it still sees the clip's own clock. [InputSpec.rebased] is what tells the preview so.
 */
internal fun FilterGraphBuilder.auxPads(
  inputs: MutableList<InputSpec>,
  auxInputs: List<AuxInput>,
  label: String,
): List<String> =
  auxInputs.mapIndexed { index, aux ->
    val input = inputs.size
    inputs +=
      InputSpec(
        source = InputSource.OfImage(aux.image),
        durationSeconds = aux.timeline?.let { it.duration.seconds() + 1.0 / it.frameRate },
        loopFrameRate = aux.timeline?.frameRate,
        rebased = true,
      )
    "${label}a$index".also { chain(listOf("$input:v"), aux.chain + RESTART_PTS, it) }
  }

/**
 * The one look every clip of [track] runs, when it writes the same samples after the join as on each clip, or null.
 *
 * Running it once after the join holds its work in the graph once rather than once per clip. It takes a look that
 * reads no clock, since a clip's look reads a frame's own stamp and the join's grid moves it, which rules out Film and
 * VHS and leaves CRT. A spatial effect stays on its clip too, since it reads the clip's own frame. A clip's tail has to
 * be a no-op on the picture too, so each clip already fills the frame in the tail's format, [plainTail] says nothing
 * else lands on it, and the clips run on from each other with no gap.
 *
 * @param track The primary track.
 * @param output The size every clip has to fill.
 * @param tailFormatSources The sources that already decode to the format a clip's tail ends on.
 * @param plainTail Whether the plan leaves a clip's tail with no grade, fill or colour work to do.
 * @param slotsOf How many output slots a clip covers.
 */
internal fun hoistableLook(
  track: ResolvedTrack?,
  output: Size,
  tailFormatSources: Set<MediaSource>,
  plainTail: Boolean,
  slotsOf: (ResolvedClip) -> Long,
): FilterFragment? {
  val clips = track?.clips.orEmpty().filter { slotsOf(it) > 0 }
  val first = clips.firstOrNull()?.effects?.singleOrNull() ?: return null
  if (first.stage == EffectStage.Spatial) return null
  val look = first.effect.fragment ?: return null
  val whole = PixelRect(0, 0, output.width, output.height)
  val hoists =
    plainTail &&
      clips.size > 1 &&
      look.branches.isNotEmpty() &&
      !look.readsCompositionClock &&
      look.live == null &&
      look.crop == null &&
      track?.start == Duration.ZERO &&
      clips.zipWithNext().all { (first, next) -> first.span.endExclusive == next.span.start } &&
      clips.all { clip ->
        clip.effects
          .singleOrNull()
          ?.effect
          ?.fragment == look &&
          clip.decodedFrame == output &&
          clip.placement.canvases.isEmpty() &&
          clip.drawnIn(output) == whole &&
          clip.source in tailFormatSources
      }
  return look.takeIf { hoists }
}
