package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_FILL
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_OFFSET
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BLACK
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BRIGHT
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.CENTRE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.DARK
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.MID_RESPONSE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.TIMEOUT
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.WIDE_CANVAS
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.BloomParams
import dev.jordond.filmstrip.effects.filters.bloom.BloomSignal
import dev.jordond.filmstrip.effects.filters.filters
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.transform.internal.hdr.Rgb
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import kotlinx.coroutines.test.runTest
import kotlin.math.abs
import kotlin.test.Test

/**
 * Where a [Bloom] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Every read here is of the glow, whose figure is large enough to survive an encoder, and is held against the signal
 * worked over the same read with no [Bloom]. How close each pixel lands to the signal is read off the kernels' own
 * output in the filters module.
 */
class AppleBloomHardwareTest {
  private val exports = GlowExports("bloom")

  @Test
  fun `a composition bloom glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source)))), listOf(GLOW_ONLY)),
        ::glowOver,
      )
    }

  @Test
  fun `a clip bloom glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source, effects = listOf(GLOW_ONLY)))))),
        ::glowOver,
      )
    }

  @Test
  fun `a composition bloom glows from the picture into a bar`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = exports.flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BLACK))
      }

      val plain = exports.export(edit(listOf(crop)), "bar-plain")
      val bloomed = exports.export(edit(listOf(crop, GLOW_ONLY)), "bar-bloom")

      val fx = exports.pictureRight(plain) + BAR_OFFSET
      val lift =
        frameOf(bloomed).average(fx, CENTRE).first.toDouble() - frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("just off the picture lifted $lift codes") { lift shouldBeGreaterThan WRITTEN_COLOUR_CODES }
      exports.remove(plain)
      exports.remove(bloomed)
    }

  @Test
  fun `a clip bloom leaves a canvas bar as its fill`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = exports.flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = exports.export(edit(listOf(crop)), "canvas-plain")
      val bloomed =
        exports.export(
          edit(listOf(crop, Bloom(intensity = 1f, threshold = 0.35f, spread = 1f))),
          "canvas-bloom",
        )

      val fx = exports.pictureRight(plain) + BAR_OFFSET
      val read = frameOf(bloomed).average(fx, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      exports.remove(plain)
      exports.remove(bloomed)
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("dark", DARK)
      val swatch = exports.swatch()

      val plain = exports.redOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "overlay-plain")
      val bloomed =
        exports.redOfSwatch(
          Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, GLOW_ONLY)),
          "overlay-bloom",
        )

      withClue("the swatch read $bloomed against $plain") { abs(bloomed - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  // A flat picture blurs to itself, so its glow is its own response.
  private fun glowOver(picture: Rgb): Rgb {
    val params = BloomParams.of(GLOW_ONLY)
    val response = BloomSignal.response(picture.r, picture.g, picture.b, params.threshold)
    val bloomed = BloomSignal.bloomed(picture.r, picture.g, picture.b, response, params)
    return Rgb(bloomed[0], bloomed[1], bloomed[2])
  }

  private companion object {
    val GLOW_ONLY = Bloom(intensity = 1f)
  }
}
