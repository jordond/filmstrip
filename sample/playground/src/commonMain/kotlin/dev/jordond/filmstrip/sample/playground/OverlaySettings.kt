package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.imageOverlay
import dev.jordond.filmstrip.effects.overlay.plus
import dev.jordond.filmstrip.effects.overlay.slideIn
import dev.jordond.filmstrip.effects.overlay.slideOut
import dev.jordond.filmstrip.effects.overlay.textOverlay
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextStyle
import kotlin.math.PI
import kotlin.math.sin
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * How the overlays sample moves both of its overlays.
 */
internal enum class OverlayMotion(
  val label: String,
) {
  None("NONE"),
  Fade("FADE"),
  Slide("SLIDE"),
  Pulse("PULSE"),
}

/**
 * When the overlays sample draws its overlays, the whole clip or a window inside it.
 */
internal enum class OverlayWindow(
  val label: String,
) {
  Always("ALWAYS"),
  Timed("1S TO 9S"),
}

/**
 * What the overlays sample draws: a caption, a logo in a corner, how both move and when they show.
 */
internal data class OverlaySettings(
  val caption: Boolean = true,
  val text: String = "Day one",
  val anchor: NamedAnchor = NamedAnchor.BottomCenter,
  val fontSize: Float = DEFAULT_FONT_SIZE,
  val weight: FontWeight = FontWeight.Bold,
  val plate: Boolean = true,
  val logo: Boolean = true,
  val corner: Corner = Corner.TopEnd,
  val logoScale: Float = DEFAULT_LOGO_SCALE,
  val logoOpacity: Float = DEFAULT_LOGO_OPACITY,
  val motion: OverlayMotion = OverlayMotion.Fade,
  val window: OverlayWindow = OverlayWindow.Timed,
) {
  companion object {
    val DEFAULT_FONT_SIZE: Float = TextStyle.Default.fontSize
    const val DEFAULT_LOGO_SCALE: Float = 0.1f
    const val DEFAULT_LOGO_OPACITY: Float = 0.8f
    const val PLATE: Int = 0x80000000.toInt()
    const val MAX_TEXT: Int = 48
  }
}

private val Ramp = 500.milliseconds
private val Drop = OverlayOffset(0f, 0.1f)
private val Timed = 1.seconds..9.seconds

/**
 * Grows an overlay by up to 8% and back once a second. It's one instance so every rebuilt composition compares equal.
 */
internal val pulse: OverlayAnimation =
  OverlayAnimation { time ->
    val beat = (time.elapsed.inWholeMilliseconds % 1000) / 1000f
    OverlayFrame(scale = 1f + 0.08f * sin(beat * PI.toFloat()))
  }

private val fadeBoth: OverlayAnimation = fade(Ramp, Ramp)
private val slideBoth: OverlayAnimation = fadeBoth + slideIn(Drop, Ramp) + slideOut(Drop, Ramp)

/**
 * The animation [motion] names, or null for none.
 */
internal fun animationOf(motion: OverlayMotion): OverlayAnimation? =
  when (motion) {
    OverlayMotion.None -> null
    OverlayMotion.Fade -> fadeBoth
    OverlayMotion.Slide -> slideBoth
    OverlayMotion.Pulse -> pulse
  }

/**
 * The edit [settings] describe over [source], with [logo] as the picture in the corner.
 */
internal fun overlaysEdit(
  source: MediaSource,
  logo: ImageSource,
  settings: OverlaySettings,
): Composition =
  compositionOf {
    clip(source)
    effects {
      val window = if (settings.window == OverlayWindow.Timed) TimeRange(Timed) else null
      val animation = animationOf(settings.motion)
      if (settings.caption) {
        textOverlay(
          text = settings.text,
          style = styleOf(settings),
          anchor = settings.anchor.anchor,
          visibleDuring = window,
          animation = animation,
        )
      }
      if (settings.logo) {
        imageOverlay(
          image = logo,
          corner = settings.corner,
          scale = settings.logoScale,
          opacity = settings.logoOpacity,
          visibleDuring = window,
          animation = animation,
        )
      }
    }
  }

private fun styleOf(settings: OverlaySettings): TextStyle =
  TextStyle(
    fontSize = settings.fontSize,
    weight = settings.weight,
    backgroundColor = if (settings.plate) OverlaySettings.PLATE else null,
  )

/**
 * The Kotlin that builds the same overlays as [settings], naming only the arguments that differ from their defaults.
 */
internal fun overlaysCode(settings: OverlaySettings): List<List<CodePart>> {
  val animated = settings.motion != OverlayMotion.None
  val at = if (settings.window == OverlayWindow.Timed) "1.seconds..9.seconds" else null
  return buildList {
    if (settings.logo) add(frame("val logo = ImageSource.ofUri(\"logo.png\")"))
    addAll(animationCode(settings.motion))
    add(frame("clip(tearsOfSteel)"))
    add(frame("effects {"))
    if (!settings.caption && !settings.logo) add(listOf(CodePart("  // Turn on a caption or a logo", CodeKind.Comment)))
    if (settings.caption) {
      add(call("  textOverlay("))
      add(argument("text", "\"${kotlinString(settings.text)}\""))
      at?.let { add(argument("at", it)) }
      textStyleCode(settings)?.let { add(argument("style", it)) }
      if (settings.anchor != NamedAnchor.BottomCenter) add(argument("anchor", "Anchor.${settings.anchor.name}"))
      if (animated) add(argument("animation", "animation"))
      add(call("  )"))
    }
    if (settings.logo) {
      add(call("  imageOverlay("))
      add(argument("image", "logo"))
      add(argument("corner", "Corner.${settings.corner.name}"))
      at?.let { add(argument("at", it)) }
      if (settings.logoScale != ImageOverlay.DEFAULT_SCALE) add(argument("scale", floatLiteral(settings.logoScale)))
      if (settings.logoOpacity != 1f) add(argument("opacity", floatLiteral(settings.logoOpacity)))
      if (animated) add(argument("animation", "animation"))
      add(call("  )"))
    }
    add(frame("}"))
  }
}

private fun textStyleCode(settings: OverlaySettings): String? {
  val arguments =
    buildList {
      if (settings.fontSize != OverlaySettings.DEFAULT_FONT_SIZE) add("fontSize = ${floatLiteral(settings.fontSize)}")
      if (settings.weight != FontWeight.Regular) add("weight = FontWeight.${settings.weight.name}")
      if (settings.plate) add("backgroundColor = 0x80000000.toInt()")
    }
  return if (arguments.isEmpty()) null else "TextStyle(${arguments.joinToString(", ")})"
}

private fun animationCode(motion: OverlayMotion): List<List<CodePart>> =
  when (motion) {
    OverlayMotion.None -> {
      emptyList()
    }
    OverlayMotion.Fade -> {
      listOf(
        listOf(
          CodePart("val animation = ", CodeKind.Frame),
          CodePart("fade(", CodeKind.Call),
          CodePart("500.milliseconds, 500.milliseconds", CodeKind.Value),
          CodePart(")", CodeKind.Call),
        ),
      )
    }
    OverlayMotion.Slide -> {
      listOf(
        frame("val animation ="),
        listOf(
          CodePart("  fade(", CodeKind.Call),
          CodePart("500.milliseconds, 500.milliseconds", CodeKind.Value),
          CodePart(") +", CodeKind.Call),
        ),
        listOf(
          CodePart("    slideIn(", CodeKind.Call),
          CodePart("OverlayOffset(0f, 0.1f), 500.milliseconds", CodeKind.Value),
          CodePart(") +", CodeKind.Call),
        ),
        listOf(
          CodePart("    slideOut(", CodeKind.Call),
          CodePart("OverlayOffset(0f, 0.1f), 500.milliseconds", CodeKind.Value),
          CodePart(")", CodeKind.Call),
        ),
      )
    }
    OverlayMotion.Pulse -> {
      listOf(
        frame("val animation ="),
        call("  OverlayAnimation { time ->"),
        frame("    val beat = (time.elapsed.inWholeMilliseconds % 1000) / 1000f"),
        listOf(
          CodePart("    OverlayFrame(scale = ", CodeKind.Call),
          CodePart("1f + 0.08f * sin(beat * PI.toFloat())", CodeKind.Value),
          CodePart(")", CodeKind.Call),
        ),
        call("  }"),
      )
    }
  }

private fun frame(text: String): List<CodePart> = listOf(CodePart(text, CodeKind.Frame))

private fun call(text: String): List<CodePart> = listOf(CodePart(text, CodeKind.Call))

private fun argument(
  name: String,
  value: String,
): List<CodePart> =
  listOf(CodePart("    $name = ", CodeKind.Call), CodePart(value, CodeKind.Value), CodePart(",", CodeKind.Call))

/**
 * [text] escaped to sit inside a Kotlin string literal.
 */
internal fun kotlinString(text: String): String =
  text
    .replace("\\", "\\\\")
    .replace("\"", "\\\"")
    .replace("$", "\\$")
    .replace("\n", "\\n")

/**
 * What the status line says about [settings], such as `CAPTION · LOGO · FADE`.
 */
internal fun overlaySummary(settings: OverlaySettings): String {
  val parts =
    buildList {
      if (settings.caption) add("CAPTION")
      if (settings.logo) add("LOGO")
      if (isEmpty()) return "NO OVERLAYS"
      if (settings.motion != OverlayMotion.None) add(settings.motion.label)
    }
  return parts.joinToString(" · ")
}
