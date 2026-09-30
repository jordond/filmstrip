package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.media.BT2020_LUMA_B
import dev.jordond.filmstrip.media.BT2020_LUMA_G
import dev.jordond.filmstrip.media.BT2020_LUMA_R
import dev.jordond.filmstrip.media.HLG_A
import dev.jordond.filmstrip.media.HLG_B
import dev.jordond.filmstrip.media.HLG_C
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.PQ_C1
import dev.jordond.filmstrip.media.PQ_C2
import dev.jordond.filmstrip.media.PQ_C3
import dev.jordond.filmstrip.media.PQ_M1
import dev.jordond.filmstrip.media.PQ_M2

// The transfer functions an HDR grade is written in, spelled as ffmpeg expressions. Every constant is
// core's, so a graph and the figures a test expects are built from the same numbers.

/**
 * Decodes an ST 2084 [signal] to linear light, as a fraction of the transfer's peak.
 */
internal fun pqLight(signal: String): String = pqLightFromEncoded(pqEncoded(signal))

/**
 * Encodes [light], a fraction of the ST 2084 peak, as its signal.
 */
internal fun pqSignal(light: String): String = pqSignalFromScaled(pqScaled(light))

private fun pqEncoded(signal: String): String = "pow($signal,${1.0 / PQ_M2})"

private fun pqLightFromEncoded(encoded: String): String =
  "pow(max($encoded-$PQ_C1,0)/($PQ_C2-$PQ_C3*$encoded),${1.0 / PQ_M1})"

private fun pqScaled(light: String): String = "pow($light,$PQ_M1)"

private fun pqSignalFromScaled(scaled: String): String = "pow(($PQ_C1+$PQ_C2*$scaled)/(1+$PQ_C3*$scaled),$PQ_M2)"

/**
 * Decodes an HLG [signal] through the inverse OETF alone, so what comes out is scene light rather than display light.
 */
internal fun hlgScene(signal: String): String =
  "if(lte($signal,0.5),$signal*$signal/3,(exp(($signal-$HLG_C)/$HLG_A)+$HLG_B)/12)"

/**
 * Encodes HLG [scene] light as its signal through the OETF.
 */
internal fun hlgSignal(scene: String): String =
  "if(lte($scene,1/12),sqrt(3*$scene),$HLG_A*log(12*$scene-$HLG_B)+$HLG_C)"

/**
 * Decodes a pixel's three signal channels to linear BT.2020 display light, one expression per channel.
 *
 * The light is a fraction of the transfer's peak, which is ten thousand cd/m2 on PQ and the nominal thousand on HLG.
 * HLG runs its opto-optical transfer on the scene's luminance, so every channel of a colour is scaled by the same
 * figure rather than raised to a power on its own.
 *
 * A figure an expression reads more than once is worked out once and stored in one of ffmpeg's expression variables,
 * so geq runs each transfer once per figure rather than once per mention. The answer is the same to the bit.
 *
 * @param signal The red, green and blue signal, each an expression in the range zero to one.
 * @return Red, green and blue display light, in that order.
 */
internal fun displayLightFromSignal(
  transfer: HdrTransfer,
  signal: List<String>,
): List<String> =
  when (transfer) {
    HdrTransfer.Pq -> {
      signal.map { stored(0, pqEncoded(it)) + pqLightFromEncoded(loaded(0)) }
    }
    HdrTransfer.Hlg -> {
      val signals = signal.mapIndexed { slot, channel -> stored(SIGNAL_SLOT + slot, channel) }.joinToString("")
      val scenes = signal.indices.joinToString("") { stored(it, hlgScene(loaded(SIGNAL_SLOT + it))) }
      val scene = signal.indices.map(::loaded)
      val gain = "pow(${luminance(scene)},${HLG_SYSTEM_GAMMA - 1.0})"
      scene.map { "$signals$scenes($it)*$gain" }
    }
  }

/**
 * Encodes linear BT.2020 display light as a pixel's three signal channels, the inverse of [displayLightFromSignal].
 *
 * Each result is clipped to the range zero to one, which is where the format runs out. A figure read more than once is
 * stored the way [displayLightFromSignal] stores it.
 *
 * @param light The red, green and blue display light, each an expression holding a fraction of the transfer's peak.
 * @return Red, green and blue signal, in that order.
 */
internal fun signalFromDisplayLight(
  transfer: HdrTransfer,
  light: List<String>,
): List<String> =
  when (transfer) {
    HdrTransfer.Pq -> {
      light.map { stored(0, pqScaled("clip($it,0,1)")) + "clip(${pqSignalFromScaled(loaded(0))},0,1)" }
    }
    HdrTransfer.Hlg -> {
      val shown = stored(0, luminance(light.map { "max($it,0)" }))
      val divisor = "pow(${loaded(0)},${(HLG_SYSTEM_GAMMA - 1.0) / HLG_SYSTEM_GAMMA})"
      light.map {
        val scene = stored(1, "if(gt(${loaded(0)},0),max($it,0)/$divisor,0)")
        "$shown${scene}clip(${hlgSignal(loaded(1))},0,1)"
      }
    }
  }

private fun luminance(rgb: List<String>): String =
  "($BT2020_LUMA_R*(${rgb[0]})+$BT2020_LUMA_G*(${rgb[1]})+$BT2020_LUMA_B*(${rgb[2]}))"

// Stores a figure in one of ffmpeg's expression variables, ahead of the expression that loads it.
private fun stored(
  slot: Int,
  figure: String,
): String = "st($slot,$figure);"

private fun loaded(slot: Int): String = "ld($slot)"

// Where HLG's decode keeps the three signal channels, clear of the three scene lights it stores from them.
private const val SIGNAL_SLOT = 3
