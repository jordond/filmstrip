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
 * What the stage draws where there is no picture to draw: the same edit, laid out from its
 * parameters rather than rendered.
 */
@Composable
internal fun StageSchematic(
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
    val graded = remember(edit.grade.effects) { Modifier.fillMaxSize().colorGraded(edit.grade.effects) }

    Box(graded) {
      FrameBackground(edit, shorterSide, state.sourceAspect)
      FrameContent(edit, frameWidth, frameHeight, state.sourceAspect, applyRectCrop = !state.croppingRect)
    }
    CaptionOverlay(edit, frameWidth, frameHeight)
    WatermarkOverlay(edit, frameWidth, shorterSide)
  }
}

@Composable
private fun FrameBackground(
  edit: EditState,
  shorterSide: Dp,
  sourceAspect: Float,
) {
  when (edit.fillMode) {
    FillMode.Solid -> {
      Box(Modifier.fillMaxSize().background(Color(edit.fillColor)))
    }
    FillMode.Blurred -> {
      Box(Modifier.fillMaxSize().background(Color.Black)) {
        SyntheticFrame(
          rotationDegrees = edit.transform.rotationDegrees,
          flipHorizontal = edit.transform.flipHorizontal,
          flipVertical = edit.transform.flipVertical,
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
  val rotated =
    if (edit.transform.rotationDegrees == 90 ||
      edit.transform.rotationDegrees == 270
    ) {
      1f / sourceAspect
    } else {
      sourceAspect
    }
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
      rotationDegrees = edit.transform.rotationDegrees,
      flipHorizontal = edit.transform.flipHorizontal,
      flipVertical = edit.transform.flipVertical,
      sourceAspect = sourceAspect,
      modifier =
        Modifier
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
    brush =
      Brush.linearGradient(
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
  val arrow =
    androidx.compose.ui.graphics.Path().apply {
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
      fontWeight =
        when (edit.textWeight) {
          FontWeight.Regular -> ComposeFontWeight.Normal
          FontWeight.Medium -> ComposeFontWeight.Medium
          FontWeight.Bold -> ComposeFontWeight.Bold
        },
      textAlign =
        when (edit.textAlignment) {
          TextAlignment.Start -> TextAlign.Start
          TextAlignment.Center -> TextAlign.Center
          TextAlignment.End -> TextAlign.End
        },
      modifier =
        Modifier
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
  val alignment =
    when (edit.watermarkCorner) {
      Corner.TopStart -> BiasAlignment(-1f, -1f)
      Corner.TopEnd -> BiasAlignment(1f, -1f)
      Corner.BottomStart -> BiasAlignment(-1f, 1f)
      Corner.BottomEnd -> BiasAlignment(1f, 1f)
    }

  Box(Modifier.fillMaxSize().padding(shorterSide * edit.watermarkMargin)) {
    Box(
      modifier =
        Modifier
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

private const val BLUR_GAIN = 6f

// What a stage dimension is multiplied by before it is handed to the placement, which measures whole pixels.
private const val PLACEMENT_SCALE = 1_000
