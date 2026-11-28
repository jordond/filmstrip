package dev.jordond.filmstrip.sample.playground

import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableIntStateOf
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.rememberUpdatedState
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.focus.FocusRequester
import androidx.compose.ui.focus.focusRequester
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.input.pointer.PointerEventPass
import androidx.compose.ui.input.pointer.PointerEventType
import androidx.compose.ui.input.pointer.PointerType
import androidx.compose.ui.input.pointer.pointerInput
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.compose.overlay.rememberComposedOverlayState
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.compose.ui.ComposedOverlayDefaults
import dev.jordond.filmstrip.compose.ui.VideoStage
import dev.jordond.filmstrip.compose.ui.component.ComposedOverlay
import dev.jordond.filmstrip.compose.ui.interaction.composedOverlayKeys
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.Tokens
import kotlinx.coroutines.CancellationException
import kotlin.math.log2
import kotlin.math.pow
import kotlin.math.roundToInt
import kotlin.time.Duration

/**
 * The composed overlays sample: real footage with a caption and a REC badge written in Compose over it. Each one can be
 * dragged, pinched and turned on the picture, or sized and turned with the sliders, and BAKED swaps them for the
 * pictures the stage bakes of them, drawn by the backend into the edit.
 *
 * @param filmstrip Opens the player.
 * @param copy Puts the code listing on the clipboard.
 */
@Composable
internal fun ComposedPlayground(
  filmstrip: Filmstrip,
  copy: (String) -> Unit,
) {
  var settings by remember { mutableStateOf(ComposedSettings()) }
  var mouse by remember { mutableStateOf(false) }

  BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.ground)) {
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(12.dp),
    ) {
      StatusLine(compare = false, composedSummary(settings, mouse), "OUT 16:9")
      ComposedPicture(filmstrip, settings, onMouse = { mouse = true }) { settings = it }
      tearsOfSteel.attribution?.let { credit ->
        Text("${tearsOfSteel.name} · $credit", style = MaterialTheme.typography.bodySmall, color = Tokens.muted)
      }
      ComposedControls(settings) { settings = it }
      CodePanel(composedCode(settings), copy)
    }
  }
}

@Composable
private fun ComposedPicture(
  filmstrip: Filmstrip,
  settings: ComposedSettings,
  onMouse: () -> Unit,
  onChange: (ComposedSettings) -> Unit,
) {
  val focus = remember { FocusRequester() }
  val currentOnMouse by rememberUpdatedState(onMouse)
  var attempt by remember { mutableIntStateOf(0) }
  var source by remember { mutableStateOf<MediaSource?>(null) }
  var failure by remember { mutableStateOf<String?>(null) }
  LaunchedEffect(attempt) {
    failure = null
    try {
      source = loadPreset(tearsOfSteel)
    } catch (cancelled: CancellationException) {
      throw cancelled
    } catch (error: Throwable) {
      failure = error.message ?: "The clip did not load"
    }
  }

  Box(
    modifier =
      Modifier
        .fillMaxWidth()
        .aspectRatio(16f / 9f)
        .background(Tokens.screen)
        .border(1.dp, Tokens.line)
        // A press on the picture hands it the keyboard, so the keys move whichever element is selected.
        .pointerInput(Unit) {
          awaitPointerEventScope {
            while (true) {
              val event = awaitPointerEvent(PointerEventPass.Initial)
              if (event.changes.any { it.type == PointerType.Mouse }) currentOnMouse()
              if (event.type == PointerEventType.Press) focus.requestFocus()
            }
          }
        }.focusRequester(focus)
        .composedOverlayKeys(
          transform = settings.transformOf(settings.selected),
          onTransformChange = { onChange(settings.with(settings.selected, it)) },
          enabled = !settings.baked,
        ),
    contentAlignment = Alignment.Center,
  ) {
    val loaded = source
    when {
      loaded != null -> {
        ComposedStage(filmstrip, loaded, settings, onChange)
      }
      failure != null -> {
        Column(horizontalAlignment = Alignment.CenterHorizontally, verticalArrangement = Arrangement.spacedBy(8.dp)) {
          Text(failure.orEmpty(), style = MaterialTheme.typography.bodyMedium, color = Tokens.red)
          Chip("RETRY", selected = false) { attempt++ }
        }
      }
      else -> {
        Text("LOADING ${tearsOfSteel.file}", style = MaterialTheme.typography.labelMedium, color = Tokens.muted)
      }
    }
  }
}

@Composable
private fun ComposedStage(
  filmstrip: Filmstrip,
  source: MediaSource,
  settings: ComposedSettings,
  onChange: (ComposedSettings) -> Unit,
) {
  val overlays = rememberComposedOverlayState()
  var baked by remember { mutableStateOf<List<ImageOverlay>>(emptyList()) }
  val composition =
    remember(source, baked) {
      compositionOf { clip(source) }.let { edit -> edit.withEffects(edit.effects + baked) }
    }
  val player = rememberFilmstripPlayer(filmstrip, composition)
  val state by player.state.collectAsState()
  val info by player.previewInfo.collectAsState()
  val output = info.outputSize.takeIf { it.width > 0 && it.height > 0 } ?: FALLBACK_OUTPUT

  LaunchedEffect(player) {
    player.setVolume(0f)
    player.setLoopRange(TimeRange.of(Duration.ZERO, tearsOfSteel.duration))
    player.play()
  }
  LaunchedEffect(settings, output) {
    baked = if (settings.baked) overlays.bake(output) else emptyList()
  }

  VideoStage(player = player, outputAspect = 16f / 9f, modifier = Modifier.fillMaxSize(), overlays = overlays) {
    // A baked element stays on the stage unseen, since the stage bakes what it holds.
    ComposedElement.entries.forEach { element ->
      ComposedOverlay(
        transform = settings.transformOf(element),
        onTransformChange = { onChange(settings.with(element, it)) },
        shown = !settings.baked,
        selected = settings.selected == element,
        onSelect = { onChange(settings.copy(selected = element)) },
      ) { ElementContent(element, settings) }
    }
  }
  Box(Modifier.fillMaxSize()) {
    Box(Modifier.align(Alignment.BottomStart).padding(8.dp)) {
      Chip(if (state.playWhenReady) "PAUSE" else "PLAY", selected = false, ground = Tokens.backdrop) {
        if (state.playWhenReady) player.pause() else player.play()
      }
    }
  }
}

@Composable
private fun ElementContent(
  element: ComposedElement,
  settings: ComposedSettings,
) {
  when (element) {
    ComposedElement.Caption -> {
      Text(
        text = settings.text,
        style = MaterialTheme.typography.displaySmall,
        color = Color.White,
        modifier = Modifier.background(Tokens.backdrop).padding(horizontal = 14.dp, vertical = 8.dp),
      )
    }
    ComposedElement.Badge -> {
      Row(
        modifier = Modifier.background(Tokens.backdrop).padding(horizontal = 10.dp, vertical = 4.dp),
        horizontalArrangement = Arrangement.spacedBy(6.dp),
        verticalAlignment = Alignment.CenterVertically,
      ) {
        Box(Modifier.size(14.dp).background(Tokens.red, CircleShape))
        Text("REC", style = MaterialTheme.typography.labelLarge, color = Color.White)
      }
    }
  }
}

@Composable
private fun ComposedControls(
  settings: ComposedSettings,
  onChange: (ComposedSettings) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(18.dp)) {
    LedSwitch("BAKED", settings.baked) { onChange(settings.copy(baked = it)) }
    Group("TEXT") {
      CaptionField(settings.text) { onChange(settings.copy(text = it.take(ComposedSettings.MAX_TEXT))) }
    }
    Group("ELEMENT", "or tap one on the picture") {
      ChipRow {
        ComposedElement.entries.forEach { element ->
          Chip(element.label, settings.selected == element) { onChange(settings.copy(selected = element)) }
        }
      }
    }
    val element = settings.selected
    val transform = settings.transformOf(element)
    SliderRow(
      label = "Size",
      value = log2(transform.scale),
      valueLabel = floatLiteral(transform.scale).removeSuffix("f"),
      range = log2(ComposedOverlayDefaults.MIN_SCALE)..log2(ComposedOverlayDefaults.MAX_SCALE),
      onValueChange = { step ->
        val scale = roundToHundredths(2f.pow(step)).coerceAtLeast(ComposedOverlayDefaults.MIN_SCALE)
        onChange(settings.with(element, OverlayTransform(transform.center, scale, transform.rotationDegrees)))
      },
      onReset = { onChange(settings.with(element, OverlayTransform(transform.center, 1f, transform.rotationDegrees))) },
    )
    SliderRow(
      label = "Turn",
      value = transform.rotationDegrees.coerceIn(-HALF_TURN, HALF_TURN),
      valueLabel = "${transform.rotationDegrees.roundToInt()}°",
      range = -HALF_TURN..HALF_TURN,
      onValueChange = { degrees ->
        onChange(
          settings.with(element, OverlayTransform(transform.center, transform.scale, degrees.roundToInt().toFloat())),
        )
      },
      onReset = { onChange(settings.with(element, OverlayTransform(transform.center, transform.scale, 0f))) },
    )
  }
}

private val FALLBACK_OUTPUT = Size(1280, 720)
private const val HALF_TURN = 180f
