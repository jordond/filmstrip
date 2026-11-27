package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.CornerRadius
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.StorySticker
import dev.jordond.filmstrip.sample.StoryStickerArt
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryStickerKind

/**
 * Draws [sticker] in its style. The stage, the capture and the tray all draw a sticker through this.
 */
@Composable
internal fun StickerContent(sticker: StoryContent.Sticker) {
  val look = STICKER_LOOKS[sticker.style % STICKER_LOOKS.size]
  when (sticker.kind) {
    StoryStickerKind.Heart -> StoryStickerArt(StorySticker.Heart)
    StoryStickerKind.Star -> StoryStickerArt(StorySticker.Star)
    StoryStickerKind.Sparkle -> StoryStickerArt(StorySticker.Sparkle)
    StoryStickerKind.Rec -> StoryStickerArt(StorySticker.Rec)
    StoryStickerKind.Arrow -> StoryStickerArt(StorySticker.Arrow)
    StoryStickerKind.Clock -> ClockBadge(look)
    StoryStickerKind.Day -> DayBadge(DAYS[sticker.style % DAYS.size])
    StoryStickerKind.Bubble -> SpeechBubble(BUBBLES[sticker.style % BUBBLES.size])
    StoryStickerKind.Tag -> LabelTag(TAGS[sticker.style % TAGS.size], look)
  }
}

private class StickerLook(
  val ground: Color,
  val ink: Color,
)

@Composable
private fun ClockBadge(look: StickerLook) {
  Canvas(Modifier.size(STICKER_SIZE)) {
    val radius = size.minDimension / 2
    val stroke = radius * CLOCK_STROKE
    drawCircle(look.ground, radius)
    drawCircle(look.ink, radius - stroke / 2, style = Stroke(stroke))
    drawLine(look.ink, center, center + Offset(-radius * 0.38f, -radius * 0.3f), stroke, StrokeCap.Round)
    drawLine(look.ink, center, center + Offset(radius * 0.55f, -radius * 0.42f), stroke, StrokeCap.Round)
    drawCircle(look.ink, stroke, center)
  }
}

@Composable
private fun DayBadge(day: String) {
  Column(
    modifier = Modifier.width(STICKER_SIZE).clip(BadgeShape).background(Color.White),
    horizontalAlignment = Alignment.CenterHorizontally,
  ) {
    Box(Modifier.fillMaxWidth().background(DayRed).padding(vertical = 2.dp), contentAlignment = Alignment.Center) {
      StickerText("TODAY", Color.White, SMALL_TEXT)
    }
    StickerText(day, Ink, LARGE_TEXT, Modifier.padding(vertical = 4.dp))
  }
}

@Composable
private fun SpeechBubble(says: String) {
  StickerText(
    text = says,
    color = Ink,
    size = LARGE_TEXT,
    modifier =
      Modifier
        .drawBehind {
          val body = Size(size.width, size.height - BUBBLE_TAIL.toPx())
          drawRoundRect(Color.White, size = body, cornerRadius = CornerRadius(body.height / 2))
          drawPath(
            Path().apply {
              moveTo(size.width * 0.22f, body.height - 1f)
              lineTo(size.width * 0.18f, size.height)
              lineTo(size.width * 0.4f, body.height - 1f)
              close()
            },
            Color.White,
          )
        }.padding(start = 18.dp, end = 18.dp, top = 6.dp, bottom = 6.dp + BUBBLE_TAIL),
  )
}

@Composable
private fun LabelTag(
  says: String,
  look: StickerLook,
) {
  StickerText(
    text = says,
    color = look.ink,
    size = LARGE_TEXT,
    modifier =
      Modifier
        .drawBehind {
          val point = size.height / 2
          drawPath(
            Path().apply {
              moveTo(0f, point)
              lineTo(point, 0f)
              lineTo(size.width, 0f)
              lineTo(size.width, size.height)
              lineTo(point, size.height)
              close()
            },
            look.ground,
          )
          drawCircle(look.ink, point * TAG_HOLE, Offset(point, point))
        }.padding(start = 34.dp, end = 12.dp, top = 4.dp, bottom = 4.dp),
  )
}

@Composable
private fun StickerText(
  text: String,
  color: Color,
  size: Int,
  modifier: Modifier = Modifier,
) {
  Text(
    text = text,
    color = color,
    fontSize = size.sp,
    lineHeight = size.sp,
    fontFamily = MaterialTheme.typography.labelLarge.fontFamily,
    textAlign = TextAlign.Center,
    modifier = modifier,
  )
}

private val STICKER_SIZE = 72.dp
private val BUBBLE_TAIL = 10.dp
private val BadgeShape = RoundedCornerShape(8.dp)
private val Ink = Color(0xFF1A1A1A)
private val DayRed = Color(0xFFFF3B30)
private val STICKER_LOOKS =
  listOf(StickerLook(Color.White, Ink), StickerLook(Ink, Color.White), StickerLook(Color(0xFFFFB627), Ink))
private val DAYS = listOf("FRI", "SAT", "SUN")
private val BUBBLES = listOf("...", "!", "?")
private val TAGS = listOf("NEW", "HOT", "WOW")
private const val CLOCK_STROKE = 0.1f
private const val TAG_HOLE = 0.28f
private const val SMALL_TEXT = 14
private const val LARGE_TEXT = 28
