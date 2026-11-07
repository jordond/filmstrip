package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.unit.dp

/**
 * Every colour and size the sample draws with. A colour is named as in the site's `tokens.css`, or after the rule it
 * comes from in `crt.css`.
 *
 * Material roles in [SampleTheme] and anything without a role read from here, so a value exists once. A test holds
 * the colours against those two files. Sizes have no counterpart on the site.
 */
public object Tokens {
  public val ground: Color = Color(0xFF0E0C12)
  public val groundDeep: Color = Color(0xFF0A090C)
  public val screen: Color = Color(0xFF07060A)
  public val panel: Color = Color(0xFF121016)
  public val panelRaised: Color = Color(0xFF17141C)
  public val bezel: Color = Color(0xFF1D1A22)
  public val knob: Color = Color(0xFF2A2630)
  public val line: Color = Color(0xFF2C2833)
  public val lineStrong: Color = Color(0xFF3A3542)
  public val text: Color = Color(0xFFF2EDE4)
  public val muted: Color = Color(0xFFA39CAE)
  public val mutedStrong: Color = Color(0xFFC9C2D3)
  public val amber: Color = Color(0xFFFFB627)
  public val amberHover: Color = Color(0xFFFFD27A)
  public val red: Color = Color(0xFFFF4F3F)
  public val cyan: Color = Color(0xFF38E1FF)
  public val pink: Color = Color(0xFFFF7BA0)
  public val coral: Color = Color(0xFFFF9A8A)
  public val titleCream: Color = Color(0xFFFFF6E0)
  public val vcrBlue: Color = Color(0xFF1C2BB8)
  public val vcrBorder: Color = Color(0xFF0A1170)
  public val vcrHeader: Color = Color(0xFFFFE14D)
  public val white: Color = Color(0xFFFFFFFF)
  public val backdrop: Color = Color(red = 7, green = 6, blue = 10, alpha = 204)
  public val fringePink: Color = Color(red = 255, green = 42, blue = 109, alpha = 217)
  public val fringeCyan: Color = Color(red = 56, green = 225, blue = 255, alpha = 217)
  public val fringeSmallPink: Color = Color(red = 255, green = 42, blue = 109, alpha = 204)
  public val fringeSmallCyan: Color = Color(red = 56, green = 225, blue = 255, alpha = 204)
  public val scanlinesFaint: Color = Color(red = 255, green = 255, blue = 255, alpha = 7)
  public val scanlinesTube: Color = Color(red = 0, green = 0, blue = 0, alpha = 71)
  public val vignette: Color = Color(red = 0, green = 0, blue = 0, alpha = 217)

  public val sceneSkyTop: Color = Color(0xFF2B1A5C)
  public val sceneSkyMiddle: Color = Color(0xFF8A2F6B)
  public val sceneSkyLow: Color = Color(0xFFFF7B4A)
  public val sceneSkyHorizon: Color = Color(0xFFFFC46B)
  public val sceneSun: Color = Color(0xFFFFB24A)
  public val sceneSunStripe: Color = Color(0xFFFFE27A)
  public val sceneSea: Color = Color(0xFF3A1D5E)
  public val scenePalm: Color = Color(0xFF14081F)
  public val trackTick: Color = Color(0xFF6D6577)
  public val trackGround: Color = Color(0xFF1A1720)
  public val trackHatch: Color = Color(0xFF221E29)

  public val barGrey: Color = Color(0xFFC0C0C0)
  public val barYellow: Color = Color(0xFFC0C000)
  public val barCyan: Color = Color(0xFF00C0C0)
  public val barGreen: Color = Color(0xFF00C000)
  public val barMagenta: Color = Color(0xFFC000C0)
  public val barRed: Color = Color(0xFFC00000)
  public val barBlue: Color = Color(0xFF0000C0)
  public val barBlack: Color = Color(0xFF131313)
  public val barNavy: Color = Color(0xFF00214C)
  public val barViolet: Color = Color(0xFF32006A)

  public val sizeTouch = 44.dp
  public val sizeKey = 48.dp
  public val sizeKeyLoad = 64.dp
  public val sizeChip = 36.dp
  public val sizeLed = 8.dp
  public val sizeKnob = 30.dp
  public val sizeKnobLarge = 34.dp
  public val sizeRail = 88.dp
  public val sizeInspector = 340.dp
  public val sizeTransport = 56.dp
}
