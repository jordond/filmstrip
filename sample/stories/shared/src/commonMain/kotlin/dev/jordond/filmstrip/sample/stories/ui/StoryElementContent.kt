package dev.jordond.filmstrip.sample.stories.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.luminance
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontFamily
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.stories.StoryAlign
import dev.jordond.filmstrip.sample.stories.StoryContent
import dev.jordond.filmstrip.sample.stories.StoryElement
import dev.jordond.filmstrip.sample.stories.StoryFace
import dev.jordond.filmstrip.sample.stories.StoryPlate
import dev.jordond.filmstrip.sample.stories.resources.Res
import dev.jordond.filmstrip.sample.stories.resources.dm_serif_display_regular
import dev.jordond.filmstrip.sample.stories.resources.pacifico_regular
import org.jetbrains.compose.resources.Font

/**
 * Draws [element]. The stage and the export's capture both call this and nothing else, so an element looks the same
 * live and in the file.
 */
@Composable
internal fun StoryElementContent(element: StoryElement) {
  when (val content = element.content) {
    is StoryContent.Caption -> CaptionContent(content)
    is StoryContent.Sticker -> StickerContent(content)
  }
}

@Composable
private fun CaptionContent(caption: StoryContent.Caption) {
  Text(text = caption.text, style = captionStyle(caption), modifier = Modifier.captionPlate(caption))
}

/**
 * The plate behind [caption] and the room around its text. The caption and the field it is written in both wear
 * it, so the text sits in the same place in each.
 */
internal fun Modifier.captionPlate(caption: StoryContent.Caption): Modifier =
  when (caption.plate) {
    StoryPlate.None -> padding(horizontal = PLATE_INSET)
    StoryPlate.Solid -> background(Color(caption.color), PlateShape).padding(PLATE_INSET, PLATE_INSET / 2)
    StoryPlate.Translucent -> background(TranslucentPlate, PlateShape).padding(PLATE_INSET, PLATE_INSET / 2)
  }

/**
 * The text style [caption] is drawn and edited in. On a solid plate the text takes whichever of black and white
 * reads against the plate's colour.
 */
@Composable
internal fun captionStyle(caption: StoryContent.Caption): TextStyle {
  val color = Color(caption.color)
  return TextStyle(
    color =
      when {
        caption.plate != StoryPlate.Solid -> color
        color.luminance() > LIGHT -> Color.Black
        else -> Color.White
      },
    fontSize = CAPTION_SIZE.sp,
    fontWeight = if (caption.face == StoryFace.Type) FontWeight.Bold else FontWeight.Normal,
    fontFamily = caption.face.family(),
    textAlign =
      when (caption.align) {
        StoryAlign.Start -> TextAlign.Start
        StoryAlign.Center -> TextAlign.Center
        StoryAlign.End -> TextAlign.End
      },
  )
}

/**
 * The theme's family for this face, so it resolves the same way on the stage and inside the capture host.
 */
@Composable
internal fun StoryFace.family(): FontFamily? =
  when (this) {
    StoryFace.Display -> MaterialTheme.typography.displaySmall.fontFamily
    StoryFace.Type -> MaterialTheme.typography.bodyMedium.fontFamily
    StoryFace.Pixel -> MaterialTheme.typography.labelLarge.fontFamily
    StoryFace.Script -> FontFamily(Font(Res.font.pacifico_regular))
    StoryFace.Serif -> FontFamily(Font(Res.font.dm_serif_display_regular))
  }

private val PlateShape = RoundedCornerShape(8.dp)
private val PLATE_INSET = 12.dp
private val TranslucentPlate = Color(0x8C000000)
private const val CAPTION_SIZE = 28
private const val LIGHT = 0.5f
