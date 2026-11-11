package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.audio.AudioEffectResolution
import dev.jordond.filmstrip.audio.PlatformAudioEffect
import dev.jordond.filmstrip.effect.Degradation
import dev.jordond.filmstrip.effect.DegradationReason
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effects.filters.internal.HashExpression
import dev.jordond.filmstrip.export.AudioFormat
import kotlin.math.min
import kotlin.math.roundToInt

// A TapeAudio as ffmpeg filters. Everything but the bend in pitch is the kernel's own arithmetic, so a spec with no
// wow and no flutter is exact and one with either is an approximation.
internal fun tapeAudioResolution(spec: TapeAudio): AudioEffectResolution {
  val effect = PlatformAudioEffect { format -> tapeAudioFilters(spec, format) }
  val bends = spec.intensity > 0f && (spec.wow.depth > 0f || spec.flutter.depth > 0f)
  return if (bends) {
    AudioEffectResolution.Degraded(effect, listOf(VIBRATO_BEND))
  } else {
    AudioEffectResolution.Resolved(effect)
  }
}

// The nodes for one stream at format, every figure read from tapeAudioParams.
internal fun tapeAudioFilters(
  spec: TapeAudio,
  format: AudioFormat,
): List<FilterNode> {
  val params = tapeAudioParams(spec, format.sampleRate)
  val bends = vibratoBendsOf(params)
  val lead = if (bends.isEmpty()) 0 else vibratoBufferFrames(format.sampleRate)
  val held = heldFramesOf(bends, format.sampleRate)
  return buildList {
    fold(params, format.channelCount)?.let { add(it) }
    addAll(bent(bends, lead, held))
    if (params.bandLimited) add(section("highpass", params.highPassHz, HIGH_PASS_Q))
    if (params.hissGain > 0.0) add(hiss(params, lead + held))
    if (params.bandLimited) LOW_PASS_Q.forEach { add(section("lowpass", params.lowPassHz, it)) }
  }
}

/**
 * One bend in pitch as ffmpeg's `vibrato` plays it.
 *
 * @property rateHz How many times a second the bend repeats.
 * @property depth The filter's own depth, from 0 to 1 of the reach of its buffer.
 */
internal class VibratoBend(
  val rateHz: Double,
  val depth: Double,
)

// The wow and the flutter as vibrato stages, leaving out one with no depth.
internal fun vibratoBendsOf(params: TapeAudioParams): List<VibratoBend> {
  val reach = (vibratoBufferFrames(params.sampleRate) - 1) / 2.0
  return listOf(params.wowFrames to params.wowRateHz, params.flutterFrames to params.flutterRateHz)
    .filter { (swing, _) -> swing > 0.0 }
    // vibrato's limit, not the tape's: its depth stops at 1, a swing of half its buffer either way. A slower or
    // deeper bend than that is played at the filter's most.
    .map { (swing, rateHz) -> VibratoBend(rateHz, min(swing / reach, VIBRATO_MAX_DEPTH)) }
}

// How many frames vibrato keeps, 5 ms rounded the way the filter rounds it.
internal fun vibratoBufferFrames(sampleRate: Int): Int = Math.rint(sampleRate * VIBRATO_BUFFER_SECONDS + 0.5).toInt()

// How late the bends leave the stream on average, in whole frames. Each holds a sample for its buffer less its swing.
internal fun heldFramesOf(
  bends: List<VibratoBend>,
  sampleRate: Int,
): Int {
  val buffer = vibratoBufferFrames(sampleRate)
  return bends.sumOf { buffer - it.depth * (buffer - 1) / 2.0 }.roundToInt()
}

// The stream bent in pitch and put back where it was. vibrato reads memory it never cleared for its first buffer of
// frames, so lead frames of silence are laid ahead of the stream and cut off again with the frames the bends hold.
// The stream is padded by what they hold first, so its tail comes through.
private fun bent(
  bends: List<VibratoBend>,
  lead: Int,
  held: Int,
): List<FilterNode> {
  if (bends.isEmpty()) return emptyList()
  return buildList {
    add(FilterNode("adelay", "delays" to "${lead}S", "all" to "1"))
    add(FilterNode("apad", "pad_len" to "$held"))
    bends.forEach { add(FilterNode("vibrato", "f" to plain(it.rateHz), "d" to plain(it.depth))) }
    add(FilterNode("atrim", "start_sample" to "${lead + held}"))
  }
}

private fun fold(
  params: TapeAudioParams,
  channels: Int,
): FilterNode? {
  if (params.monoMix == 0.0 || channels == 1) return null
  val mean = (0 until channels).joinToString("+") { "val($it)" }
  return FilterNode("aeval", "exprs" to "val(ch)+${plain(params.monoMix)}*(($mean)/$channels-val(ch))", "c" to "same")
}

private fun section(
  name: String,
  cornerHz: Double,
  q: Double,
): FilterNode = FilterNode(name, "f" to plain(cornerHz), "p" to "2", "t" to "q", "w" to plain(q), "r" to "f64")

// The kernel's hiss, drawn for the timeline frame each sample sits on. A cut ahead of this moved every sample to a
// time cut frames later than the frame it plays at, so that many come off the frame its time reads as.
private fun hiss(
  params: TapeAudioParams,
  cut: Int,
): FilterNode {
  val pass = if (params.hissPerChannel) "($PASS_HISS+ch)" else "$PASS_HISS"
  val draw = HashExpression(params.seed).hash(pass, "floor(ld(2)/$FRAME_RANGE)", "ld(2)")
  val expression =
    listOf(
      "st(2,max(0,floor(t*${params.sampleRate}+0.5)-$cut))",
      "st(3,($draw)/$FRAME_RANGE*2-1)",
      "val(ch)+${plain(params.hissGain)}*ld(3)",
    ).joinToString(";")
  return FilterNode("aeval", "exprs" to expression, "c" to "same")
}

// A figure with no exponent, which a filter argument cannot always read.
private fun plain(value: Double): String = value.toBigDecimal().toPlainString()

private val VIBRATO_BEND =
  Degradation(
    DegradationReason.ApproximateAlgorithm,
    "ffmpeg bends the pitch with its vibrato filter, which reads between two samples rather than four and starts " +
      "each bend at the head of the stream rather than where the seed puts it.",
  )

private const val VIBRATO_BUFFER_SECONDS = 0.005

/**
 * The most depth ffmpeg's `vibrato` takes.
 */
internal const val VIBRATO_MAX_DEPTH = 1.0

// How many frames the low bits of a draw count before the bits above them move on.
private const val FRAME_RANGE = 1 shl FRAME_BITS
