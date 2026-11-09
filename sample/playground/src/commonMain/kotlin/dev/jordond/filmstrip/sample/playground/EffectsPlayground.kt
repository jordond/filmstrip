package dev.jordond.filmstrip.sample.playground

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.background
import androidx.compose.foundation.border
import androidx.compose.foundation.clickable
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.interaction.MutableInteractionSource
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.ExperimentalLayoutApi
import androidx.compose.foundation.layout.FlowRow
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.defaultMinSize
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.selection.selectable
import androidx.compose.foundation.selection.toggleable
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
import androidx.compose.runtime.setValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.drawBehind
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.Role
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.compose.VideoSurface
import dev.jordond.filmstrip.compose.rememberFilmstripPlayer
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.SamplePreset
import dev.jordond.filmstrip.sample.loadPreset
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.SliderRow
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.controlHeight
import dev.jordond.filmstrip.sample.ui.focusRing
import kotlinx.coroutines.CancellationException
import kotlin.math.abs
import kotlin.math.round
import kotlin.time.Duration

private val Gap = 12.dp
private val TwoColumns = 560.dp
private val SCALE_HEIGHTS = listOf<Int?>(null, 1080, 720, 480)
private val ROTATIONS = listOf(0, 90, 180, 270)

/**
 * The clip every playground sample edits.
 */
internal val beachClip: SamplePreset by lazy { samplePresets.first { it.file == "beach-sunset.mp4" } }

private enum class Tab(
  val label: String,
  val stage: Stage,
) {
  Geometry("GEOMETRY", Stage.Geometry),
  Color("COLOUR", Stage.Color),
  Motion("PAN & ZOOM", Stage.Motion),
}

/**
 * The effects sample: the beach clip on a loop, a strip listing every active effect in the order the plan runs it,
 * a tab of controls for each stage and the Kotlin that gives the same picture. COMPARE plays the untouched clip.
 *
 * @param filmstrip Opens the player.
 * @param copy Puts the code listing on the clipboard.
 */
@Composable
internal fun EffectsPlayground(
  filmstrip: Filmstrip,
  copy: (String) -> Unit,
) {
  var settings by remember { mutableStateOf(EffectSettings()) }
  var compare by remember { mutableStateOf(false) }
  var tab by remember { mutableStateOf(Tab.Geometry) }
  val chain = chainOf(settings)

  BoxWithConstraints(Modifier.fillMaxSize().background(Tokens.ground)) {
    val columns = if (maxWidth >= TwoColumns) 2 else 1
    Column(
      modifier = Modifier.fillMaxSize().verticalScroll(rememberScrollState()).padding(16.dp),
      verticalArrangement = Arrangement.spacedBy(Gap),
    ) {
      StatusLine(compare, if (chain.size == 1) "1 EFFECT" else "${chain.size} EFFECTS", outputLabel(settings))
      Picture(filmstrip, beachClip) { effectsEdit(it, if (compare) EffectSettings() else settings) }
      LedSwitch("COMPARE", compare) { compare = it }
      ChainStrip(chain) { settings = it }
      Tabs(Tab.entries, tab, { it.label }, { stageColour(it.stage) }) { tab = it }
      when (tab) {
        Tab.Geometry -> GeometryControls(settings, columns) { settings = it }
        Tab.Color -> ColorControls(settings, columns) { settings = it }
        Tab.Motion -> MotionControls(settings, columns) { settings = it }
      }
      CodePanel(effectsCode(settings), copy)
    }
  }
}

@OptIn(ExperimentalLayoutApi::class)
@Composable
private fun ChainStrip(
  chain: List<ChainItem>,
  onChange: (EffectSettings) -> Unit,
) {
  FlowRow(
    modifier =
      Modifier
        .fillMaxWidth()
        .defaultMinSize(minHeight = 44.dp)
        .background(Tokens.panel)
        .border(1.dp, Tokens.line)
        .padding(horizontal = 10.dp, vertical = 8.dp),
    horizontalArrangement = Arrangement.spacedBy(6.dp),
    verticalArrangement = Arrangement.spacedBy(6.dp),
    itemVerticalAlignment = Alignment.CenterVertically,
  ) {
    if (chain.isEmpty()) {
      Text("NO EFFECTS · THE CLIP PLAYS AS IT IS", style = MaterialTheme.typography.labelSmall, color = Tokens.muted)
    }
    chain.groupBy { it.stage }.entries.forEachIndexed { index, (stage, items) ->
      if (index > 0) Text("▸", style = MaterialTheme.typography.labelSmall, color = Tokens.trackTick)
      Text(stage.label, style = MaterialTheme.typography.labelSmall, color = Tokens.muted)
      items.forEach { item -> ChainPill(item, onChange) }
    }
  }
}

@Composable
private fun ChainPill(
  item: ChainItem,
  onChange: (EffectSettings) -> Unit,
) {
  val colour = stageColour(item.stage)
  val interaction = remember { MutableInteractionSource() }
  Row(
    modifier = Modifier.background(Tokens.panelRaised).border(1.dp, colour).padding(start = 8.dp),
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Text(item.label, style = MaterialTheme.typography.labelSmall, color = colour)
    Box(
      modifier =
        Modifier
          .size(32.dp)
          .focusRing(interaction)
          .clickable(interactionSource = interaction, indication = null) { onChange(item.without) }
          .semantics { contentDescription = "Remove ${item.label.lowercase()}" },
      contentAlignment = Alignment.Center,
    ) {
      Text("×", style = MaterialTheme.typography.labelMedium, color = colour)
    }
  }
}

@Composable
private fun GeometryControls(
  settings: EffectSettings,
  columns: Int,
  onChange: (EffectSettings) -> Unit,
) {
  val cropped = settings.crop != CropChoice.Off
  Groups(columns) {
    add {
      Group("ROTATE", "counter-clockwise") {
        ChipRow {
          ROTATIONS.forEach { degrees ->
            Chip("$degrees°", settings.rotation == degrees) { onChange(settings.copy(rotation = degrees)) }
          }
        }
      }
    }
    add {
      Group("FLIP") {
        ChipRow {
          LedSwitch("HORIZONTAL", settings.flipHorizontal) { onChange(settings.copy(flipHorizontal = it)) }
          LedSwitch("VERTICAL", settings.flipVertical) { onChange(settings.copy(flipVertical = it)) }
        }
      }
    }
    add {
      Group("CROP TO") {
        ChipRow {
          CropChoice.entries.forEach { choice ->
            Chip(choice.label, settings.crop == choice) { onChange(settings.copy(crop = choice)) }
          }
        }
      }
    }
    add {
      Group("SCALE TO", "output height") {
        ChipRow {
          SCALE_HEIGHTS.forEach { height ->
            Chip(height?.toString() ?: "SOURCE", settings.scaleHeight == height) {
              onChange(settings.copy(scaleHeight = height))
            }
          }
        }
      }
    }
    add {
      Group("KEEP", if (cropped) settings.anchor.name else "pick a crop first") {
        AnchorGrid(settings.anchor, enabled = cropped) { onChange(settings.copy(anchor = it)) }
      }
    }
    add {
      val hint =
        when {
          !cropped -> "pick a crop first"
          settings.fit == Fit.Contain -> "bars, no cut"
          settings.fit == Fit.Stretch -> "fills each axis"
          else -> "fills, cuts the rest"
        }
      Group("FIT", hint) {
        ChipRow {
          listOf(Fit.Crop, Fit.Contain, Fit.Stretch).forEach { fit ->
            Chip(fit.name.uppercase(), settings.fit == fit, enabled = cropped) { onChange(settings.copy(fit = fit)) }
          }
        }
      }
    }
  }
}

@Composable
private fun ColorControls(
  settings: EffectSettings,
  columns: Int,
  onChange: (EffectSettings) -> Unit,
) {
  Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
    Group("START FROM", "a look sets the sliders") {
      ChipRow {
        Look.entries.forEach { look ->
          Chip(look.label, settings.showsLook(look)) { onChange(settings.withLook(look)) }
        }
      }
    }
    Groups(columns) {
      ColorControl.entries.forEach { control ->
        add {
          SliderRow(
            label = control.label,
            value = settings.value(control),
            valueLabel = valueLabel(control, settings.value(control)),
            range = control.range,
            onValueChange = { onChange(settings.withColor(control, snap(it, control))) },
            onReset = { onChange(settings.withColor(control, control.neutral)) },
          )
        }
      }
    }
  }
}

@Composable
private fun MotionControls(
  settings: EffectSettings,
  columns: Int,
  onChange: (EffectSettings) -> Unit,
) {
  Groups(columns) {
    add {
      Group("KEN BURNS", "runs across the clip") {
        Column(verticalArrangement = Arrangement.spacedBy(Gap)) {
          LedSwitch(if (settings.kenBurns) "ON" else "OFF", settings.kenBurns) {
            onChange(settings.copy(kenBurns = it))
          }
          ChipRow {
            KenBurnsMove.entries.forEach { move ->
              Chip(move.label, settings.move == move) { onChange(settings.copy(move = move, kenBurns = true)) }
            }
          }
        }
      }
    }
    add {
      Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
        MoveMap(settings.move)
        Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
          Text("- - FROM", style = MaterialTheme.typography.labelSmall, color = Tokens.amber)
          Text("— TO", style = MaterialTheme.typography.labelSmall, color = Tokens.pink)
        }
      }
    }
  }
}

@Composable
private fun MoveMap(move: KenBurnsMove) {
  Canvas(
    Modifier
      .widthIn(max = 300.dp)
      .fillMaxWidth()
      .aspectRatio(16f / 9f)
      .background(Tokens.screen)
      .border(1.dp, Tokens.line),
  ) {
    val dash = PathEffect.dashPathEffect(floatArrayOf(8.dp.toPx(), 6.dp.toPx()))
    outline(move.from, Tokens.amber, dash)
    outline(move.to, Tokens.pink, null)
  }
}

private fun DrawScope.outline(
  rect: NormalizedRect,
  colour: Color,
  dash: PathEffect?,
) {
  val stroke = 2.dp.toPx()
  drawRect(
    color = colour,
    topLeft = Offset(rect.left * size.width + stroke / 2, rect.top * size.height + stroke / 2),
    size = Size((rect.right - rect.left) * size.width - stroke, (rect.bottom - rect.top) * size.height - stroke),
    style = Stroke(stroke, pathEffect = dash),
  )
}

private fun stageColour(stage: Stage): Color =
  when (stage) {
    Stage.Geometry -> Tokens.amber
    Stage.Color -> Tokens.cyan
    Stage.Motion -> Tokens.pink
  }

/**
 * Rounds a slider's [value] to hundredths, or whole degrees for hue, landing on the neutral value of [control] when it
 * is close so a drag can find the value that leaves the frame unchanged.
 */
internal fun snap(
  value: Float,
  control: ColorControl,
): Float {
  val hue = control == ColorControl.Hue
  val rounded = if (hue) round(value) else round(value * 100) / 100
  val reach = if (hue) 2f else 0.02f
  return if (abs(rounded - control.neutral) <= reach) control.neutral else rounded
}
