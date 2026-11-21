package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.filters.glitch.Glitch
import dev.jordond.filmstrip.effects.filters.glitch.GlitchBlocks
import dev.jordond.filmstrip.effects.filters.glitch.GlitchParams
import dev.jordond.filmstrip.effects.filters.glitch.GlitchSignal
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FULL
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.channelAt
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.ffmpeg.support.lumaCode
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.shouldBeGreaterThan
import io.kotest.matchers.doubles.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.math.abs
import kotlin.math.max
import kotlin.test.Test
import kotlin.time.DurationUnit

/**
 * Reads the blocks a [Glitch] with [Glitch.blocks] breaks on ffmpeg against [GlitchBlocks] and [GlitchSignal], off
 * the preview pump with no encoder in the way.
 *
 * The picture is a grey grid, so a block read from another row or column reads another code and the plain read's
 * colour is exact. Luma is read, since 4:2:0 shares one chroma sample across a block's edge, which is ffmpeg's limit.
 */
class FfmpegGlitchBlocksMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `broken blocks land on the signal in luma`() = runBlocking<Unit> { assertBlocksLand(ExportSpec(), WIDTH, HEIGHT) }

  @Test
  fun `a mid preview height breaks blocks at its own size`() =
    runBlocking<Unit> { assertBlocksLand(ExportSpec(heightPx = MID_HEIGHT), MID_HEIGHT * WIDTH / HEIGHT, MID_HEIGHT) }

  @Test
  fun `a glitch with no blocks lowers to the tear alone`() =
    runBlocking<Unit> {
      val clip = gridClip()
      val graph = draw.graphOf(draw.composition(listOf(clip), listOf(Glitch(BLOCKED.intensity, seed = BLOCKED.seed))))
      val broken = draw.graphOf(draw.composition(listOf(clip), listOf(BLOCKED)))

      Regex("geq=").findAll(broken).count() shouldBe Regex("geq=").findAll(graph).count() + 1
    }

  // Every pixel on a stride against the signal over the same grid drawn plain, read where GlitchBlocks sends it. The
  // same reads with the blocks left whole must land well off somewhere.
  private suspend fun assertBlocksLand(
    spec: ExportSpec,
    width: Int,
    height: Int,
  ) {
    val clip = gridClip()
    val frame = draw.planesAt(draw.composition(listOf(clip), listOf(BLOCKED)), READ_SLOT, spec)
    val plain = draw.frames(draw.composition(listOf(clip)), READ_TIME, 1, spec).single()
    frame.width shouldBe width
    val params = GlitchParams.of(BLOCKED)
    var worst = 0.0
    var wholeGap = 0.0
    for (y in 0 until height step STRIDE) {
      val row = GlitchSignal.virtualRow(y, height)
      val whole = { column: Int -> DoubleArray(RGB) { plain.channelAt(width, column, y, it) / FULL } }
      val broken = { column: Int ->
        val (x, from) = GlitchBlocks.sourceOf(column, y, width, height, BLOCKED.seed, TICK, params)
        DoubleArray(RGB) { plain.channelAt(width, x, from, it) / FULL }
      }
      for (column in 0 until width step STRIDE) {
        val x = (column + 0.5) / width
        val expected = lumaCode(GlitchSignal.pixelAt(x, row, TICK, BLOCKED.seed, width, params, broken))
        val unbroken = lumaCode(GlitchSignal.pixelAt(x, row, TICK, BLOCKED.seed, width, params, whole))
        worst = max(worst, abs(frame.luma(column, y) - expected))
        wholeGap = max(wholeGap, abs(unbroken - expected))
      }
    }
    withClue("blocks left whole would read $wholeGap codes off") { wholeGap shouldBeGreaterThan 2 * ROUND_TRIP_CODES }
    withClue("worst $worst") { worst shouldBeLessThan ROUND_TRIP_CODES }
  }

  // Grey steps across and down the picture.
  private fun gridClip(): Clip {
    val still = File.createTempFile("glitch-grid", ".mkv").apply { deleteOnExit() }
    val encode =
      ProcessBuilder(
        hostFfmpeg,
        "-y",
        "-hide_banner",
        "-loglevel",
        "error",
        "-f",
        "lavfi",
        "-i",
        "nullsrc=s=${WIDTH}x$HEIGHT:r=$FILTER_RATE:d=2," +
          "geq=lum='$GRID_BASE+mod(X\\,$GRID_PX)*$GRID_STEP+mod(Y\\,$GRID_PX)*$GRID_STEP':cb=128:cr=128",
        "-c:v",
        "ffv1",
        "-pix_fmt",
        "yuv420p",
        still.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return Clip(MediaSource.of(still.path))
  }

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360

    // A preview height whose blocks round to their own size, 10 pixels where the export's are 13.
    const val MID_HEIGHT = 270
    const val RGB = 3
    const val READ_SLOT = 30
    const val STRIDE = 3
    const val GRID_PX = 24
    const val GRID_BASE = 32
    const val GRID_STEP = 3
    const val SEED_SEARCH = 100_000

    // Going through gbrap and back rounds twice, and the preview's RGBA rounds once more, which is ffmpeg's limit.
    const val ROUND_TRIP_CODES = 3.0
    val READ_TIME = outputSlotTime(READ_SLOT.toLong(), FILTER_RATE)
    val TICK = GlitchSignal.tickAt(READ_TIME.toDouble(DurationUnit.SECONDS))

    // The first seed whose burst at the read is rising and breaks at least two rows of blocks on the frame.
    val BLOCKED =
      (1..SEED_SEARCH).asSequence().map { Glitch(0.5f, blocks = 1f, seed = it) }.first { glitch ->
        val params = GlitchParams.of(glitch)
        val now = GlitchSignal.burstAt(glitch.seed, TICK, params)?.strength ?: 0.0
        val next = GlitchSignal.burstAt(glitch.seed, TICK + 1, params)?.strength ?: 0.0
        val side = GlitchBlocks.sidePx(HEIGHT)
        val columns = GlitchBlocks.columnsOf(WIDTH, HEIGHT)
        now > 0.0 && next > now &&
          (0 until HEIGHT / side).count { blockRow ->
            GlitchBlocks.runAt(glitch.seed, TICK, blockRow, columns, now, params) != null
          } >= 2
      }
  }
}
