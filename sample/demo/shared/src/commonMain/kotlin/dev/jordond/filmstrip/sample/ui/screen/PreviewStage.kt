@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.drawscope.rotate
import androidx.compose.ui.graphics.drawscope.scale
import androidx.compose.ui.graphics.drawscope.translate
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.text.rememberTextMeasurer
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.Constraints
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.compose.ui.CropColors
import dev.jordond.filmstrip.compose.ui.CropOverlayDefaults
import dev.jordond.filmstrip.compose.ui.OverlayHandleColors
import dev.jordond.filmstrip.compose.ui.OverlayHandleDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.VideoStageScope
import dev.jordond.filmstrip.compose.ui.component.CropOverlay
import dev.jordond.filmstrip.compose.ui.component.OverlayHandle
import dev.jordond.filmstrip.compose.ui.interaction.CropConstraint
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayPlacement
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.EditorTool
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.asSeconds
import dev.jordond.filmstrip.sample.compositionFrameLabel
import dev.jordond.filmstrip.sample.outputAspect
import dev.jordond.filmstrip.sample.stageSentence
import dev.jordond.filmstrip.sample.ui.FringeText
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.asClock
import dev.jordond.filmstrip.sample.ui.insetVignette
import dev.jordond.filmstrip.sample.ui.isCompactWidth
import dev.jordond.filmstrip.sample.ui.scanlinesFaint
import dev.jordond.filmstrip.sample.ui.timecode
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import dev.jordond.filmstrip.transform.internal.geometry.placeIn
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import dev.jordond.filmstrip.geometry.Size as FrameSize

/**
 * The editor's viewport.
 *
 * It plays the edit through the player `preview` hands back, letterboxed to the output aspect rather than the
 * source's, which is what a surface has to do once a crop is attached. Before a player is open, and where none could
 * be built, it falls back to a schematic of the same edit so the rest of the editor stays usable.
 *
 * The picture sits on a dark floor with its corners square and nothing over it except the edit and the crop and
 * overlay handles. Status and timecode print above it, frame size and any note print below it, and viewfinder
 * brackets stand off its corners.
 *
 * The player itself is started and stopped above this, so folding or unfolding a host that swaps this out for a
 * differently laid out copy of itself never tears the player down.
 */
@Composable
public fun PreviewStage(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val edit = state.edit
  val compact = isCompactWidth()

  Column(
    modifier =
      modifier
        .background(MaterialTheme.colorScheme.surfaceContainerLowest)
        .insetVignette(if (compact) 37.dp else 74.dp)
        .scanlinesFaint()
        .padding(
          start = if (compact) 16.dp else 24.dp,
          end = if (compact) 16.dp else 24.dp,
          top = if (compact) 8.dp else 12.dp,
          bottom = if (compact) 8.dp else 12.dp,
        ),
    verticalArrangement = Arrangement.spacedBy(if (compact) 4.dp else 8.dp),
  ) {
    OsdStatus(state, compact)
    Box(Modifier.fillMaxWidth().weight(1f), contentAlignment = Alignment.Center) {
      VideoStage(
        // Every platform ships a preview backend, so a build with none is one that never registered
        // it, and the schematic stands in for the picture.
        player = state.preview.player?.takeIf { !state.preview.previewUnavailable },
        outputAspect = edit.outputAspect(state.sourceAspect, cropped = !state.croppingRect),
        modifier =
          Modifier
            .padding(BracketReach)
            .viewfinderBrackets()
            .background(Color.Black),
        fallback = { StageSchematic(state, Modifier.fillMaxSize()) },
      ) {
        val panEnd = state.panEnd
        if (panEnd != null) {
          PanOverlay(state, panEnd)
        } else if (state.croppingRect) {
          CropOverlay(
            rect = edit.cropRect,
            onRectChange = { rect ->
              edit.setCropRect(rect)
              state.onEditChanged()
            },
            constraint =
              edit.cropLockAspect
                ?.let { CropConstraint.lockedTo(it, aspect) }
                ?: CropOverlayDefaults.Constraint,
            colors = sampleCropColors(),
          )
        } else {
          // Both tools drag against the whole stage, so the crop rectangle and the overlay handles
          // take turns rather than sharing a frame's worth of gestures.
          OverlayHandles(state)
        }
      }
    }
    StageFloorNotes(state)
  }
}

/**
 * The line of machine text above the picture, the status on the left, the tool's step in the middle and the timecode
 * on the right.
 */
@Composable
private fun OsdStatus(
  state: SampleAppState,
  compact: Boolean,
) {
  val style =
    MaterialTheme.typography.labelLarge.copy(
      fontSize = if (compact) 20.sp else 24.sp,
      lineHeight = if (compact) 22.sp else 26.sp,
    )
  val colors = MaterialTheme.colorScheme
  val live = state.preview.player != null && !state.preview.previewUnavailable
  val (word, wordColor) =
    when {
      live && state.preview.playerStatus is PlaybackStatus.Error -> "ERROR" to colors.error
      live && state.preview.playing -> "PLAY" to colors.primary
      live -> state.preview.playerStatus.statusWord() to colors.primary
      state.sourceFailure != null -> "UNREADABLE" to colors.error
      else -> "SCHEMATIC" to colors.secondary
    }
  val fps = state.info?.video?.frameRate
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    FringeText(word, style, wordColor, maxLines = 1)
    FringeText(
      text = state.stepLabel(compact),
      style = style,
      color = colors.onSurface,
      maxLines = 1,
      overflow = TextOverflow.Ellipsis,
      contentAlignment = Alignment.Center,
      modifier = Modifier.weight(1f),
    )
    FringeText(timecode(state.preview.positionSeconds.toDouble(), fps), style, colors.primary, maxLines = 1)
  }
}

/**
 * What the selected tool is doing, printed like a deck's step. A phone keeps it short so the status and timecode fit.
 */
private fun SampleAppState.stepLabel(compact: Boolean): String {
  val edit = edit
  return when (activeTool) {
    EditorTool.Clip -> {
      val clip = sequence.selectedClip
      if (compact || clip == null || !clip.trimEnabled) {
        "CLIP"
      } else {
        val start =
          clip.trimStartSeconds
            .asSeconds()
            .asClock()
        val end =
          clip.trimEndSeconds
            .asSeconds()
            .asClock()
        "TRIM  $start TO $end"
      }
    }
    EditorTool.Crop -> {
      "REFRAME"
    }
    EditorTool.Transform -> {
      "ROTATE"
    }
    EditorTool.Scale -> {
      "SCALE"
    }
    EditorTool.Adjust -> {
      if (compact) "COLOUR GRADE" else "EFFECT  COLOUR GRADE"
    }
    EditorTool.Text -> {
      if (compact) "TEXT" else "OVERLAY  TEXT"
    }
    EditorTool.Watermark -> {
      if (compact) "IMAGE" else "OVERLAY  IMAGE"
    }
    EditorTool.Audio -> {
      "AUDIO"
    }
    EditorTool.Tracks -> {
      "TRACKS"
    }
    EditorTool.Background -> {
      "FRAME"
    }
  }
}

/**
 * The frame size and any note about a stopped preview, on the floor under the picture rather than over it.
 */
@Composable
private fun StageFloorNotes(state: SampleAppState) {
  val frame = state.compositionFrameLabel()
  val note = state.previewNote()
  if (frame == null && note == null) return

  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.spacedBy(12.dp, Alignment.CenterHorizontally),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    if (note != null) {
      Text(
        text = note,
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier.weight(1f, fill = false),
      )
    }
    if (frame != null) {
      Text(frame, style = MaterialTheme.typography.labelSmall, color = Tokens.coral, maxLines = 1)
    }
  }
}

/**
 * Draws the camera corners and a thin outline outside the element, leaving the element itself untouched.
 */
private fun Modifier.viewfinderBrackets(): Modifier =
  drawBehind {
    val reach = BracketReach.toPx()
    val length = BracketLength.toPx()
    val width = BracketWidth.toPx()
    val outline = BracketWidth.toPx()
    drawRect(
      color = Tokens.line,
      topLeft = Offset(-outline / 2f, -outline / 2f),
      size = Size(size.width + outline, size.height + outline),
      style = Stroke(outline),
    )
    val left = -reach
    val top = -reach
    val right = size.width + reach
    val bottom = size.height + reach

    fun corner(
      x: Float,
      y: Float,
      xDirection: Float,
      yDirection: Float,
    ) {
      drawRect(
        color = Tokens.trackTick,
        topLeft = Offset(if (xDirection > 0) x else x - length, if (yDirection > 0) y else y - width),
        size = Size(length, width),
      )
      drawRect(
        color = Tokens.trackTick,
        topLeft = Offset(if (xDirection > 0) x else x - width, if (yDirection > 0) y else y - length),
        size = Size(width, length),
      )
    }
    corner(left, top, 1f, 1f)
    corner(right, top, -1f, 1f)
    corner(left, bottom, 1f, -1f)
    corner(right, bottom, -1f, -1f)
  }

/**
 * The placement handles for the composited overlays, drawn over whatever the stage is showing.
 *
 * A live preview has the text and the watermark burnt in by the effect chain already, so the handle
 * sits on top of the picture the viewer is looking at and a drag moves that. The schematic draws
 * its own stand-ins underneath, and the handles drive both.
 */
@Composable
private fun VideoStageScope.OverlayHandles(state: SampleAppState) {
  val edit = state.edit
  val stage = frame.contentRect
  val output = FrameSize(stage.width.roundToInt(), stage.height.roundToInt())
  if (output.width <= 0 || output.height <= 0) return

  val colors = sampleHandleColors()

  if (edit.textEnabled && edit.text.isNotBlank()) {
    OverlayHandle(
      placement = textPlacement(edit, output),
      onFrameAnchorChange = { anchor ->
        edit.setTextAnchor(anchor)
        state.onEditChanged()
      },
      output = output,
      colors = colors,
    )
  }

  edit.watermarkImage?.let { image ->
    OverlayHandle(
      placement =
        remember(edit.watermarkCorner, edit.watermarkMargin, edit.watermarkScale, output) {
          ImageOverlay(
            image = image,
            corner = edit.watermarkCorner,
            margin = edit.watermarkMargin,
            scale = edit.watermarkScale,
          ).placedOn(output, WATERMARK_IMAGE)
        },
      onFrameAnchorChange = { anchor ->
        edit.setWatermarkAnchor(anchor, output)
        state.onEditChanged()
      },
      output = output,
      colors = colors,
    )
  }
}

/**
 * Where the text block lands, measured with the same font size and wrapping width the schematic
 * draws it at.
 */
@Composable
private fun textPlacement(
  edit: EditState,
  output: FrameSize,
): OverlayPlacement {
  val measurer = rememberTextMeasurer()
  val fontSize = with(LocalDensity.current) { (output.height * edit.textSize / CAP_HEIGHT_RATIO).toSp() }
  val block =
    measurer.measure(
      text = edit.text,
      style =
        ComposeTextStyle(
          fontSize = fontSize,
          lineHeight = fontSize * 1.15f,
          fontWeight =
            when (edit.textWeight) {
              FontWeight.Regular -> ComposeFontWeight.Normal
              FontWeight.Medium -> ComposeFontWeight.Medium
              FontWeight.Bold -> ComposeFontWeight.Bold
            },
        ),
      constraints = Constraints(maxWidth = (output.width * edit.textMaxWidth).roundToInt().coerceAtLeast(1)),
    )

  return TextOverlay(
    text = edit.text,
    style = edit.textStyle,
    anchor = edit.textAnchor,
  ).placedOn(FrameSize(block.size.width, block.size.height))
}

/**
 * The crop overlay's palette, taken from the app's theme.
 */
@Composable
internal fun sampleCropColors(): CropColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    CropOverlayDefaults.colors(
      scrim = scheme.scrim.copy(alpha = 0.72f),
      outline = scheme.onSurface,
      handle = scheme.primary,
      grid = scheme.onSurface.copy(alpha = 0.5f),
    )
  }
}

/**
 * The overlay handles' palette, taken from the app's theme.
 */
@Composable
private fun sampleHandleColors(): OverlayHandleColors {
  val scheme = MaterialTheme.colorScheme
  return remember(scheme) {
    OverlayHandleDefaults.colors(
      fill = scheme.primary.copy(alpha = 0.12f),
      outline = scheme.primary.copy(alpha = 0.8f),
      handle = scheme.primary,
    )
  }
}

private fun SampleAppState.previewNote(): String? = sourceFailure ?: preview.previewError?.stageSentence()

private fun PlaybackStatus.statusWord(): String =
  when (this) {
    PlaybackStatus.Idle -> "IDLE"
    PlaybackStatus.Preparing -> "LOADING"
    PlaybackStatus.Ready -> "PREVIEW"
    PlaybackStatus.Ended -> "ENDED"
    PlaybackStatus.Released -> "RELEASED"
    is PlaybackStatus.Error -> "ERROR"
  }

private val BracketReach = 8.dp
private val BracketLength = 18.dp
private val BracketWidth = 2.dp

internal const val CAP_HEIGHT_RATIO = 0.7f
internal const val WATERMARK_ASPECT = 0.6f

/**
 * The stand-in watermark's own pixel size, whose aspect is what the handle's box follows.
 */
private val WATERMARK_IMAGE = FrameSize(100, (100 * WATERMARK_ASPECT).roundToInt())
