@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import androidx.compose.runtime.Stable
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.setValue
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.ColorMatrix
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.colorMatrix
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.grayscale
import dev.jordond.filmstrip.effects.color.hueRotate
import dev.jordond.filmstrip.effects.color.invert
import dev.jordond.filmstrip.effects.color.rgbAdjustment
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.sepia
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.geometry.FlipAxis

/**
 * Which effects a panel that can do both is editing, the selected clip's own or the whole edit's.
 */
public enum class EditScope {
  Clip,
  Edit,
}

/**
 * The colour knobs of one scope. The edit holds one for every clip at once, and each clip holds its own.
 */
@Stable
public class GradeState {
  var brightness: Float by mutableStateOf(1f)
  var channelRed: Float by mutableStateOf(1f)
  var channelGreen: Float by mutableStateOf(1f)
  var channelBlue: Float by mutableStateOf(1f)
  var contrast: Float by mutableStateOf(1f)
  var saturation: Float by mutableStateOf(1f)
  var hueDegrees: Float by mutableStateOf(0f)
  var sepia: Float by mutableStateOf(0f)
  var invert: Float by mutableStateOf(0f)
  var customMatrix: ColorMatrix by mutableStateOf(ColorMatrix.Identity)

  /**
   * The color effects the knobs author, in the order the pipeline runs them, or an empty list while
   * every knob sits at its default. A saturation of zero is authored as the grayscale shorthand, and
   * the custom matrix goes last, after everything the sliders authored.
   */
  val effects: List<EffectSpec>
    get() =
      EffectsBuilder()
        .apply {
          if (brightness != 1f) brightness(brightness)
          if (channelRed != 1f || channelGreen != 1f || channelBlue != 1f) {
            rgbAdjustment(channelRed, channelGreen, channelBlue)
          }
          if (contrast != 1f) contrast(contrast)
          when {
            saturation == 0f -> grayscale()
            saturation != 1f -> saturation(saturation)
          }
          if (hueDegrees != 0f) hueRotate(hueDegrees)
          if (sepia != 0f) sepia(sepia)
          if (invert != 0f) invert(invert)
          if (customMatrix != ColorMatrix.Identity) colorMatrix(customMatrix)
        }.build()

  /**
   * Whether any colour knob is off its default.
   */
  val graded: Boolean
    get() = effects.isNotEmpty()

  /**
   * Puts every colour knob back where it started.
   */
  fun reset() {
    brightness = 1f
    channelRed = 1f
    channelGreen = 1f
    channelBlue = 1f
    contrast = 1f
    saturation = 1f
    hueDegrees = 0f
    sepia = 0f
    invert = 0f
    customMatrix = ColorMatrix.Identity
  }
}

/**
 * The turn and the mirroring of one scope. The edit holds one for every clip at once, and each clip holds its own.
 */
@Stable
public class TransformState {
  var rotationDegrees: Int by mutableStateOf(0)
  var flipHorizontal: Boolean by mutableStateOf(false)
  var flipVertical: Boolean by mutableStateOf(false)

  /**
   * The geometry effects the knobs author, the turn first, or an empty list while nothing is turned or mirrored.
   */
  val effects: List<EffectSpec>
    get() =
      EffectsBuilder()
        .apply {
          if (rotationDegrees != 0) rotate(rotationDegrees)
          if (flipHorizontal) flip(FlipAxis.Horizontal)
          if (flipVertical) flip(FlipAxis.Vertical)
        }.build()

  /**
   * Whether the turn leaves the frame on its side, which swaps its width and height.
   */
  val sideways: Boolean
    get() = rotationDegrees == 90 || rotationDegrees == 270

  /**
   * Whether anything is turned or mirrored.
   */
  val touched: Boolean
    get() = effects.isNotEmpty()

  /**
   * Puts the frame back the way it was shot.
   */
  fun reset() {
    rotationDegrees = 0
    flipHorizontal = false
    flipVertical = false
  }
}

/**
 * The grade the adjust panel is editing, the selected clip's while the scope switch points at it.
 */
internal val SampleAppState.gradeInScope: GradeState
  get() = clipInScope?.grade ?: edit.grade

/**
 * The transform the transform panel is editing, the selected clip's while the scope switch points at it.
 */
internal val SampleAppState.transformInScope: TransformState
  get() = clipInScope?.transform ?: edit.transform

private val SampleAppState.clipInScope: ClipEdit?
  get() = sequence.selectedClip.takeIf { edit.scope == EditScope.Clip }
