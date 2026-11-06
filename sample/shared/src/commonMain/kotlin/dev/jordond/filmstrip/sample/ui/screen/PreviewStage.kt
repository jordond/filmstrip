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
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.EditorTool
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.compositionFrameLabel
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
import kotlin.math.max
import kotlin.math.min
import kotlin.math.roundToInt
import kotlin.time.Duration.Companion.seconds
import androidx.compose.ui.text.TextStyle as ComposeTextStyle
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight
import dev.jordond.filmstrip.geometry.Size as FrameSize
import dev.jordond.filmstrip.transform.internal.geometry.placeIn

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
    modifier = modifier
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
        player = state.player?.takeIf { !state.previewUnavailable },
        outputAspect = edit.outputAspect(state.sourceAspect, cropped = !state.croppingRect),
        modifier = Modifier
          .padding(BracketReach)
          .viewfinderBrackets()
          .background(Color.Black),
        fallback = { StageSchematic(state, Modifier.fillMaxSize()) },
      ) {
        if (state.croppingRect) {
          CropOverlay(
            rect = edit.cropRect,
            onRectChange = { rect ->
              edit.setCropRect(rect)
              state.onEditChanged()
            },
            constraint = edit.cropLockAspect
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
  val style = MaterialTheme.typography.labelLarge.copy(
    fontSize = if (compact) 20.sp else 24.sp,
    lineHeight = if (compact) 22.sp else 26.sp,
  )
  val colors = MaterialTheme.colorScheme
  val live = state.player != null && !state.previewUnavailable
  val (word, wordColor) = when {
    live && state.playerStatus is PlaybackStatus.Error -> "ERROR" to colors.error
    live && state.playing -> "PLAY" to colors.primary
    live -> state.playerStatus.statusWord() to colors.primary
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
    FringeText(timecode(state.positionSeconds.toDouble(), fps), style, colors.primary, maxLines = 1)
  }
}

/**
 * What the selected tool is doing, printed like a deck's step. A phone keeps it short so the status and timecode fit.
 */
private fun SampleAppState.stepLabel(compact: Boolean): String {
  val edit = edit
  return when (activeTool) {
    EditorTool.Trim ->
      if (compact || !edit.trimEnabled) {
        "TRIM"
      } else {
        val start = edit.trimStartSeconds.toDouble().seconds.asClock()
        val end = edit.trimEndSeconds.toDouble().seconds.asClock()
        "TRIM  $start TO $end"
      }
    EditorTool.Crop -> "REFRAME"
    EditorTool.Transform -> "ROTATE"
    EditorTool.Scale -> "SCALE"
    EditorTool.Adjust -> if (compact) "COLOUR GRADE" else "EFFECT  COLOUR GRADE"
    EditorTool.Text -> if (compact) "TEXT" else "OVERLAY  TEXT"
    EditorTool.Watermark -> if (compact) "IMAGE" else "OVERLAY  IMAGE"
    EditorTool.Audio -> "AUDIO"
    EditorTool.Background -> "FILL"
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
    fun corner(x: Float, y: Float, xDirection: Float, yDirection: Float) {
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
 * What the stage draws where there is no picture to draw: the same edit, laid out from its
 * parameters rather than rendered.
 */
@Composable
private fun StageSchematic(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val edit = state.edit

  BoxWithConstraints(modifier) {
    val frameWidth = maxWidth
    val frameHeight = maxHeight
    val shorterSide = min(frameWidth.value, frameHeight.value).dp

    // Folded once per grade rather than once per recomposition, since dragging a slider anywhere
    // else on the screen recomposes this.
    val graded = remember(edit.colorEffects) { Modifier.fillMaxSize().colorGraded(edit.colorEffects) }

    Box(graded) {
      FrameBackground(edit, shorterSide, state.sourceAspect)
      FrameContent(edit, frameWidth, frameHeight, state.sourceAspect, applyRectCrop = !state.croppingRect)
    }
    CaptionOverlay(edit, frameWidth, frameHeight)
    WatermarkOverlay(edit, frameWidth, shorterSide)
  }
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
      placement = remember(edit.watermarkCorner, edit.watermarkMargin, edit.watermarkScale, output) {
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
  val block = measurer.measure(
    text = edit.text,
    style = ComposeTextStyle(
      fontSize = fontSize,
      lineHeight = fontSize * 1.15f,
      fontWeight = when (edit.textWeight) {
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
private fun sampleCropColors(): CropColors {
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

@Composable
private fun FrameBackground(
  edit: EditState,
  shorterSide: Dp,
  sourceAspect: Float,
) {
  when (edit.fillMode) {
    FillMode.Solid -> Box(Modifier.fillMaxSize().background(Color(edit.fillColor)))
    FillMode.Blurred -> {
      Box(Modifier.fillMaxSize().background(Color.Black)) {
        SyntheticFrame(
          rotationDegrees = edit.rotationDegrees,
          flipHorizontal = edit.flipHorizontal,
          flipVertical = edit.flipVertical,
          sourceAspect = sourceAspect,
          modifier = Modifier.fillMaxSize().blur(shorterSide * edit.blurRadius * BLUR_GAIN),
        )
        Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = edit.blurDim)))
      }
    }
  }
}

@Composable
private fun FrameContent(
  edit: EditState,
  frameWidth: Dp,
  frameHeight: Dp,
  sourceAspect: Float,
  applyRectCrop: Boolean = true,
) {
  val rotated = if (edit.rotationDegrees == 90 || edit.rotationDegrees == 270) 1f / sourceAspect else sourceAspect
  val contentWidth: Dp
  val contentHeight: Dp
  var offsetX = 0.dp
  var offsetY = 0.dp

  // Offsets are measured from the centre, because that is where a box places a child larger than
  // itself no matter what alignment it was given.
  when {
    edit.cropMode == CropMode.Rect && edit.cropRect.isValid && applyRectCrop -> {
      val rect = edit.cropRect
      contentWidth = frameWidth / rect.width
      contentHeight = frameHeight / rect.height
      offsetX = -contentWidth * rect.left - (frameWidth - contentWidth) / 2f
      offsetY = -contentHeight * rect.top - (frameHeight - contentHeight) / 2f
    }

    // The stage is the crop's canvas, so the frame sits where the library's own placement puts it in there.
    edit.cropMode == CropMode.Aspect && edit.cropFit != Fit.Crop -> {
      val placed =
        placeIn(
          FrameSize(PLACEMENT_SCALE, (PLACEMENT_SCALE / rotated).roundToInt()),
          FrameSize(
            (frameWidth.value * PLACEMENT_SCALE).roundToInt(),
            (frameHeight.value * PLACEMENT_SCALE).roundToInt(),
          ),
          edit.cropFit,
        )
      contentWidth = frameWidth * placed.width
      contentHeight = frameHeight * placed.height
    }

    else -> {
      if (frameWidth / frameHeight > rotated) {
        contentWidth = frameWidth
        contentHeight = frameWidth / rotated
      } else {
        contentHeight = frameHeight
        contentWidth = frameHeight * rotated
      }
      offsetX = (frameWidth - contentWidth) * (edit.cropAnchorX - 0.5f)
      offsetY = (frameHeight - contentHeight) * (edit.cropAnchorY - 0.5f)
    }
  }

  Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
    SyntheticFrame(
      rotationDegrees = edit.rotationDegrees,
      flipHorizontal = edit.flipHorizontal,
      flipVertical = edit.flipVertical,
      sourceAspect = sourceAspect,
      modifier = Modifier
        .offset(x = offsetX, y = offsetY)
        .requiredSize(contentWidth, contentHeight),
    )
  }
}

/**
 * A stand-in frame with enough asymmetry that a rotation, a flip and a crop are all legible.
 */
@Composable
private fun SyntheticFrame(
  rotationDegrees: Int,
  flipHorizontal: Boolean,
  flipVertical: Boolean,
  sourceAspect: Float,
  modifier: Modifier = Modifier,
) {
  Canvas(modifier) {
    val quarterTurn = rotationDegrees == 90 || rotationDegrees == 270
    val contentWidth = if (quarterTurn) size.height else size.width
    val contentHeight = if (quarterTurn) size.width else size.height

    rotate(degrees = -rotationDegrees.toFloat(), pivot = center) {
      scale(
        scaleX = if (flipHorizontal) -1f else 1f,
        scaleY = if (flipVertical) -1f else 1f,
        pivot = center,
      ) {
        translate(left = (size.width - contentWidth) / 2f, top = (size.height - contentHeight) / 2f) {
          drawTestCard(contentWidth, contentHeight, sourceAspect)
        }
      }
    }
  }
}

private fun DrawScope.drawTestCard(
  width: Float,
  height: Float,
  sourceAspect: Float,
) {
  val frame = Size(width, height)
  val unit = min(width, height)

  drawRect(
    brush = Brush.linearGradient(
      colors = listOf(Tokens.sceneSkyTop, Tokens.sceneSkyMiddle, Tokens.sceneSkyLow),
      start = Offset.Zero,
      end = Offset(width, height),
    ),
    size = frame,
  )

  val gridColor = Tokens.white.copy(alpha = 0.10f)
  for (i in 1..2) {
    drawLine(gridColor, Offset(width * i / 3f, 0f), Offset(width * i / 3f, height), strokeWidth = 1f)
    drawLine(gridColor, Offset(0f, height * i / 3f), Offset(width, height * i / 3f), strokeWidth = 1f)
  }

  // A corner block and an up arrow, so a rotation and a flip both change something obvious.
  drawRect(
    color = Tokens.amber,
    topLeft = Offset(unit * 0.06f, unit * 0.06f),
    size = Size(unit * 0.14f, unit * 0.14f),
  )

  val arrowHeight = unit * 0.18f
  val arrowTop = height * 0.16f
  val arrowCenter = width * 0.5f
  val arrow = androidx.compose.ui.graphics.Path().apply {
    moveTo(arrowCenter, arrowTop)
    lineTo(arrowCenter + arrowHeight * 0.6f, arrowTop + arrowHeight)
    lineTo(arrowCenter - arrowHeight * 0.6f, arrowTop + arrowHeight)
    close()
  }
  drawPath(arrow, Tokens.white.copy(alpha = 0.9f))

  drawCircle(
    color = Tokens.cyan,
    radius = unit * 0.13f,
    center = Offset(width * 0.36f, height * 0.55f),
  )
  drawCircle(
    color = Tokens.white.copy(alpha = 0.35f),
    radius = unit * 0.2f,
    center = Offset(width * 0.5f, height * 0.5f),
    style = Stroke(width = max(1f, unit * 0.008f)),
  )
  drawRect(
    color = Color.Black.copy(alpha = 0.35f),
    topLeft = Offset(0f, height * 0.82f),
    size = Size(width, height * 0.18f),
  )
  drawRect(
    color = Tokens.pink.copy(alpha = 0.9f),
    topLeft = Offset(width * 0.08f, height * 0.87f),
    size = Size(width * 0.3f * sourceAspect.coerceIn(0.5f, 2f), height * 0.04f),
  )
}

@Composable
private fun CaptionOverlay(
  edit: EditState,
  frameWidth: Dp,
  frameHeight: Dp,
) {
  if (!edit.textEnabled || edit.text.isBlank()) return

  val density = LocalDensity.current
  val fontSize = with(density) { (frameHeight * edit.textSize / CAP_HEIGHT_RATIO).toSp() }
  val plate = edit.textPlate

  Box(Modifier.fillMaxSize().padding(frameHeight * 0.03f)) {
    Text(
      text = edit.text,
      color = Color(edit.textColor),
      fontSize = fontSize,
      lineHeight = fontSize * 1.15f,
      fontWeight = when (edit.textWeight) {
        FontWeight.Regular -> ComposeFontWeight.Normal
        FontWeight.Medium -> ComposeFontWeight.Medium
        FontWeight.Bold -> ComposeFontWeight.Bold
      },
      textAlign = when (edit.textAlignment) {
        TextAlignment.Start -> TextAlign.Start
        TextAlignment.Center -> TextAlign.Center
        TextAlignment.End -> TextAlign.End
      },
      modifier = Modifier
        .align(BiasAlignment(edit.textAnchorX * 2f - 1f, edit.textAnchorY * 2f - 1f))
        .widthIn(max = frameWidth * edit.textMaxWidth)
        .then(if (plate != null) Modifier.background(Color(plate)) else Modifier)
        .padding(horizontal = if (plate != null) 6.dp else 0.dp, vertical = if (plate != null) 2.dp else 0.dp),
    )
  }
}

@Composable
private fun WatermarkOverlay(
  edit: EditState,
  frameWidth: Dp,
  shorterSide: Dp,
) {
  if (edit.watermarkImage == null) return

  val width = frameWidth * edit.watermarkScale
  val alignment = when (edit.watermarkCorner) {
    Corner.TopStart -> BiasAlignment(-1f, -1f)
    Corner.TopEnd -> BiasAlignment(1f, -1f)
    Corner.BottomStart -> BiasAlignment(-1f, 1f)
    Corner.BottomEnd -> BiasAlignment(1f, 1f)
  }

  Box(Modifier.fillMaxSize().padding(shorterSide * edit.watermarkMargin)) {
    Box(
      modifier = Modifier
        .align(alignment)
        .size(width, width * WATERMARK_ASPECT)
        .background(Tokens.white.copy(alpha = 0.14f * edit.watermarkOpacity))
        .border(1.dp, Tokens.white.copy(alpha = 0.7f * edit.watermarkOpacity)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "IMG",
        color = Tokens.white.copy(alpha = 0.85f * edit.watermarkOpacity),
        fontSize = with(LocalDensity.current) { (width * 0.22f).toSp() },
        fontWeight = ComposeFontWeight.Bold,
      )
    }
  }
}

/**
 * What the stage says about a preview that is not playing, or null while it is.
 */
private fun SampleAppState.previewNote(): String? =
  sourceFailure ?: previewError?.stageSentence()

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

private const val BLUR_GAIN = 6f
private const val CAP_HEIGHT_RATIO = 0.7f
private const val WATERMARK_ASPECT = 0.6f

// What a stage dimension is multiplied by before it is handed to the placement, which measures whole pixels.
private const val PLACEMENT_SCALE = 1_000

/**
 * The stand-in watermark's own pixel size, whose aspect is what the handle's box follows.
 */
private val WATERMARK_IMAGE = FrameSize(100, (100 * WATERMARK_ASPECT).roundToInt())
