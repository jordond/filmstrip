package dev.jordond.filmstrip.effects.color

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.media.HLG_SYSTEM_GAMMA
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.SDR_DISPLAY_GAMMA
import dev.jordond.filmstrip.media.sdrSignalCeiling

/**
 * What a shader needs to move a channel between the light a backend holds and an SDR signal, all
 * derived from the shared constants rather than typed into the shader.
 *
 * @property white Reference white as a fraction of the display light the backend stores as one, so
 * a channel divided by it is the light an SDR display shows for a full signal.
 * @property displayGamma The power an SDR display raises its signal by on the way to light.
 * @property ootfGamma The per channel power between what the texture holds and display light.
 * HLG's system gamma for a backend holding scene light, and one for a backend already holding
 * display light.
 * @property ceiling The signal the transfer's peak encodes to, where the matrix's output is clamped.
 */
@InternalFilmstripApi
public class HdrColorMatrixUniforms(
  public val white: Float,
  public val displayGamma: Float,
  public val ootfGamma: Float,
  public val ceiling: Float,
)

/**
 * The figures a colour matrix pass reads a frame of this transfer through.
 *
 * @param white Reference white in the units the backend's own texture holds. A backend storing
 * display light normalised against reference white passes one.
 * @param holdsSceneLight Whether the texture holds HLG scene light rather than display light. A
 * backend that has already run the opto-optical transfer passes false, and then both transfers read
 * the frame the same way.
 */
@InternalFilmstripApi
public fun HdrTransfer.hdrColorMatrixUniforms(
  white: Float,
  holdsSceneLight: Boolean,
): HdrColorMatrixUniforms =
  HdrColorMatrixUniforms(
    white = white,
    displayGamma = SDR_DISPLAY_GAMMA.toFloat(),
    ootfGamma = if (holdsSceneLight) HLG_SYSTEM_GAMMA.toFloat() else 1f,
    ceiling = sdrSignalCeiling,
  )

/**
 * The GLSL a backend pastes in to run a colour matrix on a frame that keeps its HDR grade.
 *
 * One function and no `main`, so a backend with a pass of its own for the matrix and a backend
 * running it inside a larger fragment shader compile the same body. Every figure arrives as an
 * argument, which is what lets the two callers hold light in different units without the arithmetic
 * differing.
 *
 * The light goes to the signal an SDR display at reference white would have been fed, the matrix
 * runs there, the result is floored at black and clamped where the format runs out, and it comes
 * back the way it went in.
 */
@InternalFilmstripApi
public const val HDR_COLOR_MATRIX_GLSL: String =
  """
  vec3 filmstripGradeHdr(
    vec3 light,
    mat4 colorMatrix,
    float white,
    float displayGamma,
    float ootfGamma,
    float ceiling
  ) {
    vec3 display = pow(max(light, 0.0), vec3(ootfGamma));
    vec3 signal = pow(display / white, vec3(1.0 / displayGamma));
    vec3 graded = clamp((colorMatrix * vec4(signal, 1.0)).rgb, 0.0, ceiling);
    vec3 lit = pow(graded, vec3(displayGamma)) * white;
    return pow(lit, vec3(1.0 / ootfGamma));
  }
  """
