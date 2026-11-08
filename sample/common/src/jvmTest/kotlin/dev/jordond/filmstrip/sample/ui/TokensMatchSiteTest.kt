package dev.jordond.filmstrip.sample.ui

import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.toArgb
import io.kotest.matchers.collections.shouldContainExactlyInAnyOrder
import io.kotest.matchers.shouldBe
import java.io.File
import kotlin.test.Test

class TokensMatchSiteTest {
  private val root = File(System.getProperty("filmstrip.rootDir"))
  private val css = File(root, "website/src/styles/tokens.css").readText()
  private val crt = File(root, "website/src/styles/crt.css").readText()

  private val tokensByName: Map<String, Color> =
    mapOf(
      "ground" to Tokens.ground,
      "ground-deep" to Tokens.groundDeep,
      "screen" to Tokens.screen,
      "panel" to Tokens.panel,
      "panel-raised" to Tokens.panelRaised,
      "bezel" to Tokens.bezel,
      "knob" to Tokens.knob,
      "line" to Tokens.line,
      "line-strong" to Tokens.lineStrong,
      "text" to Tokens.text,
      "muted" to Tokens.muted,
      "muted-strong" to Tokens.mutedStrong,
      "amber" to Tokens.amber,
      "amber-hover" to Tokens.amberHover,
      "red" to Tokens.red,
      "cyan" to Tokens.cyan,
      "pink" to Tokens.pink,
      "coral" to Tokens.coral,
      "title-cream" to Tokens.titleCream,
      "vcr-blue" to Tokens.vcrBlue,
      "vcr-border" to Tokens.vcrBorder,
      "vcr-header" to Tokens.vcrHeader,
      "white" to Tokens.white,
      "backdrop" to Tokens.backdrop,
      "fringe-pink" to Tokens.fringePink,
      "fringe-cyan" to Tokens.fringeCyan,
      "scene-sky-top" to Tokens.sceneSkyTop,
      "scene-sky-middle" to Tokens.sceneSkyMiddle,
      "scene-sky-low" to Tokens.sceneSkyLow,
      "scene-sky-horizon" to Tokens.sceneSkyHorizon,
      "scene-sun" to Tokens.sceneSun,
      "scene-sun-stripe" to Tokens.sceneSunStripe,
      "scene-sea" to Tokens.sceneSea,
      "scene-palm" to Tokens.scenePalm,
      "track-tick" to Tokens.trackTick,
      "track-ground" to Tokens.trackGround,
      "track-hatch" to Tokens.trackHatch,
      "bar-grey" to Tokens.barGrey,
      "bar-yellow" to Tokens.barYellow,
      "bar-cyan" to Tokens.barCyan,
      "bar-green" to Tokens.barGreen,
      "bar-magenta" to Tokens.barMagenta,
      "bar-red" to Tokens.barRed,
      "bar-blue" to Tokens.barBlue,
      "bar-black" to Tokens.barBlack,
      "bar-navy" to Tokens.barNavy,
      "bar-violet" to Tokens.barViolet,
    )

  @Test
  fun `every colour on the site has a token of the same value`() {
    val site = cssColours()

    tokensByName.keys shouldContainExactlyInAnyOrder site.keys
    tokensByName.mapValues { (_, colour) -> colour.argbHex() } shouldBe site.mapValues { (_, argb) -> argb.hex() }
  }

  @Test
  fun `the small fringe matches the site`() {
    val shadows = COLOUR.findAll(propertyBody("fringe-small")).map { parseColour(it.value).hex() }.toList()

    shadows shouldBe listOf(Tokens.fringeSmallPink.argbHex(), Tokens.fringeSmallCyan.argbHex())
  }

  @Test
  fun `the faint scanlines match the site`() {
    val line = COLOUR.find(propertyBody("scanlines-faint"))!!.value

    parseColour(line).hex() shouldBe Tokens.scanlinesFaint.argbHex()
  }

  @Test
  fun `the tube scanlines match the site`() {
    val dark = COLOUR.findAll(ruleBody(".scan::after")).map { parseColour(it.value) }.filter { it ushr 24 != 0 }

    dark.map { it.hex() }.toList() shouldBe listOf(Tokens.scanlinesTube.argbHex())
  }

  @Test
  fun `the vignette matches the site`() {
    val edge = COLOUR.find(ruleBody(".vignette"))!!.value

    parseColour(edge).hex() shouldBe Tokens.vignette.argbHex()
  }

  @Test
  fun `the web page background is ground`() {
    listOf("sample/webApp", "sample/tryIt").forEach { app ->
      val html = File(root, "$app/src/wasmJsMain/resources/index.html").readText()
      val background = Regex("""background:\s*(#[0-9a-fA-F]{6})""").find(html)!!.groupValues[1]

      parseColour(background).hex() shouldBe Tokens.ground.argbHex()
    }
  }

  private fun cssColours(): Map<String, Int> =
    DECLARATION
      .findAll(css)
      .filter { COLOUR.matchEntire(it.groupValues[2].trim()) != null }
      .associate { it.groupValues[1] to parseColour(it.groupValues[2].trim()) }

  private fun propertyBody(name: String): String =
    DECLARATION.findAll(css).first { it.groupValues[1] == name }.groupValues[2]

  private fun ruleBody(selector: String): String =
    Regex(Regex.escape(selector) + """\s*\{([^}]*)\}""").find(crt)!!.groupValues[1]

  private fun parseColour(value: String): Int {
    if (value.startsWith("#")) return (0xFF000000 or value.drop(1).toLong(16)).toInt()
    val parts =
      value
        .substringAfter('(')
        .substringBefore(')')
        .split(',')
        .map { it.trim() }
    val alpha = (parts[3].toFloat() * 255 + 0.5f).toInt()
    return (alpha shl 24) or (parts[0].toInt() shl 16) or (parts[1].toInt() shl 8) or parts[2].toInt()
  }

  private fun Color.argbHex(): String = toArgb().hex()

  private fun Int.hex(): String = toUInt().toString(16).padStart(8, '0')

  private companion object {
    val DECLARATION = Regex("""--([a-z0-9-]+):\s*([^;]+);""")
    val COLOUR = Regex("""#[0-9a-fA-F]{6}|rgba\([^)]*\)""")
  }
}
