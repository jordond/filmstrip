package dev.jordond.filmstrip.sample.ui

import androidx.compose.runtime.Composable
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.ProvidableCompositionLocal
import androidx.compose.runtime.staticCompositionLocalOf
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The room a screen leaves for rounded corners around the whole window, and whether it draws a set of its own.
 *
 * Nothing but the site's Try it television asks for this, whose tube rounds the corners of the app inside it.
 *
 * @property top How far a top bar sits down from the window's top edge.
 * @property side How far a top bar sits in from each side.
 * @property bottom How far what runs to a bottom corner stops short of the window's bottom edge.
 * @property showsSet Whether a screen draws its own `TvSet`, which reads wrong on a set that is already there.
 */
public class ScreenInsets(
  public val top: Dp = 0.dp,
  public val side: Dp = 0.dp,
  public val bottom: Dp = 0.dp,
  public val showsSet: Boolean = true,
)

/**
 * The [ScreenInsets] in force, with nothing to clear unless a host says otherwise.
 */
public val LocalScreenInsets: ProvidableCompositionLocal<ScreenInsets> = staticCompositionLocalOf { ScreenInsets() }

/**
 * Whether a page's query string [search] asks for the app to sit inside the site's television.
 *
 * @param search The query as `location.search` hands it over, with or without the leading question mark.
 */
public fun isTubeEmbed(search: String): Boolean =
  search.removePrefix("?").split('&').any { it == "embed=tv" }

/**
 * Lays [content] out to clear the corners of the site's television, a wide tube's or the tighter ones a phone gets.
 */
@Composable
public fun InsideTube(content: @Composable () -> Unit) {
  val insets = if (isCompactWidth()) TubeCompact else TubeWide
  CompositionLocalProvider(LocalScreenInsets provides insets, content = content)
}

private val TubeWide = ScreenInsets(top = 6.dp, side = 48.dp, bottom = 28.dp, showsSet = false)
private val TubeCompact = ScreenInsets(top = 4.dp, side = 8.dp, bottom = 8.dp, showsSet = false)
