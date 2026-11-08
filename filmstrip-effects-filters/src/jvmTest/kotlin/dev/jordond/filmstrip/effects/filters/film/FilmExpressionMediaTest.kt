package dev.jordond.filmstrip.effects.filters.film

import dev.jordond.filmstrip.effects.filters.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import java.io.ByteArrayOutputStream
import kotlin.math.roundToInt
import kotlin.test.Test

/**
 * Runs [FilmExpressions.grainPlane] through ffmpeg's own `geq` and reads it back against [FilmSignal.grainAt].
 *
 * The ffmpeg is [hostFfmpeg], the one the build declares as this suite's input.
 */
class FilmExpressionMediaTest {
  @Test
  fun `the geq grain equals grainAt on a mid-range grid`() {
    val span = FilmSignal.grainCellSpan(WIDTH, HEIGHT)

    val plane = plane(FilmExpressions.grainPlane(span, "$TICK"))

    for (y in 0 until HEIGHT) {
      for (x in 0 until WIDTH) {
        val expected = MID + REACH * FilmSignal.grainAt(FilmSignal.grainCell(x, y, WIDTH, HEIGHT), TICK)
        withClue("x $x y $y") {
          (plane[y * WIDTH + x].toInt() and 0xFF) shouldBe expected.roundToInt()
        }
      }
    }
  }

  @Test
  fun `a grain plane equals the geq's at a mid-range tick on every raster`() {
    listOf(
      Triple(1920, 1080, Size(1920, 1080)),
      Triple(1920, 1080, Size(3840, 2160)),
      Triple(1280, 720, Size(1280, 720)),
    ).forEach { (width, height, frame) ->
      val input =
        filmFragment(Film(), frame)
          .live
          .shouldNotBeNull()
          .inputs
          .single()
      withClue("the raster of a $frame frame") {
        input.width shouldBe width
        input.height shouldBe height
      }
      listOf(TICK, TICK + 1, 0).forEach { tick ->
        val span = FilmSignal.grainCellSpan(width, height)
        val drawn = plane(FilmExpressions.grainPlane(span, "$tick"), width, height)
        withClue("tick $tick at ${width}x$height") { input.planeAt(tick).contentEquals(drawn) shouldBe true }
      }
    }
  }

  @Test
  fun `a film with no grain has no live form and a grain frames lut2 ends with the frame`() {
    filmFragment(Film(grain = 0f, halation = 0.5f), Size(320, 180)).live.shouldBeNull()
    val live = filmFragment(Film(), Size(320, 180)).live.shouldNotBeNull()
    live.branches
      .last()
      .nodes
      .single()
      .arguments
      .first()
      .key shouldBe "shortest"
  }

  private fun plane(
    expression: String,
    width: Int = WIDTH,
    height: Int = HEIGHT,
  ): ByteArray {
    val process =
      ProcessBuilder(
        hostFfmpeg,
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "color=c=black:s=${width}x$height:d=0.04",
        "-frames:v",
        "1",
        "-vf",
        "format=gray,geq=lum='$expression'",
        "-f",
        "rawvideo",
        "-pix_fmt",
        "gray",
        "-",
      ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
    val out = ByteArrayOutputStream()
    process.inputStream.copyTo(out)
    process.waitFor() shouldBe 0
    return out.toByteArray()
  }

  private companion object {
    const val WIDTH = 96
    const val HEIGHT = 54
    const val TICK = 3596
    const val MID = 128.0
    const val REACH = 127.0
  }
}
