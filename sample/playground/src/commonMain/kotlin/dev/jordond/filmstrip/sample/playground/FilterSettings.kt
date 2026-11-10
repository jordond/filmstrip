package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.camcorder.camcorder
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.film
import dev.jordond.filmstrip.effects.filters.glitch.glitch
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.nightvision.nightVision
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.pixel.pixel
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.TEAL_AND_ORANGE_CUBE

/**
 * The look the filters sample puts on its clip, one at a time.
 *
 * @property hasIntensity Whether the look takes an intensity, which the pixel look does not.
 */
internal enum class FilterLook(
  val label: String,
  val hasIntensity: Boolean = true,
) {
  None("NONE"),
  Vhs("VHS"),
  Crt("CRT"),
  Glitch("GLITCH"),
  Lut("LUT"),
  NightVision("NIGHT VISION"),
  Pixel("PIXEL", hasIntensity = false),
  Film("FILM", hasIntensity = false),
}

/**
 * Which tracking band the VHS look runs, bands now and then, one rolling band, or none.
 */
internal enum class TrackingMode(
  val label: String,
) {
  Events("EVENTS"),
  Rolling("ROLLING"),
  Off("OFF"),
}

/**
 * The colours the pixel look snaps to, a [PixelPalette] preset of the same name or none for the mosaic alone.
 */
internal enum class PixelColours(
  val label: String,
  val palette: PixelPalette?,
) {
  GameBoy("GAME BOY", PixelPalette.GameBoy),
  Cga("CGA", PixelPalette.Cga),
  Mosaic("MOSAIC", null),
}

/**
 * The table the LUT look reads, the sample's teal and orange grade.
 */
internal val tealAndOrange: CubeLut by lazy { CubeLut.parse(TEAL_AND_ORANGE_CUBE) }

/**
 * The look the filters sample applies, how strong each look is, the tape, tracking band, snow and camcorder display
 * VHS plays with, the colours the pixel look snaps to and whether Film takes its Super 8 preset. Each look keeps its
 * own intensity, so switching away and back finds it where it was.
 */
internal data class FilterSettings(
  val look: FilterLook = FilterLook.Vhs,
  val intensities: Map<FilterLook, Float> = emptyMap(),
  val tape: TapeSpeed = TapeSpeed.EP,
  val tracking: TrackingMode = TrackingMode.Events,
  val snow: Float = Tracking.DEFAULT_SNOW,
  val camcorder: CamcorderSettings = CamcorderSettings(),
  val colours: PixelColours = PixelColours.GameBoy,
  val super8: Boolean = false,
) {
  /**
   * How strong [look] is, full strength until it is moved.
   */
  fun intensity(look: FilterLook = this.look): Float = intensities[look] ?: FULL

  /**
   * These settings with the current look at [value].
   */
  fun withIntensity(value: Float): FilterSettings = copy(intensities = intensities + (look to value))

  companion object {
    /**
     * The intensity every look defaults to.
     */
    const val FULL: Float = 1f
  }
}

/**
 * The edit [settings] describe over [source]: the clip, with the chosen look on the composition.
 */
internal fun filtersEdit(
  source: MediaSource,
  settings: FilterSettings,
): Composition =
  compositionOf {
    clip(source)
    effects {
      when (settings.look) {
        FilterLook.None -> Unit
        FilterLook.Vhs -> vhsEffects(settings)
        FilterLook.Crt -> crt(intensity = settings.intensity())
        FilterLook.Glitch -> glitch(intensity = settings.intensity())
        FilterLook.Lut -> lut(tealAndOrange, intensity = settings.intensity())
        FilterLook.NightVision -> nightVision(intensity = settings.intensity())
        FilterLook.Pixel -> pixel(palette = settings.colours.palette)
        FilterLook.Film -> if (settings.super8) add(Film.Super8) else film()
      }
    }
  }

private fun EffectsBuilder.vhsEffects(settings: FilterSettings) {
  val display = settings.camcorder
  vhs {
    intensity = settings.intensity()
    tape = settings.tape
    tracking =
      when (settings.tracking) {
        TrackingMode.Events -> events { snow = settings.snow }
        TrackingMode.Rolling -> rolling { snow = settings.snow }
        TrackingMode.Off -> Tracking.Off
      }
    if (display.shown && display.mode == CamcorderMode.OnTape) camcorder { display.addTo(this) }
  }
  if (display.shown && display.mode == CamcorderMode.Clean) camcorder { display.addTo(this) }
}

/**
 * The Kotlin that builds the same look as [settings], naming only the arguments and settings that differ from their
 * defaults.
 */
internal fun filtersCode(settings: FilterSettings): List<List<CodePart>> {
  val call =
    when (settings.look) {
      FilterLook.None -> {
        listOf(listOf(CodePart("  // Pick a look", CodeKind.Comment)))
      }
      FilterLook.Vhs -> {
        vhsCode(settings)
      }
      FilterLook.Film if settings.super8 -> {
        listOf(
          listOf(
            CodePart("  add(", CodeKind.Call),
            CodePart("Film.Super8", CodeKind.Value),
            CodePart(")", CodeKind.Call),
          ),
        )
      }
      FilterLook.Crt, FilterLook.Glitch, FilterLook.Lut, FilterLook.NightVision, FilterLook.Pixel, FilterLook.Film -> {
        val intensity = settings.intensity()
        val arguments =
          buildList {
            if (settings.look == FilterLook.Lut) add("table")
            if (settings.look.hasIntensity && intensity != FilterSettings.FULL) {
              add("intensity = ${floatLiteral(intensity)}")
            }
            if (settings.look == FilterLook.Pixel && settings.colours.palette != null) {
              add("palette = PixelPalette.${settings.colours.name}")
            }
          }
        listOf(
          listOf(
            CodePart("  ${settings.look.name.replaceFirstChar { it.lowercase() }}(", CodeKind.Call),
            CodePart(arguments.joinToString(", "), CodeKind.Value),
            CodePart(")", CodeKind.Call),
          ),
        )
      }
    }
  return buildList {
    if (settings.look == FilterLook.Lut) {
      add(
        listOf(
          CodePart("val table = CubeLut.parse(", CodeKind.Frame),
          CodePart("tealAndOrangeCube", CodeKind.Value),
          CodePart(")", CodeKind.Frame),
        ),
      )
    }
    add(listOf(CodePart("clip(tearsOfSteel)", CodeKind.Frame)))
    add(listOf(CodePart("effects {", CodeKind.Frame)))
    addAll(call)
    add(listOf(CodePart("}", CodeKind.Frame)))
  }
}

private fun vhsCode(settings: FilterSettings): List<List<CodePart>> {
  val display = settings.camcorder
  val intensity = settings.intensity()
  val body =
    buildList {
      if (intensity != FilterSettings.FULL) add(settingCode("intensity", floatLiteral(intensity)))
      if (settings.tape != TapeSpeed.EP) add(settingCode("tape", "TapeSpeed.${settings.tape.name}"))
      trackingCode(settings)?.let { add(settingCode("tracking", it)) }
      if (display.shown && display.mode == CamcorderMode.OnTape) addAll(camcorderCode(display, "    "))
    }
  return buildList {
    if (body.isEmpty()) {
      add(listOf(CodePart("  vhs()", CodeKind.Call)))
    } else {
      add(listOf(CodePart("  vhs {", CodeKind.Call)))
      addAll(body)
      add(listOf(CodePart("  }", CodeKind.Call)))
    }
    if (display.shown && display.mode == CamcorderMode.Clean) addAll(camcorderCode(display, "  "))
  }
}

private fun trackingCode(settings: FilterSettings): String? {
  val snow = if (settings.snow == Tracking.DEFAULT_SNOW) null else " { snow = ${floatLiteral(settings.snow)} }"
  return when (settings.tracking) {
    TrackingMode.Events -> snow?.let { "events$it" }
    TrackingMode.Rolling -> "rolling${snow ?: "()"}"
    TrackingMode.Off -> "Tracking.Off"
  }
}

private fun settingCode(
  name: String,
  value: String,
): List<CodePart> = listOf(CodePart("    $name = ", CodeKind.Call), CodePart(value, CodeKind.Value))

private fun camcorderCode(
  display: CamcorderSettings,
  indent: String,
): List<List<CodePart>> =
  buildList {
    add(listOf(CodePart("${indent}camcorder {", CodeKind.Call)))
    display.ordered.flatMap { it.code }.forEach { add(listOf(CodePart("$indent  $it", CodeKind.Value))) }
    add(listOf(CodePart("$indent}", CodeKind.Call)))
  }

/**
 * What the status line says about [settings], such as `VHS · LP`, `VHS · EP · ROLLING · ON TAPE`, `CRT 0.5`,
 * `PIXEL · CGA` or `FILM · SUPER 8`.
 */
internal fun filterSummary(settings: FilterSettings): String {
  if (settings.look == FilterLook.None) return "NO LOOK"
  if (settings.look == FilterLook.Film) return if (settings.super8) "FILM · SUPER 8" else "FILM"
  val intensity = settings.intensity()
  val strength = if (intensity == FilterSettings.FULL) "" else " ${floatLiteral(intensity).removeSuffix("f")}"
  if (settings.look == FilterLook.Pixel) return "${settings.look.label}$strength · ${settings.colours.label}"
  if (settings.look != FilterLook.Vhs) return "${settings.look.label}$strength"
  val tracking = if (settings.tracking == TrackingMode.Events) "" else " · ${settings.tracking.label}"
  val camcorder = if (settings.camcorder.shown) " · ${settings.camcorder.mode.label}" else ""
  return "${settings.look.label}$strength · ${settings.tape.name}$tracking$camcorder"
}
