package dev.jordond.filmstrip.sample.ui.screen

import androidx.compose.foundation.background
import androidx.compose.foundation.gestures.Orientation
import androidx.compose.foundation.gestures.scrollable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.BoxWithConstraints
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.WindowInsets
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.requiredSize
import androidx.compose.foundation.layout.safeDrawing
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.layout.windowInsetsPadding
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.LaunchedEffect
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.SpanStyle
import androidx.compose.ui.text.buildAnnotatedString
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.text.withStyle
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.em
import androidx.compose.ui.unit.sp
import dev.jordond.filmstrip.sample.SampleAppState
import dev.jordond.filmstrip.sample.presetsAvailable
import dev.jordond.filmstrip.sample.samplePresets
import dev.jordond.filmstrip.sample.ui.ColourBars
import dev.jordond.filmstrip.sample.ui.Fringe
import dev.jordond.filmstrip.sample.ui.FringeText
import dev.jordond.filmstrip.sample.ui.IconKey
import dev.jordond.filmstrip.sample.ui.LedState
import dev.jordond.filmstrip.sample.ui.LocalScreenInsets
import dev.jordond.filmstrip.sample.ui.LoadKey
import dev.jordond.filmstrip.sample.ui.Panel
import dev.jordond.filmstrip.sample.ui.SampleIcons
import dev.jordond.filmstrip.sample.ui.TextKey
import dev.jordond.filmstrip.sample.ui.Tokens
import dev.jordond.filmstrip.sample.ui.TvSet
import dev.jordond.filmstrip.sample.ui.isCompactWidth

private const val HEADLINE = "Cut, reframe and export on the device's own encoder."
private const val BLURB =
  "One composition value drives the plan, the preview and the export. Pick a clip to see what " +
      "this device will do with it."

private val TwoColumnsFrom = 1040.dp
private val PageWidth = 1200.dp
private val PhoneSetWidth = 560.dp

/**
 * The editor before a clip is loaded, a television waiting for a tape.
 *
 * The set holds the import key, the sample clips and what this device's encoders report sit in panels under it, and
 * importing is the only step between launch and the editor. A wide window puts the pitch beside the set and the two
 * panels side by side.
 */
@Composable
public fun StartScreen(
  state: SampleAppState,
  onImport: () -> Unit,
  modifier: Modifier = Modifier,
) {
  val compact = isCompactWidth()
  val insets = LocalScreenInsets.current
  val scroll = rememberScrollState()

  LaunchedEffect(Unit) {
    if (state.capabilities == null) state.refreshCapabilities()
  }

  BoxWithConstraints(
    modifier = modifier
      .fillMaxSize()
      .background(MaterialTheme.colorScheme.background)
      // The page stops at its width, so the ground either side has to scroll it too.
      .scrollable(scroll, Orientation.Vertical, reverseDirection = true),
    contentAlignment = Alignment.TopCenter,
  ) {
    val twoColumns = maxWidth >= TwoColumnsFrom
    val pageSide = insets.side.coerceAtLeast(if (compact) 16.dp else 40.dp)
    Column(
      modifier = Modifier
        .widthIn(max = PageWidth)
        .fillMaxSize()
        .windowInsetsPadding(WindowInsets.safeDrawing)
        .verticalScroll(scroll)
        .padding(horizontal = pageSide)
        .padding(top = insets.top + if (compact) 16.dp else 24.dp, bottom = if (compact) 24.dp else 48.dp),
      verticalArrangement = Arrangement.spacedBy(if (compact) 20.dp else 32.dp),
    ) {
      Deck(state, compact)
      if (twoColumns) {
        Row(horizontalArrangement = Arrangement.spacedBy(56.dp), verticalAlignment = Alignment.CenterVertically) {
          Pitch(state, compact, Modifier.weight(1f))
          StartSet(state, onImport, compact, Modifier.weight(1f))
        }
        Row(horizontalArrangement = Arrangement.spacedBy(32.dp), verticalAlignment = Alignment.Top) {
          if (presetsAvailable) PresetSection(state, Modifier.weight(1f))
          CapabilitiesSection(state, Modifier.weight(1f))
        }
      } else {
        Pitch(state, compact)
        Box(Modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
          StartSet(state, onImport, compact, Modifier.widthIn(max = PhoneSetWidth))
        }
        if (presetsAvailable) PresetSection(state)
        CapabilitiesSection(state)
      }
    }
  }
}

/**
 * The strip across the top of the start screen, the deck's stopped status and the way into diagnostics.
 */
@Composable
private fun Deck(
  state: SampleAppState,
  compact: Boolean,
) {
  val style = MaterialTheme.typography.labelLarge.copy(fontSize = if (compact) 26.sp else 28.sp)
  Row(
    modifier = Modifier.fillMaxWidth(),
    horizontalArrangement = Arrangement.SpaceBetween,
    verticalAlignment = Alignment.CenterVertically,
  ) {
    Row(
      horizontalArrangement = Arrangement.spacedBy(if (compact) 12.dp else 14.dp),
      verticalAlignment = Alignment.CenterVertically,
    ) {
      val square = if (compact) 14.dp else 16.dp
      Icon(
        imageVector = SampleIcons.Stop,
        contentDescription = null,
        tint = MaterialTheme.colorScheme.primary,
        // The glyph fills half its viewport, so it draws at twice the square and overhangs a square sized slot.
        modifier = Modifier.size(square).requiredSize(square * 2),
      )
      Text("STOP", style = style, color = MaterialTheme.colorScheme.primary)
      Text("SP", style = style, color = MaterialTheme.colorScheme.onSurfaceVariant)
    }
    TextKey("DIAGNOSTICS", onClick = state::openDiagnostics)
  }
}

@Composable
private fun Pitch(
  state: SampleAppState,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  Column(modifier.fillMaxWidth(), verticalArrangement = Arrangement.spacedBy(if (compact) 14.dp else 24.dp)) {
    Text(
      text = buildAnnotatedString {
        append("KOTLIN MULTIPLATFORM · ")
        withStyle(SpanStyle(color = MaterialTheme.colorScheme.error)) { append("PRE-ALPHA") }
      },
      style = MaterialTheme.typography.labelLarge.copy(fontSize = if (compact) 22.sp else 26.sp),
      color = MaterialTheme.colorScheme.onSurfaceVariant,
    )
    FringeText(
      text = "filmstrip",
      style = MaterialTheme.typography.displaySmall.copy(
        fontSize = if (compact) 40.sp else 54.sp,
        lineHeight = 1.em,
      ),
      color = MaterialTheme.colorScheme.onSurface,
      fringe = Fringe.Large,
      fitWidth = true,
    )
    Text(
      text = HEADLINE,
      style = MaterialTheme.typography.bodyMedium.copy(
        fontSize = if (compact) 17.sp else 20.sp,
        lineHeight = if (compact) 26.sp else 30.sp,
      ),
      color = MaterialTheme.colorScheme.onSurface,
    )
    Text(
      text = BLURB,
      style = if (compact) MaterialTheme.typography.bodySmall else MaterialTheme.typography.bodyMedium,
      color = Tokens.mutedStrong,
    )
    state.pickFailure?.let { failure ->
      Text(
        text = "Could not load that clip: $failure",
        style = MaterialTheme.typography.bodySmall,
        color = MaterialTheme.colorScheme.error,
      )
    }
  }
}

/**
 * The key that starts a session from a file on the device. It stops taking presses while a file is being read.
 */
@Composable
internal fun ImportKey(
  probing: Boolean,
  onImport: () -> Unit,
  modifier: Modifier = Modifier,
  compact: Boolean = isCompactWidth(),
) {
  LoadKey(
    label = if (probing) "READING" else "IMPORT",
    onClick = onImport,
    enabled = !probing,
    compact = compact,
    modifier = modifier,
  )
}

@Composable
private fun StartSet(
  state: SampleAppState,
  onImport: () -> Unit,
  compact: Boolean,
  modifier: Modifier = Modifier,
) {
  if (!LocalScreenInsets.current.showsSet) {
    Box(modifier.fillMaxWidth(), contentAlignment = Alignment.Center) {
      ImportKey(state.probing, onImport, compact = compact)
    }
    return
  }

  TvSet(modifier = modifier, compact = compact, led = if (state.probing) LedState.Busy else LedState.Off) {
    ColourBars(compact = compact)
    if (compact) {
      ImportKey(state.probing, onImport, Modifier.align(Alignment.Center), compact = true)
    } else {
      TubeOverlay(state, onImport)
    }
  }
}

@Composable
private fun TubeOverlay(
  state: SampleAppState,
  onImport: () -> Unit,
) {
  val osd = MaterialTheme.typography.labelLarge.copy(fontSize = 28.sp)
  val note = if (presetsAvailable) "PICK A FILE · OR A SAMPLE CLIP BELOW" else "PICK A FILE"
  Column(
    modifier = Modifier.fillMaxSize().padding(horizontal = 28.dp, vertical = 18.dp),
    verticalArrangement = Arrangement.SpaceBetween,
  ) {
    Row(Modifier.fillMaxWidth(), horizontalArrangement = Arrangement.SpaceBetween) {
      FringeText("CH 03", osd, Tokens.white)
      FringeText("AUX", osd, Tokens.white)
    }
    Column(
      modifier = Modifier.fillMaxWidth(),
      horizontalAlignment = Alignment.CenterHorizontally,
      verticalArrangement = Arrangement.spacedBy(14.dp),
    ) {
      ImportKey(state.probing, onImport, compact = false)
      FringeText(
        text = note,
        style = MaterialTheme.typography.labelMedium.copy(textAlign = TextAlign.Center),
        color = Tokens.white,
        modifier = Modifier.background(Tokens.backdrop).padding(horizontal = 12.dp, vertical = 2.dp),
      )
    }
    Box(Modifier.size(28.dp))
  }
}

@Composable
private fun PresetSection(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Panel(
    title = "SAMPLE CLIPS",
    modifier = modifier.fillMaxWidth(),
    trailing = {
      Text(
        text = "${samplePresets.size} TAPES",
        style = MaterialTheme.typography.titleMedium,
        color = MaterialTheme.colorScheme.onSurfaceVariant,
      )
    },
    bodyPadding = 0.dp,
  ) {
    PresetList(state)
  }
}

@Composable
private fun CapabilitiesSection(
  state: SampleAppState,
  modifier: Modifier = Modifier,
) {
  Panel(
    title = "DEVICE CAPABILITIES",
    modifier = modifier.fillMaxWidth(),
    trailing = {
      IconKey(
        icon = SampleIcons.Refresh,
        contentDescription = "Refresh capabilities",
        onClick = state::refreshCapabilities,
        enabled = !state.loadingCapabilities,
      )
    },
    headerVerticalPadding = 0.dp,
  ) {
    CapabilitiesContent(state)
  }
}
