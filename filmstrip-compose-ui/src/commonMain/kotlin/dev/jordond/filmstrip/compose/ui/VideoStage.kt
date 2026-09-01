package dev.jordond.filmstrip.compose.ui

import androidx.compose.foundation.background
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxScope
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.padding
import androidx.compose.runtime.Composable
import androidx.compose.runtime.remember
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalDensity
import androidx.compose.ui.tooling.preview.Preview
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.compose.VideoContentScale
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.VideoSurfaceState
import dev.jordond.filmstrip.compose.rememberVideoSurfaceState
import dev.jordond.filmstrip.compose.ui.component.CropOverlay
import dev.jordond.filmstrip.compose.ui.component.PreviewSurface
import dev.jordond.filmstrip.compose.ui.geometry.CropFrame
import dev.jordond.filmstrip.compose.videoContentRect
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.player.VideoPlayer
import androidx.compose.ui.geometry.Size as ComposeSize

/**
 * A box the shape of the composition's output frame, holding the video and whatever is drawn over it.
 *
 * The stage takes the largest rectangle of that shape which fits the space it is given, so give it space to fit into
 * rather than a size to fill: a `Modifier.padding` inside a centred box leaves it room, while a `Modifier.fillMaxSize`
 * pins both axes and leaves it nothing to letterbox with.
 *
 * A live [player] reports the frame it is showing rather than the one it was last asked for, and that wins over
 * [outputAspect], so the stage and the picture in it change shape on the same frame instead of the stage moving a swap
 * ahead of the video. [outputAspect] carries the shape until a player has one, and on its own where there is no player
 * at all.
 *
 * A null [player] is the whole signal for the [fallback] slot. A host whose preview stopped, or whose platform reported
 * `PlaybackError.BackendMissing` , passes null and draws its own stand-in at the same letterboxed size rather than
 * passing a player it does not want rendered.
 *
 * ```
 * val composition = remember(source) { filmstrip.composition { clip(source) } }
 * val player = rememberFilmstripPlayer(filmstrip, composition)
 * val playerState by player.state.collectAsState()
 * var rect by remember(source) { mutableStateOf(NormalizedRect.Full) }
 *
 * // Every platform ships a preview backend, so a build reaching this is one that never registered
 * // it, and the schematic stands in for the picture.
 * val missing = (playerState.status as? PlaybackStatus.Error)?.error is PlaybackError.BackendMissing
 *
 * Box(Modifier.fillMaxSize(), contentAlignment = Alignment.Center) {
 *   VideoStage(
 *     player = player.takeIf { !missing },
 *     outputAspect = info?.video?.displaySize?.aspect ?: 0f,
 *     modifier = Modifier.padding(16.dp),
 *     fallback = { EditSchematic(edit, Modifier.fillMaxSize()) },
 *   ) {
 *     CropOverlay(rect = rect, onRectChange = { rect = it })
 *   }
 * }
 * ```
 *
 * @param player The player to render, or null to draw [fallback] instead.
 * @param outputAspect Width over height of the frame the composition outputs, which the stage uses until [player]
 * presents one of its own. Anything at or below zero leaves the stage filling whatever space it was given, the way
 * `Modifier.videoAspect` stays inert until an output size arrives.
 * @param modifier Modifier for the stage, applied outside the shape it lays itself out to, so a border or a clip
 * follows the letterboxed frame rather than the surrounding space.
 * @param contentScale How the video fills the stage.
 * @param surface The holder the presented size is read from, for a host that already keeps one over [player]. Null
 * leaves the stage keeping its own.
 * @param fallback Drawn at the stage's size when [player] is null.
 * @param content Drawn over the video or the fallback, for overlays that address the frame.
 */
@Composable
public fun VideoStage(
  player: VideoPlayer?,
  outputAspect: Float,
  modifier: Modifier = Modifier,
  contentScale: VideoContentScale = VideoContentScale.Fit,
  surface: VideoSurfaceState? = null,
  fallback: @Composable BoxScope.() -> Unit = { },
  content: @Composable VideoStageScope.() -> Unit = { },
) {
  val aspect = stageAspect(player, outputAspect, surface)
  // The box is clamped and the letterbox below is not, so a degenerate aspect draws its picture at its own shape
  // inside the nearest box this stage will lay out.
  val shaped =
    if (aspect > 0f) modifier.aspectRatio(aspect.coerceIn(VideoStageDefaults.AspectRange)) else modifier

  BoxWithConstraints(shaped, contentAlignment = Alignment.Center) {
    val available = with(LocalDensity.current) { ComposeSize(maxWidth.toPx(), maxHeight.toPx()) }
    val frame =
      remember(aspect, available, contentScale) {
        CropFrame(videoContentRect(aspect, available, contentScale))
      }

    if (player == null) fallback() else VideoSurface(player, Modifier.fillMaxSize(), contentScale)

    val boxScope = this
    remember(boxScope, frame, aspect) { VideoStageContent(boxScope, frame, aspect) }.content()
  }
}

/**
 * The aspect the stage lays out to, which is what [player] is presenting when it has something to present.
 */
@Composable
private fun stageAspect(
  player: VideoPlayer?,
  outputAspect: Float,
  surface: VideoSurfaceState?,
): Float {
  if (player == null) return outputAspect

  val presented = (surface ?: rememberVideoSurfaceState(player)).outputSize.aspect
  return if (presented > 0f) presented else outputAspect
}

@Preview
@Composable
private fun VideoStagePreview() {
  PreviewSurface(height = PreviewStageHeight) {
    VideoStage(
      player = null,
      outputAspect = 16f / 9f,
      modifier = Modifier.padding(PreviewStagePadding),
      fallback = { Box(Modifier.fillMaxSize().background(PreviewFallbackColor)) },
    ) {
      CropOverlay(rect = NormalizedRect(0.15f, 0.1f, 0.85f, 0.9f), onRectChange = { })
    }
  }
}

private val PreviewStageHeight = 220.dp
private val PreviewStagePadding = 12.dp
private val PreviewFallbackColor = Color(0xFF33405C)
