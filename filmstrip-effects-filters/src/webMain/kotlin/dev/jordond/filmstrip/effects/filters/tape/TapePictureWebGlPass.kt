package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effect.PreviewClock
import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import kotlin.time.DurationUnit

/**
 * A [look] as one browser pass running [TapePictureShader], with its uniforms read off [TapePictureSignal.tapeSeconds]
 * of the frame's composition time, under [previewClock], and its size.
 *
 * Every texture the compositor hands a pass runs up the picture. A clip's frame holds clear bars, so alpha moves with
 * the displacement, and a composition's frame is opaque everywhere, so moving it changes nothing there.
 */
internal fun tapePicturePass(
  look: TapeLook,
  previewClock: PreviewClock? = null,
): WebGlPass =
  WebGlPass(
    look.id.value,
    listOf(
      WebGlStep(
        name = "tape",
        fragmentSource = TapePictureShader.FRAGMENT,
        reads = mapOf(TapePictureShader.TEXTURE to WebGlStep.INPUT),
      ) { time, width, height ->
        buildMap {
          val seconds = TapePictureSignal.tapeSeconds(look, time.toDouble(DurationUnit.SECONDS), previewClock)
          TapePictureShader.uniforms(look, seconds).forEach { (name, value) ->
            put(name, floatArrayOf(value))
          }
          put(TapePictureShader.RESOLUTION, floatArrayOf(width.toFloat(), height.toFloat()))
          put(TapePictureShader.FLIP_V, floatArrayOf(1f))
        }
      },
    ),
  )
