package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.EffectsBuilder
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.betamax.BetaSpeed
import dev.jordond.filmstrip.effects.filters.betamax.betamax
import dev.jordond.filmstrip.effects.filters.bloom.Bloom
import dev.jordond.filmstrip.effects.filters.bloom.bloom
import dev.jordond.filmstrip.effects.filters.camcorder.camcorder
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.digicam.Digicam
import dev.jordond.filmstrip.effects.filters.digicam.digicam
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.effects.filters.film.film
import dev.jordond.filmstrip.effects.filters.glitch.glitch
import dev.jordond.filmstrip.effects.filters.hi8.Hi8Speed
import dev.jordond.filmstrip.effects.filters.hi8.hi8
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.nightvision.nightVision
import dev.jordond.filmstrip.effects.filters.pixel.PixelPalette
import dev.jordond.filmstrip.effects.filters.pixel.pixel
import dev.jordond.filmstrip.effects.filters.tape.TapeLookBuilder
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.TEAL_AND_ORANGE_CUBE
import kotlin.time.Duration.Companion.seconds

/**
 * The look the filters sample puts on its clip, one at a time.
 *
 * @property hasIntensity Whether the look takes an intensity, which the pixel, film, bloom and digicam looks do
 * not.
 */
internal enum class FilterLook(
  val label: String,
  val hasIntensity: Boolean = true,
) {
  None("NONE"),
  Tape("TAPE"),
  Crt("CRT"),
  Glitch("GLITCH"),
  Lut("LUT"),
  NightVision("NIGHT VISION"),
  Pixel("PIXEL", hasIntensity = false),
  Film("FILM", hasIntensity = false),
  Bloom("BLOOM", hasIntensity = false),
  Digicam("DIGICAM", hasIntensity = false),
}

/**
 * The film the film look runs, the default stock, the same with light leaks, a faded and breathing print, or the
 * Super 8 preset.
 */
internal enum class FilmStock(
  val label: String,
) {
  Default("DEFAULT"),
  Leak("LEAK"),
  Faded("FADED"),
  Super8("SUPER 8"),
}

/**
 * The tape format the tape look plays.
 *
 * @property builder The name of the function that adds the format's look.
 */
internal enum class TapeFormat(
  val label: String,
  val builder: String,
) {
  Vhs("VHS", "vhs"),
  Betamax("BETAMAX", "betamax"),
  Hi8("HI8", "hi8"),
}

/**
 * Which tracking band the tape look runs, bands now and then, one rolling band, or none.
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
 * The look the filters sample applies, how strong each look is, the format, speed, tracking band, snow, settle and
 * camcorder display the tape look plays with, the colours the pixel look snaps to, the stock Film runs, whether Bloom
 * takes its dreamy preset, and whether Digicam takes its CCD preset and its date stamp. Each look keeps its own
 * intensity and each tape format its own speed, so switching away and back finds it where it was.
 */
internal data class FilterSettings(
  val look: FilterLook = FilterLook.Tape,
  val intensities: Map<FilterLook, Float> = emptyMap(),
  val format: TapeFormat = TapeFormat.Vhs,
  val tape: TapeSpeed = TapeSpeed.EP,
  val beta: BetaSpeed = BetaSpeed.II,
  val hi8: Hi8Speed = Hi8Speed.SP,
  val tracking: TrackingMode = TrackingMode.Events,
  val snow: Float = Tracking.DEFAULT_SNOW,
  val settle: Boolean = false,
  val camcorder: CamcorderSettings = CamcorderSettings(),
  val colours: PixelColours = PixelColours.GameBoy,
  val film: FilmStock = FilmStock.Default,
  val blocks: Boolean = false,
  val dreamy: Boolean = false,
  val ccd: Boolean = false,
  val stamp: Boolean = false,
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
        FilterLook.None -> {
          Unit
        }
        FilterLook.Tape -> {
          tapeEffects(settings)
        }
        FilterLook.Crt -> {
          crt(intensity = settings.intensity())
        }
        FilterLook.Glitch -> {
          glitch(intensity = settings.intensity(), blocks = if (settings.blocks) 1f else 0f)
        }
        FilterLook.Lut -> {
          lut(tealAndOrange, intensity = settings.intensity())
        }
        FilterLook.NightVision -> {
          nightVision(intensity = settings.intensity())
        }
        FilterLook.Pixel -> {
          pixel(palette = settings.colours.palette)
        }
        FilterLook.Film -> {
          when (settings.film) {
            FilmStock.Default -> film()
            FilmStock.Leak -> film(leak = SAMPLE_LEAK)
            FilmStock.Faded -> film(breath = SAMPLE_BREATH, fade = SAMPLE_FADE)
            FilmStock.Super8 -> add(Film.Super8)
          }
        }
        FilterLook.Bloom -> {
          if (settings.dreamy) add(Bloom.Dreamy) else bloom()
        }
        FilterLook.Digicam -> {
          if (settings.ccd && !settings.stamp) {
            add(Digicam.Ccd)
          } else {
            digicam {
              if (settings.ccd) {
                sharpness = Digicam.Ccd.sharpness
                noise = Digicam.Ccd.noise
                clip = Digicam.Ccd.clip
              }
              if (settings.stamp) stamp()
            }
          }
        }
      }
    }
  }

private fun EffectsBuilder.tapeEffects(settings: FilterSettings) {
  val display = settings.camcorder
  val shared: TapeLookBuilder.() -> Unit = {
    intensity = settings.intensity()
    tracking =
      when (settings.tracking) {
        TrackingMode.Events -> events { snow = settings.snow }
        TrackingMode.Rolling -> rolling { snow = settings.snow }
        TrackingMode.Off -> Tracking.Off
      }
    if (settings.settle) settle(from = 1f, over = SETTLE_OVER)
    if (display.shown && display.mode == CamcorderMode.OnTape) camcorder { display.addTo(this) }
  }
  when (settings.format) {
    TapeFormat.Vhs -> {
      vhs {
        tape = settings.tape
        shared()
      }
    }
    TapeFormat.Betamax -> {
      betamax {
        tape = settings.beta
        shared()
      }
    }
    TapeFormat.Hi8 -> {
      hi8 {
        tape = settings.hi8
        shared()
      }
    }
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
      FilterLook.Tape -> {
        tapeCode(settings)
      }
      FilterLook.Film if settings.film == FilmStock.Super8 -> {
        presetCode("Film.Super8")
      }
      FilterLook.Bloom if settings.dreamy -> {
        presetCode("Bloom.Dreamy")
      }
      FilterLook.Digicam -> {
        digicamCode(settings)
      }
      FilterLook.Crt,
      FilterLook.Glitch,
      FilterLook.Lut,
      FilterLook.NightVision,
      FilterLook.Pixel,
      FilterLook.Film,
      FilterLook.Bloom,
      -> {
        val intensity = settings.intensity()
        val arguments =
          buildList {
            if (settings.look == FilterLook.Lut) add("table")
            if (settings.look.hasIntensity && intensity != FilterSettings.FULL) {
              add("intensity = ${floatLiteral(intensity)}")
            }
            if (settings.look == FilterLook.Glitch && settings.blocks) add("blocks = 1f")
            if (settings.look == FilterLook.Pixel && settings.colours.palette != null) {
              add("palette = PixelPalette.${settings.colours.name}")
            }
            if (settings.look == FilterLook.Film && settings.film == FilmStock.Leak) {
              add("leak = ${floatLiteral(SAMPLE_LEAK)}")
            }
            if (settings.look == FilterLook.Film && settings.film == FilmStock.Faded) {
              add("breath = ${floatLiteral(SAMPLE_BREATH)}")
              add("fade = ${floatLiteral(SAMPLE_FADE)}")
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

private fun presetCode(preset: String): List<List<CodePart>> =
  listOf(listOf(CodePart("  add(", CodeKind.Call), CodePart(preset, CodeKind.Value), CodePart(")", CodeKind.Call)))

// A digicam with a stamp is a block, and the CCD preset inside one names each of its values.
private fun digicamCode(settings: FilterSettings): List<List<CodePart>> {
  if (!settings.stamp) {
    return if (settings.ccd) presetCode("Digicam.Ccd") else listOf(listOf(CodePart("  digicam()", CodeKind.Call)))
  }
  val preset =
    if (settings.ccd) {
      listOf(
        settingCode("sharpness", floatLiteral(Digicam.Ccd.sharpness)),
        settingCode("noise", floatLiteral(Digicam.Ccd.noise)),
        settingCode("clip", floatLiteral(Digicam.Ccd.clip)),
      )
    } else {
      emptyList()
    }
  return listOf(listOf(CodePart("  digicam {", CodeKind.Call))) + preset +
    listOf(listOf(CodePart("    stamp()", CodeKind.Call)), listOf(CodePart("  }", CodeKind.Call)))
}

private fun tapeCode(settings: FilterSettings): List<List<CodePart>> {
  val display = settings.camcorder
  val intensity = settings.intensity()
  val body =
    buildList {
      if (intensity != FilterSettings.FULL) add(settingCode("intensity", floatLiteral(intensity)))
      speedCode(settings)?.let { add(settingCode("tape", it)) }
      trackingCode(settings)?.let { add(settingCode("tracking", it)) }
      if (settings.settle) add(listOf(CodePart("    settle(from = 1f, over = 3.seconds)", CodeKind.Call)))
      if (display.shown && display.mode == CamcorderMode.OnTape) addAll(camcorderCode(display, "    "))
    }
  return buildList {
    if (body.isEmpty()) {
      add(listOf(CodePart("  ${settings.format.builder}()", CodeKind.Call)))
    } else {
      add(listOf(CodePart("  ${settings.format.builder} {", CodeKind.Call)))
      addAll(body)
      add(listOf(CodePart("  }", CodeKind.Call)))
    }
    if (display.shown && display.mode == CamcorderMode.Clean) addAll(camcorderCode(display, "  "))
  }
}

// The speed the format plays at as the code names it, or null at the speed the format starts on.
private fun speedCode(settings: FilterSettings): String? =
  when (settings.format) {
    TapeFormat.Vhs -> settings.tape.takeIf { it != TapeSpeed.EP }?.let { "TapeSpeed.${it.name}" }
    TapeFormat.Betamax -> settings.beta.takeIf { it != BetaSpeed.II }?.let { "BetaSpeed.${it.name}" }
    TapeFormat.Hi8 -> settings.hi8.takeIf { it != Hi8Speed.SP }?.let { "Hi8Speed.${it.name}" }
  }

// The name of the speed the format plays at.
private fun speedName(settings: FilterSettings): String =
  when (settings.format) {
    TapeFormat.Vhs -> settings.tape.name
    TapeFormat.Betamax -> settings.beta.name
    TapeFormat.Hi8 -> settings.hi8.name
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
 * What the status line says about [settings], such as `VHS · LP`, `HI8 · SP · ROLLING · ON TAPE`, `CRT 0.5`,
 * `PIXEL · CGA`, `GLITCH · BLOCKS`, `FILM · SUPER 8`, `BLOOM · DREAMY` or `DIGICAM · CCD · STAMP`.
 */
internal fun filterSummary(settings: FilterSettings): String {
  if (settings.look == FilterLook.None) return "NO LOOK"
  if (settings.look == FilterLook.Film) {
    return if (settings.film == FilmStock.Default) "FILM" else "FILM · ${settings.film.label}"
  }
  if (settings.look == FilterLook.Bloom) return if (settings.dreamy) "BLOOM · DREAMY" else "BLOOM"
  if (settings.look == FilterLook.Digicam) {
    return "DIGICAM" + (if (settings.ccd) " · CCD" else "") + (if (settings.stamp) " · STAMP" else "")
  }
  val intensity = settings.intensity()
  val strength = if (intensity == FilterSettings.FULL) "" else " ${floatLiteral(intensity).removeSuffix("f")}"
  if (settings.look == FilterLook.Pixel) return "${settings.look.label}$strength · ${settings.colours.label}"
  if (settings.look == FilterLook.Glitch && settings.blocks) return "${settings.look.label}$strength · BLOCKS"
  if (settings.look != FilterLook.Tape) return "${settings.look.label}$strength"
  val tracking = if (settings.tracking == TrackingMode.Events) "" else " · ${settings.tracking.label}"
  val camcorder = if (settings.camcorder.shown) " · ${settings.camcorder.mode.label}" else ""
  return "${settings.format.label}$strength · ${speedName(settings)}$tracking$camcorder"
}

// How long the tape look takes to settle when the sample asks it to.
private val SETTLE_OVER = 3.seconds

// How bright the leaks are on the film look's leak stock.
private const val SAMPLE_LEAK = 0.6f

// How far the film look's faded stock breathes and how far its print has faded.
private const val SAMPLE_BREATH = 0.5f
private const val SAMPLE_FADE = 0.6f
