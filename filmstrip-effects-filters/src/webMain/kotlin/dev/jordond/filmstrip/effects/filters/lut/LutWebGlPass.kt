package dev.jordond.filmstrip.effects.filters.lut

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep
import dev.jordond.filmstrip.effect.WebGlTable

/**
 * A [lut] as one browser pass running [LutShader] over its [LutAtlas], with the intensity already in the table.
 */
internal fun lutPass(lut: Lut): WebGlPass {
  val atlas = lutAtlas(lut.table.blended(lut.intensity))
  val uniforms = LutShader.uniforms(atlas)
  return WebGlPass(
    Lut.ID.value,
    listOf(
      WebGlStep(
        name = "table",
        fragmentSource = LutShader.FRAGMENT,
        reads = mapOf(LutShader.TEXTURE to WebGlStep.INPUT),
        textures = mapOf(LutShader.TABLE to WebGlTable(atlas.width, atlas.height, atlas.bytes)),
      ) { _, _, _ -> uniforms },
    ),
  )
}
