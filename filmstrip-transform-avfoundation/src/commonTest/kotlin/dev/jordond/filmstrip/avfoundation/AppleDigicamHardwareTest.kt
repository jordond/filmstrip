package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_FILL
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_OFFSET
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.CENTRE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.DARK
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.MID_RESPONSE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.TIMEOUT
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.WIDE_CANVAS
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.DigicamParams
import dev.jordond.filmstrip.effects.filters.digicam.DigicamSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Digicam] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Every read here is of the clip's push on a flat picture, whose figure is large enough to survive an encoder, and is
 * held against the signal worked over the same read with no [Digicam]. The halo and the noise are a pixel wide, which
 * an encoder does not keep, so how close each pixel lands to the signal is read off the kernels' own output in the
 * filters module.
 */
class AppleDigicamHardwareTest {
  private val exports = GlowExports("digicam")

  @Test
  fun `a composition digicam pushes the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source)))), listOf(PUSH_ONLY)),
        ::pushed,
      )
    }

  @Test
  fun `a clip digicam pushes the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source, effects = listOf(PUSH_ONLY)))))),
        ::pushed,
      )
    }

  @Test
  fun `a clip digicam leaves a canvas bar as its fill`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = exports.flatClip("mid", MID_RESPONSE)
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = exports.export(edit(listOf(crop)), "canvas-plain")
      val drawn = exports.export(edit(listOf(crop, Digicam(sharpness = 1f, noise = 0f, clip = 1f))), "canvas-digicam")

      val fx = exports.pictureRight(plain) + BAR_OFFSET
      val read = frameOf(drawn).average(fx, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      exports.remove(plain)
      exports.remove(drawn)
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("dark", DARK)
      val swatch = exports.swatch()

      val plain = exports.redOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "overlay-plain")
      val drawn =
        exports.redOfSwatch(
          Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, PUSH_ONLY)),
          "overlay-digicam",
        )

      withClue("the swatch read $drawn against $plain") { abs(drawn - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  // A flat picture blurs to itself, so the halo leaves it where it was and the clip gain alone moves it.
  private fun pushed(picture: Rgb): Rgb {
    val params = DigicamParams.of(PUSH_ONLY)
    val at = { code: Double -> DigicamSignal.clipped(DigicamSignal.sharpened(code, code, params), params) }
    return Rgb(at(picture.r), at(picture.g), at(picture.b))
  }

  private companion object {
    val PUSH_ONLY = Digicam(sharpness = 0.5f, noise = 0f, clip = 0.25f)
  }
}
