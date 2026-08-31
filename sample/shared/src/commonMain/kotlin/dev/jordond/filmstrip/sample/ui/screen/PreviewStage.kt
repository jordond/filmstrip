package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.offset
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.BiasAlignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.blur
import androidx.compose.ui.draw.clip
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
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberVideoSurfaceState
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlaybackStatus
import dev.jordond.filmstrip.sample.CropMode
import dev.jordond.filmstrip.sample.EditState
import dev.jordond.filmstrip.sample.FillMode
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.ui.Pill
import dev.jordond.filmstrip.style.FontWeight
import dev.jordond.filmstrip.style.TextAlignment
import kotlin.math.max
import kotlin.math.min
import androidx.compose.ui.text.font.FontWeight as ComposeFontWeight

/**
 * The editor's viewport.
 *
 * It plays the edit through the player `preview` hands back, letterboxed to the output aspect
 * rather than the source's, which is what a surface has to do once a crop is attached. Where no
 * preview backend is registered it falls back to a schematic of the same edit, so the rest of the
 * editor stays usable on a target that cannot play anything yet.
 *
 * The player itself is started and stopped above this, so folding or unfolding a host that swaps
 * this out for a differently laid out copy of itself never tears the player down.
 */
@Composable
public fun PreviewStage(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val edit = state.edit
  val aspect = stageAspect(state).coerceIn(0.2f, 5f)
  val player = state.player

  Box(
    modifier = modifier.background(MaterialTheme.colorScheme.background),
    contentAlignment = Alignment.Center,
  ) {
    BoxWithConstraints(Modifier.fillMaxSize().padding(16.dp), contentAlignment = Alignment.Center) {
      val frameWidth: Dp
      val frameHeight: Dp
      if (maxWidth / maxHeight > aspect) {
        frameHeight = maxHeight
        frameWidth = maxHeight * aspect
      } else {
        frameWidth = maxWidth
        frameHeight = maxWidth / aspect
      }
      val shorterSide = min(frameWidth.value, frameHeight.value).dp

      Box(
        modifier = Modifier
          .size(frameWidth, frameHeight)
          .clip(RoundedCornerShape(4.dp))
          .border(1.dp, MaterialTheme.colorScheme.outlineVariant, RoundedCornerShape(4.dp)),
      ) {
        if (player != null && !state.previewUnavailable) {
          Box(Modifier.fillMaxSize().background(Color.Black))
          VideoSurface(player, Modifier.fillMaxSize())
        } else {
          FrameBackground(edit, shorterSide, state.sourceAspect)
          FrameContent(edit, frameWidth, frameHeight, state.sourceAspect)
          BrightnessScrim(edit.brightness)
          TextOverlay(edit, frameWidth, frameHeight)
          WatermarkOverlay(edit, frameWidth, shorterSide)
        }
      }
    }

    StageChrome(state, Modifier.fillMaxSize().padding(12.dp))
  }
}

/**
 * The aspect the stage frames.
 *
 * A live preview reports the frame it is showing rather than the one it was last asked for, so the
 * stage and the picture in it change shape on the same frame instead of the stage moving a swap
 * ahead of the video. The schematic has no picture to wait for and follows the edit directly.
 */
@Composable
private fun stageAspect(state: SampleAppState): Float {
  val edited = state.edit.outputAspect(state.sourceAspect)
  val player = state.player
  if (player == null || state.previewUnavailable) return edited

  val presented = rememberVideoSurfaceState(player).outputSize.aspect
  return if (presented > 0f) presented else edited
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
) {
  val rotated = if (edit.rotationDegrees == 90 || edit.rotationDegrees == 270) 1f / sourceAspect else sourceAspect
  val contentWidth: Dp
  val contentHeight: Dp
  var offsetX = 0.dp
  var offsetY = 0.dp

  // Offsets are measured from the centre, because that is where a box places a child larger than
  // itself no matter what alignment it was given.
  when {
    edit.cropMode == CropMode.Rect && edit.cropRect.isValid -> {
      val rect = edit.cropRect
      contentWidth = frameWidth / rect.width
      contentHeight = frameHeight / rect.height
      offsetX = -contentWidth * rect.left - (frameWidth - contentWidth) / 2f
      offsetY = -contentHeight * rect.top - (frameHeight - contentHeight) / 2f
    }

    edit.cropMode == CropMode.Aspect && edit.cropFit == Fit.Stretch -> {
      contentWidth = frameWidth
      contentHeight = frameHeight
    }

    edit.cropMode == CropMode.Aspect && edit.cropFit == Fit.Contain -> {
      if (frameWidth / frameHeight > rotated) {
        contentHeight = frameHeight
        contentWidth = frameHeight * rotated
      } else {
        contentWidth = frameWidth
        contentHeight = frameWidth / rotated
      }
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
      colors = listOf(Color(0xFF2B3A67), Color(0xFF6C3F8F), Color(0xFFB4553C)),
      start = Offset.Zero,
      end = Offset(width, height),
    ),
    size = frame,
  )

  val gridColor = Color.White.copy(alpha = 0.10f)
  for (i in 1..2) {
    drawLine(gridColor, Offset(width * i / 3f, 0f), Offset(width * i / 3f, height), strokeWidth = 1f)
    drawLine(gridColor, Offset(0f, height * i / 3f), Offset(width, height * i / 3f), strokeWidth = 1f)
  }

  // A corner block and an up arrow, so a rotation and a flip both change something obvious.
  drawRect(
    color = Color(0xFFFFB454),
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
  drawPath(arrow, Color.White.copy(alpha = 0.9f))

  drawCircle(
    color = Color(0xFF5FD3A3),
    radius = unit * 0.13f,
    center = Offset(width * 0.36f, height * 0.55f),
  )
  drawCircle(
    color = Color.White.copy(alpha = 0.35f),
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
    color = Color(0xFF8E9BFF).copy(alpha = 0.9f),
    topLeft = Offset(width * 0.08f, height * 0.87f),
    size = Size(width * 0.3f * sourceAspect.coerceIn(0.5f, 2f), height * 0.04f),
  )
}

@Composable
private fun BrightnessScrim(factor: Float) {
  when {
    factor < 1f -> Box(Modifier.fillMaxSize().background(Color.Black.copy(alpha = (1f - factor).coerceIn(0f, 1f))))
    factor > 1f -> Box(
      Modifier
        .fillMaxSize()
        .background(Color.White.copy(alpha = ((factor - 1f) * 0.55f).coerceIn(0f, 0.85f))),
    )
    else -> Unit
  }
}

@Composable
private fun TextOverlay(
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
        .then(if (plate != null) Modifier.background(Color(plate), RoundedCornerShape(3.dp)) else Modifier)
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
        .background(Color.White.copy(alpha = 0.14f * edit.watermarkOpacity), RoundedCornerShape(3.dp))
        .border(1.dp, Color.White.copy(alpha = 0.7f * edit.watermarkOpacity), RoundedCornerShape(3.dp)),
      contentAlignment = Alignment.Center,
    ) {
      Text(
        text = "IMG",
        color = Color.White.copy(alpha = 0.85f * edit.watermarkOpacity),
        fontSize = with(LocalDensity.current) { (width * 0.22f).toSp() },
        fontWeight = ComposeFontWeight.Bold,
      )
    }
  }
}

@Composable
private fun StageChrome(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  val scaled = state.edit.scaleHeight.takeIf { state.edit.scaleEnabled }
  val height = state.targetHeight ?: scaled ?: state.info?.video?.displaySize?.height
  val aspect = state.edit.outputAspect(state.sourceAspect)
  val live = state.player != null && !state.previewUnavailable

  Box(modifier) {
    Column(Modifier.align(Alignment.TopStart)) {
      if (live) {
        Pill(state.playerStatus.pillLabel(), MaterialTheme.colorScheme.primary)
      } else {
        Pill("SCHEMATIC", MaterialTheme.colorScheme.secondary)
      }
    }
    Row(Modifier.align(Alignment.TopEnd)) {
      if (height != null) {
        Pill("${(height * aspect).toInt()} x $height", MaterialTheme.colorScheme.onSurfaceVariant)
      }
    }

    state.previewNote()?.let { note ->
      Text(
        text = note,
        style = MaterialTheme.typography.labelSmall,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
        textAlign = TextAlign.Center,
        modifier = Modifier
          .align(Alignment.BottomCenter)
          .background(MaterialTheme.colorScheme.surfaceContainer, RoundedCornerShape(6.dp))
          .padding(horizontal = 10.dp, vertical = 6.dp),
      )
    }
  }
}

/**
 * What the stage says about a preview that is not playing, or null while it is.
 */
private fun SampleAppState.previewNote(): String? =
  when (val error = previewError) {
    null -> null
    is PlaybackError.BackendMissing -> "No preview backend here. The schematic stands in for it."
    else -> "Preview stopped: ${error.message}"
  }

private fun PlaybackStatus.pillLabel(): String =
  when (this) {
    PlaybackStatus.Idle -> "IDLE"
    PlaybackStatus.Preparing -> "LOADING"
    PlaybackStatus.Ready -> "PREVIEW"
    PlaybackStatus.Ended -> "ENDED"
    PlaybackStatus.Released -> "RELEASED"
    is PlaybackStatus.Error -> "ERROR"
  }

private const val BLUR_GAIN = 6f
private const val CAP_HEIGHT_RATIO = 0.7f
private const val WATERMARK_ASPECT = 0.6f
