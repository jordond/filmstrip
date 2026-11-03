package dev.jordond.filmstrip.effects

import dev.jordond.filmstrip.color.BT2020_LUMA_B
import dev.jordond.filmstrip.color.BT2020_LUMA_G
import dev.jordond.filmstrip.color.BT2020_LUMA_R
import dev.jordond.filmstrip.color.HLG_A
import dev.jordond.filmstrip.color.HLG_B
import dev.jordond.filmstrip.color.HLG_C
import dev.jordond.filmstrip.color.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.color.PQ_C1
import dev.jordond.filmstrip.color.PQ_C2
import dev.jordond.filmstrip.color.PQ_C3
import dev.jordond.filmstrip.color.PQ_M1
import dev.jordond.filmstrip.color.PQ_M2
import dev.jordond.filmstrip.media.HdrTransfer

// The transfer functions an HDR grade is written in, spelled as ffmpeg expressions. Every constant is core's, the
// same numbers every other backend reads.

private fun pqScaled(light: String): String = "pow($light,$PQ_M1)"

private fun pqSignalFromScaled(scaled: String): String = "pow(($PQ_C1+$PQ_C2*$scaled)/(1+$PQ_C3*$scaled),$PQ_M2)"

/**
 * Encodes HLG [scene] light as its signal through the OETF.
 *
 * The square root runs up to a twelfth of the scene's peak and the logarithm above it, on core's HLG constants.
 */
internal fun hlgSignal(scene: String): String =
  "if(lte($scene,1/12),sqrt(3*$scene),$HLG_A*log(12*$scene-$HLG_B)+$HLG_C)"

/**
 * Takes a pixel's three channels of HLG scene light to linear BT.2020 display light, one expression per channel.
 *
 * The light is a fraction of the nominal thousand cd/m2 peak. HLG runs its opto-optical transfer on the scene's
 * luminance, so every channel of a color is scaled by the same figure rather than raised to a power on its own.
 *
 * @param scene The red, green and blue scene light, each an expression in the range zero to one.
 * @return Red, green and blue display light, in that order.
 */
internal fun displayLightFromHlgScene(scene: List<String>): List<String> {
  val gain = "pow(${luminance(scene)},${HLG_SYSTEM_GAMMA - 1.0})"
  return scene.map { "($it)*$gain" }
}

/**
 * Encodes linear BT.2020 display light as a pixel's three signal channels.
 *
 * The light is a fraction of the transfer's peak, which is ten thousand cd/m2 on PQ and the nominal thousand on HLG.
 * Each result is clipped to the range zero to one, which is where the format runs out.
 *
 * A figure an expression reads more than once is worked out once and stored in one of ffmpeg's expression variables,
 * so geq runs each transfer once per figure.
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
