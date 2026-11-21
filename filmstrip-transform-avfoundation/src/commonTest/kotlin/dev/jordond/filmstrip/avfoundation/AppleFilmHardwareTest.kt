package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_FILL
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BAR_OFFSET
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BLACK
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.BRIGHT
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.CENTRE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.DARK
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.MAX_CODE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.MID_RESPONSE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.SWATCH_CODE
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.TIMEOUT
import dev.jordond.filmstrip.avfoundation.GlowExports.Companion.WIDE_CANVAS
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.FilmLeak
import dev.jordond.filmstrip.effects.filters.film.FilmParams
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
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
 * Where a [Film] lands in a real Apple export: what it reaches at each scope, and what stays clean.
 *
 * Every read here is of the glow, whose figure is large enough to survive an encoder, and is held against the signal
 * worked over the same read with no [Film]. How close each pixel lands to the signal, grain included, is read off the
 * kernels' own output in the filters module.
 */
class AppleFilmHardwareTest {
  private val exports = GlowExports("film")

  @Test
  fun `a composition film glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source)))), listOf(GLOW_ONLY)),
        ::glowOver,
      )
    }

  @Test
  fun `a clip film glows the picture on the signal`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("mid", MID_RESPONSE)

      exports.assertGlowsOnTheSignal(
        source,
        Composition(listOf(Track(listOf(Clip(source, effects = listOf(GLOW_ONLY)))))),
        ::glowOver,
      )
    }

  @Test
  fun `a composition film leaks on the signal where the leak is brightest`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("dark", DARK)
      val edit = { effects: List<EffectSpec> -> Composition(listOf(Track(listOf(Clip(source)))), effects) }

      val plain = exports.export(edit(emptyList()), "leak-plain")
      val leaked = exports.export(edit(listOf(LEAK_ONLY)), "leak-film")

      val under = codesOf(plain)
      val aspect = under.width.toDouble() / under.height
      val place = LEAK_PLACES.maxBy { leakAt(it, aspect).max() }
      val code = DARK / MAX_CODE
      val wanted = FilmLeak.leaked(code, code, code, leakAt(place, aspect)).map { (it - code) * MAX_CODE }
      val read = codesOf(leaked).average(place.first, place.second)
      val base = under.average(place.first, place.second)
      val lifts = listOf(read.first - base.first, read.second - base.second, read.third - base.third)

      withClue("the leak has to clear what an encoder moves for this to tell, ${wanted[0]} codes") {
        wanted[0] shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
      }
      lifts.forEachIndexed { channel, lift ->
        withClue("channel $channel at $place lifted $lift against ${wanted[channel]}") {
          abs(lift - wanted[channel]) shouldBeLessThan WRITTEN_COLOUR_CODES
        }
      }
      exports.remove(plain)
      exports.remove(leaked)
    }

  @Test
  fun `a composition film glows from the picture into a bar`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = exports.flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source)))), effects, fill = Fill.Solid(BLACK))
      }

      val plain = exports.export(edit(listOf(crop)), "bar-plain")
      val filmed = exports.export(edit(listOf(crop, GLOW_ONLY)), "bar-film")

      val fx = exports.pictureRight(plain) + BAR_OFFSET
      val lift =
        frameOf(filmed).average(fx, CENTRE).first.toDouble() - frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("just off the picture lifted $lift codes") { lift shouldBeGreaterThan WRITTEN_COLOUR_CODES }
      exports.remove(plain)
      exports.remove(filmed)
    }

  @Test
  fun `a clip film leaves a canvas bar as its fill`() =
    runTest(timeout = TIMEOUT) {
      val crop = Crop(WIDE_CANVAS, fit = Fit.Contain)
      val source = exports.flatClip("bright", BRIGHT)
      val edit = { effects: List<EffectSpec> ->
        Composition(listOf(Track(listOf(Clip(source, effects = effects)))), fill = Fill.Solid(BAR_FILL))
      }

      val plain = exports.export(edit(listOf(crop)), "canvas-plain")
      val filmed = exports.export(edit(listOf(crop, Film(grain = 1f, halation = 1f))), "canvas-film")

      val fx = exports.pictureRight(plain) + BAR_OFFSET
      val read = frameOf(filmed).average(fx, CENTRE).first.toDouble()
      val wanted = frameOf(plain).average(fx, CENTRE).first.toDouble()
      withClue("the bar read $read against $wanted") { abs(read - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
      exports.remove(plain)
      exports.remove(filmed)
    }

  @Test
  fun `an overlay at the same scope stays clean`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("dark", DARK)
      val swatch = exports.swatch()

      val plain = exports.redOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "overlay-plain")
      val filmed =
        exports.redOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, GLOW_ONLY)), "overlay-film")

      withClue("the swatch read $filmed against $plain") { abs(filmed - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  @Test
  fun `an overlay at the same scope stays clean under super 8`() =
    runTest(timeout = TIMEOUT) {
      val source = exports.flatClip("dark", DARK)
      val swatch = exports.swatch()

      val plain = exports.redOfSwatch(Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch)), "super8-plain")
      val filmed =
        exports.redOfSwatch(
          Composition(listOf(Track(listOf(Clip(source)))), listOf(swatch, Film.Super8)),
          "super8-film",
        )

      withClue("the swatch read $filmed against $plain") { abs(filmed - plain) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  @Test
  fun `a clip overlay under a composition film is burned`() =
    runTest(timeout = TIMEOUT) {
      val clip = Clip(exports.flatClip("dark", DARK), effects = listOf(exports.swatch()))

      val filmed = exports.redOfSwatch(Composition(listOf(Track(listOf(clip))), listOf(GLOW_ONLY)), "burned-film")

      val under = SWATCH_CODE / MAX_CODE
      val response = FilmSignal.halationResponse(under, under, under)
      val wanted = FilmSignal.halated(under, under, under, response, FilmParams.of(GLOW_ONLY))[0] * MAX_CODE
      withClue("the glow has to clear what an encoder moves for this to tell") {
        (wanted - SWATCH_CODE) shouldBeGreaterThan WRITTEN_COLOUR_CODES * 2
      }
      withClue("the swatch read $filmed against $wanted") { abs(filmed - wanted) shouldBeLessThan WRITTEN_COLOUR_CODES }
    }

  // The leak's light at a place given as fractions of the frame, on the first frame's tick.
  private fun leakAt(
    place: Pair<Float, Float>,
    aspect: Double,
  ): DoubleArray =
    FilmLeak.leakAt(
      place.first.toDouble(),
      place.second.toDouble(),
      aspect,
      FilmSignal.grainTickAt(0.0),
      LEAK_ONLY.seed,
      LEAK_ONLY.leak.toDouble(),
    )

  private fun glowOver(picture: Rgb): Rgb {
    val response = FilmSignal.halationResponse(picture.r, picture.g, picture.b)
    val halated = FilmSignal.halated(picture.r, picture.g, picture.b, response, FilmParams.of(GLOW_ONLY))
    return Rgb(halated[0], halated[1], halated[2])
  }

  private companion object {
    val GLOW_ONLY = Film(grain = 0f, halation = 1f)
    val LEAK_ONLY = Film(grain = 0f, halation = 0f, leak = 1f)

    // Every tenth of the frame each way, clear of its edge, where a read of the leak is looked for.
    val LEAK_PLACES = (1..9).flatMap { y -> (1..9).map { x -> x / 10f to y / 10f } }
  }
}
