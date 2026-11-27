package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.StorySticker
import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.sin

/**
 * Draws [sticker] in Compose. The REC badge is a row of a dot and text, every other sticker is an outlined shape 72 dp
 * across.
 */
@Composable
internal fun StoryStickerArt(sticker: StorySticker) {
  when (sticker) {
    StorySticker.Rec -> RecBadge()
    else -> Canvas(Modifier.size(STICKER_SIZE)) { drawSticker(sticker) }
  }
}

@Composable
private fun RecBadge() {
  Row(
    modifier =
      Modifier
        .background(
          Color(0xCC000000),
          RoundedCornerShape(6.dp),
        ).padding(horizontal = 10.dp, vertical = 4.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Box(Modifier.size(14.dp).background(RecRed, CircleShape))
    Text("REC", color = Color.White, fontSize = 28.sp, fontFamily = MaterialTheme.typography.labelLarge.fontFamily)
  }
}

private fun DrawScope.drawSticker(sticker: StorySticker) {
  val (path, fill) =
    when (sticker) {
      StorySticker.Heart -> heart(size) to HeartRed
      StorySticker.Star -> star(size, points = 5, inner = 0.45f) to StarYellow
      StorySticker.Sparkle -> star(size, points = 4, inner = 0.22f) to SparkleBlue
      StorySticker.Arrow -> arrow(size) to Color.White
      StorySticker.Rec -> error("The REC badge is drawn as a row.")
    }
  drawPath(path, fill)
  drawPath(path, Outline, style = Stroke(width = size.minDimension * OUTLINE, join = StrokeJoin.Round))
}

private fun heart(size: Size): Path {
  val w = size.width
  val h = size.height
  return Path().apply {
    moveTo(w * 0.5f, h * 0.9f)
    cubicTo(w * 0.1f, h * 0.62f, w * 0.06f, h * 0.38f, w * 0.18f, h * 0.22f)
    cubicTo(w * 0.32f, h * 0.06f, w * 0.48f, h * 0.14f, w * 0.5f, h * 0.3f)
    cubicTo(w * 0.52f, h * 0.14f, w * 0.68f, h * 0.06f, w * 0.82f, h * 0.22f)
    cubicTo(w * 0.94f, h * 0.38f, w * 0.9f, h * 0.62f, w * 0.5f, h * 0.9f)
    close()
  }
}

private fun star(
  size: Size,
  points: Int,
  inner: Float,
): Path {
  val center = Offset(size.width / 2f, size.height / 2f)
  val outer = size.minDimension * 0.44f
  return Path().apply {
    repeat(points * 2) { i ->
      val radius = if (i % 2 == 0) outer else outer * inner
      val angle = PI * i / points - PI / 2
      val point = center + Offset((cos(angle) * radius).toFloat(), (sin(angle) * radius).toFloat())
      if (i == 0) moveTo(point.x, point.y) else lineTo(point.x, point.y)
    }
    close()
  }
}

private fun arrow(size: Size): Path {
  val w = size.width
  val h = size.height
  return Path().apply {
    moveTo(w * 0.1f, h * 0.38f)
    lineTo(w * 0.55f, h * 0.38f)
    lineTo(w * 0.55f, h * 0.18f)
    lineTo(w * 0.92f, h * 0.5f)
    lineTo(w * 0.55f, h * 0.82f)
    lineTo(w * 0.55f, h * 0.62f)
    lineTo(w * 0.1f, h * 0.62f)
    close()
  }
}

private val STICKER_SIZE = 72.dp
private const val OUTLINE = 0.04f
private val Outline = Color(0xFF1A1A1A)
private val HeartRed = Color(0xFFFF4D6D)
private val StarYellow = Color(0xFFFFD23F)
private val SparkleBlue = Color(0xFF8FE3FF)
private val RecRed = Color(0xFFFF3B30)
