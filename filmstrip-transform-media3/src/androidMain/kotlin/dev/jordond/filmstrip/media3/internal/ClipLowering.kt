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
import dev.jordond.filmstrip.media.HdrTransfer
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
 * on [SdPrimariesHead], ahead of everything [wrapper] sees, so its effects read the clip's colour as BT.709. A blurred
 * cover on a kept [transfer] grade is blurred on its SDR signal. A clip whose pictures can be transparent opens on
 * [StraightAlphaHead], so everything after it reads straight color.
 */
internal fun ResolvedClip.clipVideoEffects(
  fill: Fill,
  outputSize: Size,
  wrapper: EffectWrapper = UnwrappedEffects,
  sdPrimaries: Int? = null,
  transfer: HdrTransfer? = null,
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
    if (fill is Fill.Blurred && (placement.showsBlurredFill(outputSize) || info.video?.hasAlpha == true)) {
      FillCoverBlur(fill, outputSize, drawnRect, transfer)
    } else {
      PlacedFrame(drawnRect, outputSize)
    }
  val straight = StraightAlphaHead.takeIf { info.video?.hasAlpha == true }
  return listOfNotNull(straight, sdPrimaries?.let(::SdPrimariesHead)) + (chain + blackBars + placed).map(wrapper::wrap)
}

/**
 * The processors a clip's sound runs through once it is the right length: its audio effects, then its level.
 *
 * The effects come first, so a fade takes whatever an effect adds down with the clip.
 *
 * With [liveGain] set the level is one a standing graph can change.
 */
internal fun ResolvedClip.clipAudioProcessors(
  mixes: Boolean,
  liveGain: Boolean,
): List<AudioProcessor> = kernelProcessors(audioEffects, span.start) + audioProcessors(gain, mixes, liveGain)

/**
 * The processors that bring an audio-carrying clip to the format its sequence outputs and to its level.
 *
 * Every item in a sequence has to reach the same output format. Pinning 16-bit PCM and a channel count is what makes a
 * concatenation of a mono clip and a stereo clip mix at all, so it is done whenever a sequence has more than one clip
 * rather than only when a gain is asked for. The mixing matrix stays at unity and a [ClipGainProcessor] after it
 * carries the whole level, a held one and a ramp alike.
 *
 * With [liveGain] set the level processor is there whatever [gain] is and the format is pinned only for [mixes], since
 * media3 keeps the processors a graph was built with and a changed level must not change them.
 */
internal fun audioProcessors(
  gain: ResolvedGain,
  mixes: Boolean,
  liveGain: Boolean = false,
): List<AudioProcessor> {
  val unity = gain.constant == 1f
  val format = if (mixes || (!liveGain && !unity)) outputFormatProcessors() else emptyList()
  return format + listOfNotNull(ClipGainProcessor(gain).takeIf { liveGain || !unity })
}

private fun outputFormatProcessors(): List<AudioProcessor> {
  val mixer = ChannelMixingAudioProcessor()
  INPUT_CHANNEL_COUNTS.forEach { inputs ->
    mixer.putChannelMixingMatrix(ChannelMixingMatrix.createForConstantGain(inputs, CHANNEL_COUNT))
  }
  return listOf(ToInt16PcmAudioProcessor(), mixer)
}

/**
 * The processors that open a clip on its trim start, play it at [speed] and run [then] over the result.
 *
 * [AudioStartDrop] cuts the [lead] between the access unit the item is clipped from and the trim start. A
 * [SonicAudioProcessor] plays what is left at [speed] with its pitch kept, and [AudioLengthPin] holds the result to
 * [length], which is also the length media3 lays the next item after. At a speed of one there is nothing to stretch.
 *
 * [then] reads the clip's output clock, like a gain ramp or an audio effect. It runs after the pin, so it is handed
 * the silence a short clip is padded with as well. media3 seeds each processor's position with what the ones ahead of
 * it report for the position it started from, and each of these reports the start of the item as the start.
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
  ) + AudioLengthPin(length, reportsLength = true) + then

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
