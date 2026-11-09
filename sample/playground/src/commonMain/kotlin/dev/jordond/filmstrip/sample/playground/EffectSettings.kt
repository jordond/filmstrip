package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.contrast
import dev.jordond.filmstrip.effects.color.hueRotate
import dev.jordond.filmstrip.effects.color.invert
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.color.sepia
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.kenBurns
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.effects.geometry.scale
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource
import kotlin.math.roundToInt

/**
 * An aspect ratio the playground can crop to, with the label its chip shows and the constant the code listing names.
 */
internal enum class CropChoice(
  val label: String,
  val aspect: AspectRatio?,
  val code: String?,
) {
  Off("OFF", null, null),
  Landscape("16:9", AspectRatio.Landscape, "AspectRatio.Landscape"),
  Classic("4:3", AspectRatio.Classic, "AspectRatio.Classic"),
  Square("1:1", AspectRatio.Square, "AspectRatio.Square"),
  Feed("4:5", AspectRatio.Feed, "AspectRatio.Feed"),
  Portrait("9:16", AspectRatio.Portrait, "AspectRatio.Portrait"),
}

/**
 * A Ken Burns move, the rectangle it starts on and the one it ends on.
 */
internal enum class KenBurnsMove(
  val label: String,
  val from: NormalizedRect,
  val to: NormalizedRect,
) {
  PushIn("PUSH IN", NormalizedRect.Full, NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)),
  PullOut("PULL OUT", NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f), NormalizedRect.Full),
  Pan("PAN", NormalizedRect(0f, 0.2f, 0.6f, 0.8f), NormalizedRect(0.4f, 0.2f, 1f, 0.8f)),
}

/**
 * One colour effect the playground has a slider for: its range, the value that leaves the frame alone and the call
 * that applies it.
 */
internal enum class ColorControl(
  val label: String,
  val call: String,
  val range: ClosedFloatingPointRange<Float>,
  val neutral: Float,
) {
  Brightness("Brightness", "brightness", 0f..2f, 1f),
  Contrast("Contrast", "contrast", 0f..2f, 1f),
  Saturation("Saturation", "saturation", 0f..2f, 1f),
  Hue("Hue rotate", "hueRotate", -180f..180f, 0f),
  Sepia("Sepia", "sepia", 0f..1f, 0f),
  Invert("Invert", "invert", 0f..1f, 0f),
}

/**
 * A starting look, the colour values its chip sets. Anything it leaves out goes back to neutral.
 */
internal enum class Look(
  val label: String,
  val values: Map<ColorControl, Float>,
) {
  None("NONE", emptyMap()),
  Warm("WARM", mapOf(ColorControl.Brightness to 1.05f, ColorControl.Saturation to 1.25f, ColorControl.Sepia to 0.2f)),
  Noir("NOIR", mapOf(ColorControl.Saturation to 0f, ColorControl.Contrast to 1.35f)),
  Faded(
    "FADED",
    mapOf(ColorControl.Brightness to 1.1f, ColorControl.Contrast to 0.8f, ColorControl.Saturation to 0.7f),
  ),
  Negative("NEGATIVE", mapOf(ColorControl.Invert to 1f)),
}

/**
 * The effects the playground applies to its clip. Every default leaves the frame as it was, and only the values that
 * differ from it reach the edit and the code listing.
 */
internal data class EffectSettings(
  val rotation: Int = 0,
  val flipHorizontal: Boolean = false,
  val flipVertical: Boolean = false,
  val crop: CropChoice = CropChoice.Off,
  val anchor: NamedAnchor = NamedAnchor.Center,
  val fit: Fit = Fit.Crop,
  val scaleHeight: Int? = null,
  val color: Map<ColorControl, Float> = emptyMap(),
  val kenBurns: Boolean = false,
  val move: KenBurnsMove = KenBurnsMove.PushIn,
) {
  /**
   * The value [control] is set to, its neutral value when it was never moved.
   */
  fun value(control: ColorControl): Float = color[control] ?: control.neutral

  /**
   * These settings with [control] at [value], dropping it from [color] when that is neutral.
   */
  fun withColor(
    control: ColorControl,
    value: Float,
  ): EffectSettings = copy(color = if (value == control.neutral) color - control else color + (control to value))

  /**
   * These settings with every colour value taken from [look].
   */
  fun withLook(look: Look): EffectSettings =
    copy(
      color =
        look.values.filter { (control, value) ->
          value !=
            control.neutral
        },
    )

  /**
   * Whether the colour values are exactly the ones [look] sets.
   */
  fun showsLook(look: Look): Boolean = ColorControl.entries.all { value(it) == (look.values[it] ?: it.neutral) }
}

/**
 * The edit [settings] describe over [source], one clip with an effect for each value that is not a default, in the
 * order the code listing shows them.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun effectsEdit(
  source: MediaSource,
  settings: EffectSettings,
): Composition =
  compositionOf {
    clip(source) {
      effects {
        if (settings.rotation != 0) rotate(settings.rotation)
        if (settings.flipHorizontal) flip(FlipAxis.Horizontal)
        if (settings.flipVertical) flip(FlipAxis.Vertical)
        settings.crop.aspect?.let { crop(it, fit = settings.fit, anchor = settings.anchor.anchor) }
        settings.scaleHeight?.let { scale(it) }
        ColorControl.entries.forEach { control ->
          val value = settings.color[control] ?: return@forEach
          when (control) {
            ColorControl.Brightness -> brightness(value)
            ColorControl.Contrast -> contrast(value)
            ColorControl.Saturation -> saturation(value)
            ColorControl.Hue -> hueRotate(value)
            ColorControl.Sepia -> sepia(value)
            ColorControl.Invert -> invert(value)
          }
        }
        if (settings.kenBurns) kenBurns(from = settings.move.from, to = settings.move.to)
      }
    }
  }

/**
 * Which stage of the plan an effect runs in, which also picks its colour in the chain strip.
 */
internal enum class Stage(
  val label: String,
) {
  Geometry("GEOMETRY"),
  Color("COLOUR"),
  Motion("PAN & ZOOM"),
}

/**
 * One effect in the chain strip, the settings that remove it and its label.
 */
internal data class ChainItem(
  val stage: Stage,
  val label: String,
  val without: EffectSettings,
)

/**
 * Every effect [settings] turn on, in the order the plan runs them.
 */
internal fun chainOf(settings: EffectSettings): List<ChainItem> =
  buildList {
    if (settings.rotation !=
      0
    ) {
      add(ChainItem(Stage.Geometry, "ROTATE ${settings.rotation}", settings.copy(rotation = 0)))
    }
    if (settings.flipHorizontal) add(ChainItem(Stage.Geometry, "FLIP H", settings.copy(flipHorizontal = false)))
    if (settings.flipVertical) add(ChainItem(Stage.Geometry, "FLIP V", settings.copy(flipVertical = false)))
    if (settings.crop != CropChoice.Off) {
      val reset = settings.copy(crop = CropChoice.Off, anchor = NamedAnchor.Center, fit = Fit.Crop)
      add(ChainItem(Stage.Geometry, "CROP ${settings.crop.label}", reset))
    }
    settings.scaleHeight?.let { add(ChainItem(Stage.Geometry, "SCALE $it", settings.copy(scaleHeight = null))) }
    ColorControl.entries.forEach { control ->
      val value = settings.color[control] ?: return@forEach
      val label = "${control.label.uppercase()} ${valueLabel(control, value)}"
      add(ChainItem(Stage.Color, label, settings.withColor(control, control.neutral)))
    }
    if (settings.kenBurns) {
      add(
        ChainItem(Stage.Motion, "KEN BURNS ${settings.move.label}", settings.copy(kenBurns = false)),
      )
    }
  }

/**
 * The aspect and height of what [settings] produce, such as `OUT 9:16 · 720 TALL`.
 */
internal fun outputLabel(settings: EffectSettings): String {
  val turned = settings.rotation % 180 != 0
  val aspect = settings.crop.aspect?.let { settings.crop.label } ?: if (turned) "9:16" else "16:9"
  val height = settings.scaleHeight?.let { "$it TALL" } ?: "SOURCE HEIGHT"
  return "OUT $aspect · $height"
}

/**
 * The Kotlin that builds the same effects as [settings], one list of parts per line, in the order [effectsEdit] adds
 * them.
 */
internal fun effectsCode(settings: EffectSettings): List<List<CodePart>> {
  fun call(
    name: String,
    value: String,
  ) = listOf(CodePart("    $name(", CodeKind.Call), CodePart(value, CodeKind.Value), CodePart(")", CodeKind.Call))

  val body =
    buildList {
      if (settings.rotation != 0) add(call("rotate", settings.rotation.toString()))
      if (settings.flipHorizontal) add(call("flip", "FlipAxis.Horizontal"))
      if (settings.flipVertical) add(call("flip", "FlipAxis.Vertical"))
      settings.crop.code?.let { aspect ->
        val fit = if (settings.fit != Fit.Crop) ", fit = Fit.${settings.fit.name}" else ""
        val anchor = if (settings.anchor != NamedAnchor.Center) ", anchor = Anchor.${settings.anchor.name}" else ""
        add(call("crop", aspect + fit + anchor))
      }
      settings.scaleHeight?.let { add(call("scale", it.toString())) }
      ColorControl.entries.forEach { control ->
        val value = settings.color[control] ?: return@forEach
        add(call(control.call, floatLiteral(value)))
      }
      if (settings.kenBurns) {
        add(listOf(CodePart("    kenBurns(", CodeKind.Call)))
        add(
          listOf(
            CodePart("      from = ", CodeKind.Call),
            CodePart(rectCode(settings.move.from), CodeKind.Value),
            CodePart(",", CodeKind.Call),
          ),
        )
        add(
          listOf(
            CodePart("      to = ", CodeKind.Call),
            CodePart(rectCode(settings.move.to), CodeKind.Value),
            CodePart(",", CodeKind.Call),
          ),
        )
        add(listOf(CodePart("    )", CodeKind.Call)))
      }
    }
  return buildList {
    add(listOf(CodePart("clip(beach) {", CodeKind.Frame)))
    add(listOf(CodePart("  effects {", CodeKind.Frame)))
    if (body.isEmpty()) add(listOf(CodePart("    // Pick an effect", CodeKind.Comment))) else addAll(body)
    add(listOf(CodePart("  }", CodeKind.Frame)))
    add(listOf(CodePart("}", CodeKind.Frame)))
  }
}

/**
 * How [value] of [control] reads beside its slider, degrees for hue and a plain number otherwise.
 */
internal fun valueLabel(
  control: ColorControl,
  value: Float,
): String = if (control == ColorControl.Hue) "${value.roundToInt()}°" else floatLiteral(value).removeSuffix("f")

private fun rectCode(rect: NormalizedRect): String =
  if (rect.left == 0f && rect.top == 0f && rect.right == 1f && rect.bottom == 1f) {
    "NormalizedRect.Full"
  } else {
    "NormalizedRect(${floatLiteral(rect.left)}, ${floatLiteral(rect.top)}, ${floatLiteral(rect.right)}, " +
      "${floatLiteral(rect.bottom)})"
  }

/**
 * The listing for [settings] as plain text.
 */
internal fun effectsCodeText(settings: EffectSettings): String = codeText(effectsCode(settings))
