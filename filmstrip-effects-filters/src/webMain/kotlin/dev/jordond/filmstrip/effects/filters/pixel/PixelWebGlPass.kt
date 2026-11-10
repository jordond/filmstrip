package dev.jordond.filmstrip.effects.filters.pixel

import dev.jordond.filmstrip.effect.WebGlPass
import dev.jordond.filmstrip.effect.WebGlStep

/**
 * A [pixel] as one browser pass running [PixelShader], with its uniforms read off the frame's size.
 *
 * Every texture the compositor hands a pass runs up the picture, so the shader counts rows down from the other end.
 */
internal fun pixelPass(pixel: Pixel): WebGlPass =
  WebGlPass(
    pixelProgramKey(pixel.palette),
    listOf(
      WebGlStep(
        name = "cells",
        fragmentSource = PixelShader.fragment(pixel.palette),
        reads = mapOf(PixelShader.TEXTURE to WebGlStep.INPUT),
      ) { _, width, height -> PixelShader.uniforms(pixel, width, height) + (PixelShader.FLIP_V to floatArrayOf(1f)) },
    ),
  )

/**
 * The program key for a pass drawing [palette], or levels alone when it is null.
 *
 * The compositor links one program per key, and [PixelShader.fragment] writes each palette's colours into its source,
 * so the key carries those colours as the codes the source holds.
 */
internal fun pixelProgramKey(palette: PixelPalette?): String =
  if (palette == null) {
    "${Pixel.ID}/levels"
  } else {
    "${Pixel.ID}/" + palette.colors.joinToString("-") { (it and RGB_MASK).toString(HEX).padStart(HEX_DIGITS, '0') }
  }

private const val RGB_MASK = 0xFFFFFF
private const val HEX = 16
private const val HEX_DIGITS = 6
