package dev.jordond.filmstrip.media3.internal

import android.content.Context
import androidx.media3.common.Effect
import androidx.media3.common.audio.AudioProcessor
import androidx.media3.common.audio.ChannelMixingAudioProcessor
import androidx.media3.common.audio.ChannelMixingMatrix
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.ToInt16PcmAudioProcessor
import androidx.media3.effect.GlEffect
import androidx.media3.effect.GlShaderProgram
import androidx.media3.effect.OverlayEffect
import androidx.media3.effect.TextureOverlay
import androidx.media3.transformer.EditedMediaItem
import dev.jordond.filmstrip.effect.Media3Handle
import dev.jordond.filmstrip.effects.overlay.MAX_OVERLAYS_PER_EFFECT
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media3.internal.fill.FillCoverBlur
import dev.jordond.filmstrip.media3.internal.fill.FillFlatten
import dev.jordond.filmstrip.transform.internal.geometry.showsBlurredFill
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.drawnRect
import kotlin.time.Duration

/**
 * A clip's own video effect chain, ending on the pass that places the clip in an output frame of [outputSize].
 *
 * Each crop that makes a canvas a later effect reads has the canvas built right after it, with clear bars the flatten
 * pass paints the fill into. Under [Fill.Blurred] they turn black once the clip's effects have run, since the cover is
 * drawn from the frame and carries the bars the way it carries the picture. The clip is then drawn at its [drawnRect],
 * over a blurred cover of itself when [showsBlurredFill] says the cover can be seen. An SD clip on [sdPrimaries] opens
 * on [SdPrimariesHead], ahead of everything [wrapper] sees, so its effects read the clip's colour as BT.709.
 */
internal fun ResolvedClip.clipVideoEffects(
  fill: Fill,
  outputSize: Size,
  wrapper: EffectWrapper = UnwrappedEffects,
  sdPrimaries: Int? = null,
): List<Effect> {
  // Cut at each canvas, so a run of overlays on one side of it still blends in one pass.
  val cuts = listOf(-1) + canvasEffectIndices
  val chain =
    cuts.indices.flatMap { segment ->
      val until = canvasEffectIndices.getOrNull(segment)?.plus(1) ?: effects.size
      effects.subList(cuts[segment] + 1, until).toMedia3Effects() +
        listOfNotNull(placement.canvases.getOrNull(segment)?.let(PlacedFrame::canvasOf))
    }
  val blackBars =
    if (fill is Fill.Blurred && placement.canvases.isNotEmpty()) listOf(FillFlatten(BLACK_BARS)) else emptyList()
  val placed =
    if (fill is Fill.Blurred && placement.showsBlurredFill(outputSize)) {
      FillCoverBlur(fill, outputSize, drawnRect)
    } else {
      PlacedFrame(drawnRect, outputSize)
    }
  return listOfNotNull(sdPrimaries?.let(::SdPrimariesHead)) + (chain + blackBars + placed).map(wrapper::wrap)
}

/**
 * The processors an audio-carrying clip runs through.
 *
 * Every item in a sequence has to reach the same output format, and a constant gain is applied by
 * scaling the mixing matrix that gets it there. Pinning 16-bit PCM and a channel count is what
 * makes a concatenation of a mono clip and a stereo clip mix at all, so it is done whenever a
 * sequence has more than one clip rather than only when a gain is asked for.
 *
 * A curve that ramps cannot ride on the matrix, which holds one number for the whole item, so the
 * matrix stays at unity and [rampProcessorFor] appends a processor that walks the curve after it.
 */
internal fun audioProcessors(
  gain: ResolvedGain,
  mixes: Boolean,
): List<AudioProcessor> {
  val constant = gain.constant
  if (!mixes && constant == 1f) return emptyList()

  val mixer = ChannelMixingAudioProcessor()
  INPUT_CHANNEL_COUNTS.forEach { inputs ->
    mixer.putChannelMixingMatrix(
      ChannelMixingMatrix.createForConstantGain(inputs, CHANNEL_COUNT).scaleBy(constant ?: 1f),
    )
  }

  return listOf(ToInt16PcmAudioProcessor(), mixer) + listOfNotNull(rampProcessorFor(gain))
}

/**
 * The processors that open a clip on its trim start, play it at [speed] and run [then] over the result.
 *
 * [AudioStartDrop] cuts the [lead] between the access unit the item is clipped from and the trim start. A
 * [SonicAudioProcessor] plays what is left at [speed] with its pitch kept, and [AudioLengthPin] holds the result to
 * [length], which is also the length media3 lays the next item after. At a speed of one there is nothing to stretch.
 *
 * [then] reads the clip's output clock, like a gain ramp. media3 seeds each processor's position with what the ones
 * ahead of it report for the position it started from, and the pin reports [length] whatever it is asked, so [then]
 * runs ahead of the pin.
 */
internal fun clipAudioSpeed(
  lead: Duration,
  speed: Double,
  length: Duration,
  then: List<AudioProcessor> = emptyList(),
): List<AudioProcessor> =
  listOfNotNull(
    AudioStartDrop(lead),
    if (speed == 1.0) null else SonicAudioProcessor().apply { setSpeed(speed.toFloat()) },
  ) + then + AudioLengthPin(length, reportsLength = true)

/**
 * Lowers a resolved chain, gathering the overlays in it into as few passes as they fit into.
 *
 * A resolver hands back a [TextureOverlay] factory rather than an [Effect] for anything it composites,
 * because media3 blends every overlay in one [OverlayEffect] with a single draw call, whereas a
 * chain of N `OverlayEffect`s is N render passes over the whole frame. Canonical order already
 * leaves the overlays adjacent, so gathering each run where it appears keeps the declared order and
 * costs one pass however many of them there are, up to the sampler budget one GL program has.
 */
internal fun List<ResolvedEffect>.toMedia3Effects(): List<Effect> {
  val effects = mutableListOf<Effect>()
  val overlays = mutableListOf<() -> TextureOverlay>()

  fun flush() {
    if (overlays.isEmpty()) return
    effects += OverlayPass(overlays.toList())
    overlays.clear()
  }

  forEach { resolved ->
    when (val handle = resolved.effect.handle) {
      is Media3Handle.Overlay -> {
        overlays += handle.newOverlay
        if (overlays.size == MAX_OVERLAYS_PER_EFFECT) flush()
      }
      is Media3Handle.Video -> {
        flush()
        effects += handle.effect
      }
    }
  }

  flush()
  return effects
}

/**
 * A run of overlays blended in one [OverlayEffect], with every overlay built for the graph that draws it.
 *
 * media3 keeps an uploaded texture's name on the [TextureOverlay] and frees it on release, with no note of the GL
 * context it came from. The player, a frame reader and a photo's processor each run a context of their own, so an
 * overlay that two of them shared would bind a name that means something else in the second one. Building the overlays
 * here gives each shader program its own.
 */
private class OverlayPass(
  private val overlays: List<() -> TextureOverlay>,
) : GlEffect {
  override fun toGlShaderProgram(
    context: Context,
    useHdr: Boolean,
  ): GlShaderProgram = OverlayEffect(overlays.map { it() }).toGlShaderProgram(context, useHdr)
}

private const val CHANNEL_COUNT = 2
private val INPUT_CHANNEL_COUNTS = listOf(1, 2)

private const val BLACK_BARS = 0xFF000000.toInt()
