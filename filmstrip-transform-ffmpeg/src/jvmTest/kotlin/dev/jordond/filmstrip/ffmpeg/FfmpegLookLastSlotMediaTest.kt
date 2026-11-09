package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.effects.filters.film.Film
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.render
import dev.jordond.filmstrip.ffmpeg.internal.lowering.trailingNodes
import dev.jordond.filmstrip.ffmpeg.support.BYTE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.PLANES_BYTES_PER_TWO_PIXELS
import dev.jordond.filmstrip.ffmpeg.support.formPlanes
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.assertions.withClue
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import kotlinx.coroutines.runBlocking
import java.io.File
import kotlin.test.Test

/**
 * Reads the last slot of an SD clip carrying a [Film] on its live grain plane, whose `lut2` ends with the clip's last
 * frame.
 *
 * Each source frame is one flat grey a step above the one before it, so the luma a slot reads says which frame it was.
 * One source keeps Matroska's millisecond timebase and one carries a timebase of a whole source frame, coarser than a
 * slot at 30 and 60 fps. The clip's tail is also run on its own behind a `lut2` whose frames sit on that coarse
 * timebase, since the composition's graph moves them onto a finer one before the tail reads them.
 */
class FfmpegLookLastSlotMediaTest {
  private val draw = FilterFrames()

  @Test
  fun `a film on an sd clip shows the source's last frame in its last slot at every rate`() =
    runBlocking<Unit> { assertLastSlots(steppedSdClip("mkv")) }

  @Test
  fun `a film on a clip whose timebase is coarser than a slot shows the source's last frame in its last slot`() =
    runBlocking<Unit> { assertLastSlots(steppedSdClip("mov", "-video_track_timescale", SOURCE_RATE.toString())) }

  @Test
  fun `a clip's tail behind a lut2 on a timebase coarser than a slot writes the last frame in its last slot`() {
    for (rate in RATES) {
      val coarse = "settb=1/$SOURCE_RATE"
      val graph =
        "[0:v]format=yuv420p,geq=lum=$LUMA_BLACK+N*$STEP:cb=$CHROMA_MID:cr=$CHROMA_MID,$coarse[src];" +
          "color=c=black:s=${SIZE}x$SIZE:r=$SOURCE_RATE,format=yuv420p,$coarse[map];" +
          "[src][map]lut2=shortest=1:c0=x:c1=x:c2=x," +
          trailingNodes(rate, "yuv420p", rate.toLong()).render() + "[out]"
      val read =
        ProcessBuilder(
          hostFfmpeg,
          "-hide_banner",
          "-loglevel",
          "error",
          "-f",
          "lavfi",
          "-i",
          "color=s=${SIZE}x$SIZE:r=$SOURCE_RATE:d=1",
          "-filter_complex",
          graph,
          "-map",
          "[out]",
          "-f",
          "rawvideo",
          "-",
        ).redirectError(ProcessBuilder.Redirect.INHERIT).start()
      val planes = read.inputStream.readBytes()
      read.waitFor() shouldBe 0

      withClue("the last of $rate slots") {
        planes.size shouldBe SIZE * SIZE * PLANES_BYTES_PER_TWO_PIXELS / 2 * rate
        planes.lastLumaMean(rate) shouldBe (LAST_LUMA plusOrMinus STEP / 2.0)
      }
    }
  }

  private suspend fun assertLastSlots(clip: Clip) {
    val composition = draw.composition(listOf(clip))

    for (rate in RATES) {
      val spec = ExportSpec(frameRate = rate)
      val (_, planned) =
        draw.engine.runs
          .lowered(composition, spec, null)
          .graph
          .shouldNotBeNull()
      planned.liveForm.shouldNotBeNull().filterGraph shouldContain "lut2=shortest=1"

      val planes = draw.formPlanes(composition, rate, live = true, spec = spec)
      withClue("the last of $rate slots") { planes.lastLumaMean(rate) shouldBe (LAST_LUMA plusOrMinus STEP / 2.0) }
    }
  }

  private fun steppedSdClip(
    container: String,
    vararg muxing: String,
  ): Clip {
    val video = File.createTempFile("look-last-slot", ".$container").apply { deleteOnExit() }
    val source =
      "color=c=gray:s=${SIZE}x$SIZE:r=$SOURCE_RATE:d=1,format=yuv420p," +
        "geq=lum=$LUMA_BLACK+N*$STEP:cb=$CHROMA_MID:cr=$CHROMA_MID,setparams=$TAGS_170M"
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
        source,
        "-colorspace",
        "smpte170m",
        "-color_primaries",
        "smpte170m",
        "-color_trc",
        "smpte170m",
        "-c:v",
        "ffv1",
        *muxing,
        video.path,
      ).inheritIO().start()
    encode.waitFor() shouldBe 0
    return Clip(MediaSource.of(video.path), effects = listOf(Film(halation = 0f)))
  }

  private fun ByteArray.lastLumaMean(count: Int): Double {
    val frame = size / count
    val luma = frame * 2 / PLANES_BYTES_PER_TWO_PIXELS
    val start = frame * (count - 1)
    var sum = 0.0
    for (at in start until start + luma) sum += (this[at].toInt() and BYTE).toDouble()
    return sum / luma
  }

  private companion object {
    const val SIZE = 64
    const val SOURCE_RATE = 25
    const val SOURCE_FRAMES = 25
    const val LUMA_BLACK = 16
    const val CHROMA_MID = 128
    const val STEP = 8
    val LAST_LUMA = (LUMA_BLACK + (SOURCE_FRAMES - 1) * STEP).toDouble()
    const val TAGS_170M = "colorspace=smpte170m:color_primaries=smpte170m:color_trc=smpte170m"
    val RATES = listOf(24, 25, 30, 60)
  }
}
