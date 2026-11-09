package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.filters.crt.crt
import dev.jordond.filmstrip.effects.filters.lut.CubeLut
import dev.jordond.filmstrip.effects.filters.lut.lut
import dev.jordond.filmstrip.effects.filters.vhs.TapeSpeed
import dev.jordond.filmstrip.effects.filters.vhs.vhs
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.TEAL_AND_ORANGE_CUBE

/**
 * The look the filters sample puts on its clip, one at a time.
 */
internal enum class FilterLook(
  val label: String,
) {
  None("NONE"),
  Vhs("VHS"),
  Crt("CRT"),
  Lut("LUT"),
}

/**
 * The table the LUT look reads, the sample's teal and orange grade.
 */
internal val tealAndOrange: CubeLut by lazy { CubeLut.parse(TEAL_AND_ORANGE_CUBE) }

/**
 * The look the filters sample applies, how strong each look is and the tape VHS plays. Each look keeps its own
 * intensity, so switching away and back finds it where it was.
 */
internal data class FilterSettings(
  val look: FilterLook = FilterLook.Vhs,
  val intensities: Map<FilterLook, Float> = emptyMap(),
  val tape: TapeSpeed = TapeSpeed.EP,
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
      val intensity = settings.intensity()
      when (settings.look) {
        FilterLook.None -> Unit
        FilterLook.Vhs -> vhs(intensity = intensity, tape = settings.tape)
        FilterLook.Crt -> crt(intensity = intensity)
        FilterLook.Lut -> lut(tealAndOrange, intensity = intensity)
      }
    }
  }

/**
 * The Kotlin that builds the same look as [settings], naming only the arguments that differ from their defaults.
 */
internal fun filtersCode(settings: FilterSettings): List<List<CodePart>> {
  val intensity = settings.intensity()
  val arguments =
    buildList {
      if (settings.look == FilterLook.Lut) add("table")
      if (intensity != FilterSettings.FULL) add("intensity = ${floatLiteral(intensity)}")
      if (settings.look == FilterLook.Vhs &&
        settings.tape != TapeSpeed.EP
      ) {
        add("tape = TapeSpeed.${settings.tape.name}")
      }
    }
  val call =
    when (settings.look) {
      FilterLook.None -> {
        listOf(CodePart("  // Pick a look", CodeKind.Comment))
      }
      else -> {
        listOf(
          CodePart("  ${settings.look.name.lowercase()}(", CodeKind.Call),
          CodePart(arguments.joinToString(", "), CodeKind.Value),
          CodePart(")", CodeKind.Call),
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
    add(call)
    add(listOf(CodePart("}", CodeKind.Frame)))
  }
}

/**
 * What the status line says about [settings], such as `VHS · LP` or `CRT 0.5`.
 */
internal fun filterSummary(settings: FilterSettings): String {
  if (settings.look == FilterLook.None) return "NO LOOK"
  val intensity = settings.intensity()
  val strength = if (intensity == FilterSettings.FULL) "" else " ${floatLiteral(intensity).removeSuffix("f")}"
  val tape = if (settings.look == FilterLook.Vhs) " · ${settings.tape.name}" else ""
  return "${settings.look.label}$strength$tape"
}
