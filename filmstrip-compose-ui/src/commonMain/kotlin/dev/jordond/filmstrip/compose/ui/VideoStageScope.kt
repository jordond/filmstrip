package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.layout.BoxScope
import androidx.compose.runtime.Stable
import dev.jordond.filmstrip.compose.ui.geometry.CropFrame

/**
 * What content drawn over a [VideoStage] is given.
 *
 * A [BoxScope], so an overlay places itself with `align` and `matchParentSize` the way it would in any box. [frame] is
 * the letterbox the stage already computed, so an overlay reads where the picture is rather than measuring for it a
 * second time.
 *
 * A `Row` or a `Column` opened inside the content shadows this scope, so chrome that reads [frame] or [aspect] from
 * inside one takes them as parameters.
 *
 * ```
 * VideoStage(player = player, outputAspect = outputAspect) {
 *   CropOverlay(rect = rect, onRectChange = { rect = it })
 *
 *   Text(
 *     text = "${(aspect * 100).roundToInt() / 100f}",
 *     modifier = Modifier.align(Alignment.BottomEnd),
 *   )
 * }
 * ```
 *
 * @property frame Where the video is drawn, in the stage's own coordinate space, and the mapping between that and a
 * normalized rectangle.
 * @property aspect Width over height of the frame the stage is showing, which is what a live preview reports while one
 * is running and the aspect the stage was given otherwise. Zero before either is known.
 */
@Stable
public interface VideoStageScope : BoxScope {
  public val frame: CropFrame

  public val aspect: Float
}

/**
 * The scope a stage hands its content, delegating the box part to the stage's own box.
 */
internal class VideoStageContent(
  boxScope: BoxScope,
  override val frame: CropFrame,
  override val aspect: Float,
) : VideoStageScope,
  BoxScope by boxScope
