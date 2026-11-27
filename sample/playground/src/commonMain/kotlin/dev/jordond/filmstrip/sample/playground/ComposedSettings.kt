package dev.jordond.filmstrip.sample.playground

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor

/**
 * The two elements the composed overlays sample lays over the clip.
 */
internal enum class ComposedElement(
  val label: String,
  val variable: String,
) {
  Caption("CAPTION", "caption"),
  Badge("REC BADGE", "badge"),
}

/**
 * What the composed overlays sample shows: the caption's text, where each element sits, which one the sliders move and
 * whether the clip plays the live elements or the pictures baked from them.
 */
internal data class ComposedSettings(
  val text: String = "Day one",
  val caption: OverlayTransform = DEFAULT_CAPTION,
  val badge: OverlayTransform = DEFAULT_BADGE,
  val selected: ComposedElement = ComposedElement.Caption,
  val baked: Boolean = false,
) {
  /**
   * The transform of [element].
   */
  fun transformOf(element: ComposedElement): OverlayTransform =
    when (element) {
      ComposedElement.Caption -> caption
      ComposedElement.Badge -> badge
    }

  /**
   * These settings with [element] moved to [transform].
   */
  fun with(
    element: ComposedElement,
    transform: OverlayTransform,
  ): ComposedSettings =
    when (element) {
      ComposedElement.Caption -> copy(caption = transform)
      ComposedElement.Badge -> copy(badge = transform)
    }

  internal companion object {
    const val MAX_TEXT = 40
    val DEFAULT_CAPTION = OverlayTransform(center = Anchor(0.5f, 0.22f))
    val DEFAULT_BADGE = OverlayTransform(center = Anchor(0.84f, 0.8f), rotationDegrees = -8f)
  }
}

/**
 * A summary of [settings] for the status line, naming the mouse and keyboard controls once a [mouse] has been seen
 * over the picture.
 */
internal fun composedSummary(
  settings: ComposedSettings,
  mouse: Boolean,
): String =
  when {
    settings.baked -> "BAKED INTO THE EDIT"
    mouse -> "LIVE · DRAG · WHEEL · KEYS"
    else -> "LIVE · DRAG PINCH TURN"
  }

/**
 * The Kotlin that places and bakes the elements of [settings].
 */
internal fun composedCode(settings: ComposedSettings): List<List<CodePart>> =
  buildList {
    ComposedElement.entries.forEach { element ->
      add(
        listOf(
          CodePart("var ${element.variable} by remember { mutableStateOf(", CodeKind.Frame),
          CodePart("OverlayTransform(", CodeKind.Call),
          CodePart(transformArguments(settings.transformOf(element)), CodeKind.Value),
          CodePart(")", CodeKind.Call),
          CodePart(") }", CodeKind.Frame),
        ),
      )
    }
    add(frame(""))
    add(frame("VideoStage(player, outputAspect) {"))
    ComposedElement.entries.forEach { element ->
      add(
        listOf(
          CodePart("  ComposedOverlay(", CodeKind.Call),
          CodePart(
            "${element.variable}, canvas, output, onTransformChange = { ${element.variable} = it }",
            CodeKind.Frame,
          ),
          CodePart(") { ", CodeKind.Call),
          CodePart(contentCode(element, settings), CodeKind.Value),
          CodePart(" }", CodeKind.Call),
        ),
      )
    }
    add(frame("}"))
    add(frame(""))
    add(listOf(CodePart("// On export, plan once for the frame and capture each element on it", CodeKind.Comment)))
    ComposedElement.entries.forEach { element ->
      add(
        listOf(
          CodePart("capturer.capture(", CodeKind.Call),
          CodePart("${element.variable}, canvas, plan.output.size", CodeKind.Frame),
          CodePart(") { ", CodeKind.Call),
          CodePart(contentCode(element, settings), CodeKind.Value),
          CodePart(" }", CodeKind.Call),
        ),
      )
    }
  }

private fun transformArguments(transform: OverlayTransform): String =
  buildList {
    add("Anchor(${floatLiteral(transform.center.x)}, ${floatLiteral(transform.center.y)})")
    if (roundToHundredths(transform.scale) != 1f) add("scale = ${floatLiteral(transform.scale)}")
    if (roundToHundredths(transform.rotationDegrees) != 0f) {
      add("rotationDegrees = ${floatLiteral(transform.rotationDegrees)}")
    }
  }.joinToString(", ")

private fun contentCode(
  element: ComposedElement,
  settings: ComposedSettings,
): String =
  when (element) {
    ComposedElement.Caption -> "Caption(\"${kotlinString(settings.text)}\")"
    ComposedElement.Badge -> "RecBadge()"
  }

private fun frame(text: String): List<CodePart> = listOf(CodePart(text, CodeKind.Frame))
