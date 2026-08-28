package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.SolidColor
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.StrokeJoin
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.graphics.vector.PathBuilder
import androidx.compose.ui.graphics.vector.path
import androidx.compose.ui.unit.dp

private fun vector(
  name: String,
  stroked: Boolean,
  block: PathBuilder.() -> Unit,
): ImageVector =
  ImageVector
    .Builder(
      name = name,
      defaultWidth = 24.dp,
      defaultHeight = 24.dp,
      viewportWidth = 24f,
      viewportHeight = 24f,
    ).apply {
      if (stroked) {
        path(
          stroke = SolidColor(Color.Black),
          strokeLineWidth = 1.7f,
          strokeLineCap = StrokeCap.Round,
          strokeLineJoin = StrokeJoin.Round,
          pathBuilder = block,
        )
      } else {
        path(fill = SolidColor(Color.Black), pathBuilder = block)
      }
    }.build()

private fun stroke(
  name: String,
  block: PathBuilder.() -> Unit,
): ImageVector = vector(name, stroked = true, block = block)

private fun solid(
  name: String,
  block: PathBuilder.() -> Unit,
): ImageVector = vector(name, stroked = false, block = block)

/**
 * The sample's icon set, drawn here rather than pulled from a dependency.
 *
 * Every glyph is a 24 by 24 vector on the same 1.7 stroke, so a row of tools reads as one family.
 */
public object SampleIcons {
  public val Play: ImageVector =
    solid("play") {
      moveTo(8f, 5f)
      lineTo(19f, 12f)
      lineTo(8f, 19f)
      close()
    }

  public val Pause: ImageVector =
    solid("pause") {
      moveTo(7f, 5f)
      lineTo(10.5f, 5f)
      lineTo(10.5f, 19f)
      lineTo(7f, 19f)
      close()
      moveTo(13.5f, 5f)
      lineTo(17f, 5f)
      lineTo(17f, 19f)
      lineTo(13.5f, 19f)
      close()
    }

  public val StepBack: ImageVector =
    solid("stepBack") {
      moveTo(17f, 5f)
      lineTo(9f, 12f)
      lineTo(17f, 19f)
      close()
      moveTo(6f, 5f)
      lineTo(8f, 5f)
      lineTo(8f, 19f)
      lineTo(6f, 19f)
      close()
    }

  public val StepForward: ImageVector =
    solid("stepForward") {
      moveTo(7f, 5f)
      lineTo(15f, 12f)
      lineTo(7f, 19f)
      close()
      moveTo(16f, 5f)
      lineTo(18f, 5f)
      lineTo(18f, 19f)
      lineTo(16f, 19f)
      close()
    }

  public val Loop: ImageVector =
    stroke("loop") {
      moveTo(6f, 8.5f)
      lineTo(18f, 8.5f)
      moveTo(15f, 5.5f)
      lineTo(18f, 8.5f)
      lineTo(15f, 11.5f)
      moveTo(18f, 15.5f)
      lineTo(6f, 15.5f)
      moveTo(9f, 12.5f)
      lineTo(6f, 15.5f)
      lineTo(9f, 18.5f)
    }

  public val Close: ImageVector =
    stroke("close") {
      moveTo(6f, 6f)
      lineTo(18f, 18f)
      moveTo(18f, 6f)
      lineTo(6f, 18f)
    }

  public val Check: ImageVector =
    stroke("check") {
      moveTo(5f, 12.5f)
      lineTo(9.5f, 17f)
      lineTo(19f, 7f)
    }

  public val Plus: ImageVector =
    stroke("plus") {
      moveTo(12f, 5f)
      lineTo(12f, 19f)
      moveTo(5f, 12f)
      lineTo(19f, 12f)
    }

  public val Export: ImageVector =
    stroke("export") {
      moveTo(12f, 15.5f)
      lineTo(12f, 3f)
      moveTo(8f, 7f)
      lineTo(12f, 3f)
      lineTo(16f, 7f)
      moveTo(5f, 13f)
      lineTo(5f, 20f)
      lineTo(19f, 20f)
      lineTo(19f, 13f)
    }

  public val Crop: ImageVector =
    stroke("crop") {
      moveTo(6f, 2f)
      lineTo(6f, 18f)
      lineTo(22f, 18f)
      moveTo(2f, 6f)
      lineTo(18f, 6f)
      lineTo(18f, 22f)
    }

  public val Rotate: ImageVector =
    stroke("rotate") {
      moveTo(5f, 13f)
      arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 14f, 0f)
      moveTo(16f, 10f)
      lineTo(19f, 13f)
      lineTo(22f, 10f)
    }

  public val Flip: ImageVector =
    stroke("flip") {
      moveTo(12f, 3f)
      lineTo(12f, 21f)
      moveTo(9f, 7f)
      lineTo(4f, 12f)
      lineTo(9f, 17f)
      close()
      moveTo(15f, 7f)
      lineTo(20f, 12f)
      lineTo(15f, 17f)
      close()
    }

  public val TextGlyph: ImageVector =
    stroke("text") {
      moveTo(5f, 6f)
      lineTo(19f, 6f)
      moveTo(12f, 6f)
      lineTo(12f, 19f)
    }

  public val Brightness: ImageVector =
    stroke("brightness") {
      moveTo(8f, 12f)
      arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, 8f, 0f)
      arcToRelative(4f, 4f, 0f, isMoreThanHalf = true, isPositiveArc = true, -8f, 0f)
      close()
      moveTo(12f, 2f)
      lineTo(12f, 4.2f)
      moveTo(12f, 19.8f)
      lineTo(12f, 22f)
      moveTo(2f, 12f)
      lineTo(4.2f, 12f)
      moveTo(19.8f, 12f)
      lineTo(22f, 12f)
      moveTo(5f, 5f)
      lineTo(6.6f, 6.6f)
      moveTo(17.4f, 17.4f)
      lineTo(19f, 19f)
      moveTo(19f, 5f)
      lineTo(17.4f, 6.6f)
      moveTo(6.6f, 17.4f)
      lineTo(5f, 19f)
    }

  public val Watermark: ImageVector =
    stroke("watermark") {
      moveTo(3f, 5f)
      lineTo(21f, 5f)
      lineTo(21f, 19f)
      lineTo(3f, 19f)
      close()
      moveTo(13f, 12f)
      lineTo(19f, 12f)
      lineTo(19f, 17f)
      lineTo(13f, 17f)
      close()
    }

  public val Image: ImageVector =
    stroke("image") {
      moveTo(3f, 5f)
      lineTo(21f, 5f)
      lineTo(21f, 19f)
      lineTo(3f, 19f)
      close()
      moveTo(3f, 16f)
      lineTo(9f, 10f)
      lineTo(14f, 15f)
      lineTo(17f, 12f)
      lineTo(21f, 16f)
      moveTo(14f, 8.5f)
      arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, 3f, 0f)
      arcToRelative(1.5f, 1.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -3f, 0f)
      close()
    }

  public val Volume: ImageVector =
    stroke("volume") {
      moveTo(4f, 9.5f)
      lineTo(7.5f, 9.5f)
      lineTo(12f, 5.5f)
      lineTo(12f, 18.5f)
      lineTo(7.5f, 14.5f)
      lineTo(4f, 14.5f)
      close()
      moveTo(15.5f, 9f)
      arcToRelative(4f, 4f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 6f)
      moveTo(18f, 6.5f)
      arcToRelative(7f, 7f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 11f)
    }

  public val Mute: ImageVector =
    stroke("mute") {
      moveTo(4f, 9.5f)
      lineTo(7.5f, 9.5f)
      lineTo(12f, 5.5f)
      lineTo(12f, 18.5f)
      lineTo(7.5f, 14.5f)
      lineTo(4f, 14.5f)
      close()
      moveTo(16f, 9.5f)
      lineTo(21f, 14.5f)
      moveTo(21f, 9.5f)
      lineTo(16f, 14.5f)
    }

  public val Layers: ImageVector =
    stroke("layers") {
      moveTo(12f, 3f)
      lineTo(21f, 8f)
      lineTo(12f, 13f)
      lineTo(3f, 8f)
      close()
      moveTo(3f, 13f)
      lineTo(12f, 18f)
      lineTo(21f, 13f)
    }

  public val Trim: ImageVector =
    stroke("trim") {
      moveTo(9f, 4f)
      lineTo(6f, 4f)
      lineTo(6f, 20f)
      lineTo(9f, 20f)
      moveTo(15f, 4f)
      lineTo(18f, 4f)
      lineTo(18f, 20f)
      lineTo(15f, 20f)
      moveTo(12f, 8f)
      lineTo(12f, 16f)
    }

  public val Scale: ImageVector =
    stroke("scale") {
      moveTo(4f, 10f)
      lineTo(4f, 4f)
      lineTo(10f, 4f)
      moveTo(20f, 14f)
      lineTo(20f, 20f)
      lineTo(14f, 20f)
      moveTo(4.6f, 4.6f)
      lineTo(19.4f, 19.4f)
    }

  public val Sliders: ImageVector =
    stroke("sliders") {
      moveTo(4f, 7f)
      lineTo(20f, 7f)
      moveTo(4f, 12f)
      lineTo(20f, 12f)
      moveTo(4f, 17f)
      lineTo(20f, 17f)
      moveTo(7f, 7f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
      close()
      moveTo(13f, 12f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
      close()
      moveTo(6f, 17f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, 4f, 0f)
      arcToRelative(2f, 2f, 0f, isMoreThanHalf = true, isPositiveArc = true, -4f, 0f)
      close()
    }

  public val Info: ImageVector =
    stroke("info") {
      moveTo(4f, 12f)
      arcToRelative(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, 16f, 0f)
      arcToRelative(8f, 8f, 0f, isMoreThanHalf = true, isPositiveArc = true, -16f, 0f)
      close()
      moveTo(12f, 11f)
      lineTo(12f, 16.5f)
      moveTo(12f, 7.8f)
      lineTo(12f, 8f)
    }

  public val Warning: ImageVector =
    stroke("warning") {
      moveTo(12f, 4f)
      lineTo(21f, 19f)
      lineTo(3f, 19f)
      close()
      moveTo(12f, 10f)
      lineTo(12f, 14f)
      moveTo(12f, 16.4f)
      lineTo(12f, 16.6f)
    }

  public val Refresh: ImageVector =
    stroke("refresh") {
      moveTo(19.5f, 12f)
      arcToRelative(7.5f, 7.5f, 0f, isMoreThanHalf = true, isPositiveArc = true, -2.2f, -5.3f)
      moveTo(17.3f, 3.4f)
      lineTo(17.3f, 6.7f)
      lineTo(14f, 6.7f)
    }

  public val Undo: ImageVector =
    stroke("undo") {
      moveTo(8f, 6f)
      lineTo(4f, 10f)
      lineTo(8f, 14f)
      moveTo(4f, 10f)
      lineTo(14f, 10f)
      arcToRelative(4.5f, 4.5f, 0f, isMoreThanHalf = false, isPositiveArc = true, 0f, 9f)
      lineTo(9f, 19f)
    }

  public val ChevronDown: ImageVector =
    stroke("chevronDown") {
      moveTo(6f, 9.5f)
      lineTo(12f, 15.5f)
      lineTo(18f, 9.5f)
    }

  public val ChevronUp: ImageVector =
    stroke("chevronUp") {
      moveTo(6f, 14.5f)
      lineTo(12f, 8.5f)
      lineTo(18f, 14.5f)
    }

  public val Film: ImageVector =
    stroke("film") {
      moveTo(3f, 5f)
      lineTo(21f, 5f)
      lineTo(21f, 19f)
      lineTo(3f, 19f)
      close()
      moveTo(7.5f, 5f)
      lineTo(7.5f, 19f)
      moveTo(16.5f, 5f)
      lineTo(16.5f, 19f)
      moveTo(4.8f, 8f)
      lineTo(6.2f, 8f)
      moveTo(4.8f, 12f)
      lineTo(6.2f, 12f)
      moveTo(4.8f, 16f)
      lineTo(6.2f, 16f)
      moveTo(17.8f, 8f)
      lineTo(19.2f, 8f)
      moveTo(17.8f, 12f)
      lineTo(19.2f, 12f)
      moveTo(17.8f, 16f)
      lineTo(19.2f, 16f)
    }
}
