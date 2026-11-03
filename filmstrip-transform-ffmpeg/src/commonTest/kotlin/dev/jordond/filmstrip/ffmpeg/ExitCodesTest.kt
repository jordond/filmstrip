package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.process.PUMP_UNSPAWNABLE
import dev.jordond.filmstrip.ffmpeg.internal.process.ResolvedInput
import dev.jordond.filmstrip.ffmpeg.internal.process.SPAWN_FAILED
import dev.jordond.filmstrip.ffmpeg.internal.process.StderrTail
import dev.jordond.filmstrip.ffmpeg.internal.process.classifyExit
import dev.jordond.filmstrip.ffmpeg.internal.process.isDiskFull
import dev.jordond.filmstrip.ffmpeg.internal.process.unspawnablePump
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.io.IOException
import kotlin.test.Test

class ExitCodesTest {
  // stderr names the two failures a caller can act on, and it wins over whatever the code says.
  @Test
  fun `a full disk is insufficient storage whatever the exit code`() {
    listOf(1, 234, 254).forEach { code ->
      val error = classifyExit(code, "av_interleaved_write_frame(): No space left on device", SINK)

      error.shouldBeInstanceOf<FilmstripError.InsufficientStorage>().requiredBytes shouldBe null
      error.message shouldBe "av_interleaved_write_frame(): No space left on device"
    }
  }

  @Test
  fun `a refused output path names the sink`() {
    val error = classifyExit(1, "$SINK: Permission denied", SINK).shouldBeInstanceOf<FilmstripError.SinkUnwritable>()

    error.sink shouldBe SINK
    error.message shouldBe "$SINK: Permission denied"
  }

  @Test
  fun `a refused input names that source rather than the sink`() {
    listOf("$SOURCE: Permission denied", INPUT_REFUSED_6_1).forEach { stderr ->
      val error = classifyExit(1, stderr, SINK, INPUTS).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      error.source shouldBe SOURCE
      error.message shouldBe stderr
    }
  }

  @Test
  fun `a refused output from 6 point 1 on names the sink`() {
    classifyExit(1, OUTPUT_REFUSED_6_1, SINK, INPUTS).shouldBeInstanceOf<FilmstripError.SinkUnwritable>().sink shouldBe
      SINK
  }

  @Test
  fun `a refusal naming neither side keeps its code`() {
    classifyExit(
      1,
      "/tmp/other: Permission denied",
      SINK,
      INPUTS,
    ).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe
      1
  }

  @Test
  fun `a refused path that only ends with an input's path is not that input`() {
    val error = classifyExit(1, "/elsewhere$SOURCE: Permission denied", SINK, INPUTS)

    error.shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe 1
  }

  @Test
  fun `a full disk is read before a refused path when stderr carries both`() {
    classifyExit(
      1,
      "Permission denied\nNo space left on device",
      SINK,
    ).shouldBeInstanceOf<FilmstripError.InsufficientStorage>()
  }

  @Test
  fun `an invalid argument is an invalid composition`() {
    val error = classifyExit(234, "Error initializing complex filters.", SINK)

    error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe "Error initializing complex filters."
  }

  // Neither spelling says which side was missing, so on its own a missing file is only its code. Up to 6.0 that is 1,
  // and from 6.1 it is AVERROR(ENOENT), which reads back as -2.
  @Test
  fun `a missing file on its own blames neither side`() {
    MISSING_FILE_RUNS.forEach { (code, stderr) ->
      val error = classifyExit(code, stderr, SINK).shouldBeInstanceOf<FilmstripError.Underlying>()

      error.platformCode shouldBe mapOf(1 to 1, 254 to -2).getValue(code)
      error.message shouldBe stderr
    }
  }

  @Test
  fun `a missing file whose input is gone names that source`() {
    MISSING_FILE_RUNS.forEach { (code, stderr) ->
      val error =
        classifyExit(
          code,
          stderr,
          SINK,
          missingSource = SOURCE,
        ).shouldBeInstanceOf<FilmstripError.SourceUnreadable>()

      error.source shouldBe SOURCE
      error.message shouldBe stderr
    }
  }

  @Test
  fun `a missing file whose output directory is gone names the sink`() {
    MISSING_FILE_RUNS.forEach { (code, stderr) ->
      val error =
        classifyExit(
          code,
          stderr,
          SINK,
          sinkParentMissing = true,
        ).shouldBeInstanceOf<FilmstripError.SinkUnwritable>()

      error.sink shouldBe SINK
      error.message shouldBe stderr
    }
  }

  // ffmpeg opens its inputs before its output, so a run missing both stopped at the input.
  @Test
  fun `a gone input is read before a gone output directory`() {
    val error = classifyExit(254, OUTPUT_6_1, SINK, missingSource = SOURCE, sinkParentMissing = true)

    error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().source shouldBe SOURCE
  }

  @Test
  fun `what stderr names wins over what is gone`() {
    classifyExit(
      1,
      "No space left on device",
      SINK,
      missingSource = SOURCE,
      sinkParentMissing = true,
    ).shouldBeInstanceOf<FilmstripError.InsufficientStorage>()
    classifyExit(
      1,
      "$SINK: Permission denied",
      SINK,
      missingSource = SOURCE,
    ).shouldBeInstanceOf<FilmstripError.SinkUnwritable>().sink shouldBe
      SINK
  }

  @Test
  fun `a full disk reads the same on every platform`() {
    isDiskFull("av_interleaved_write_frame(): No space left on device") shouldBe true
    isDiskFull("There is not enough space on the disk") shouldBe true
    isDiskFull("$SINK: No such file or directory") shouldBe false
  }

  @Test
  fun `a process that never started keeps its own code`() {
    classifyExit(
      SPAWN_FAILED,
      "spawn failed",
      SINK,
    ).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe
      SPAWN_FAILED
  }

  @Test
  fun `a preview pump that never started keeps the reason the system gave`() {
    val error = unspawnablePump(IOException(EACCES_SPAWN)).shouldBeInstanceOf<FilmstripError.Underlying>()

    error.platformCode shouldBe FilmstripError.Underlying.NO_PLATFORM_CODE
    error.message shouldBe "$PUMP_UNSPAWNABLE $EACCES_SPAWN"
  }

  @Test
  fun `a preview pump that never started and gave no reason says only that`() {
    unspawnablePump(IOException(" ")).message shouldBe PUMP_UNSPAWNABLE
  }

  @Test
  fun `an unrecognised option keeps its code`() {
    classifyExit(8, "Unrecognized option", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe 8
  }

  // A code above 128 is an AVERROR cut down to a byte, so it reads back as the negative it was.
  @Test
  fun `a truncated averror is recovered as its negative`() {
    classifyExit(129, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe -127
    classifyExit(187, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe -69
    classifyExit(255, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe -1
  }

  @Test
  fun `a code at or below the signal floor passes through`() {
    classifyExit(1, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe 1
    classifyExit(69, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe 69
    classifyExit(128, "x", SINK).shouldBeInstanceOf<FilmstripError.Underlying>().platformCode shouldBe 128
  }

  @Test
  fun `a run that wrote nothing to stderr says so in the message`() {
    classifyExit(1, "", SINK).message shouldBe "ffmpeg exited with 1 and wrote nothing to stderr."
    classifyExit(234, "  \n", SINK).message shouldBe "ffmpeg exited with 234 and wrote nothing to stderr."
  }

  @Test
  fun `a tail keeps only the last lines in order`() {
    val tail = StderrTail(limit = 3)
    (1..7).forEach { tail.accept("line $it") }

    tail.text() shouldBe "line 5\nline 6\nline 7"
  }

  @Test
  fun `a tail skips blank lines`() {
    val tail = StderrTail(limit = 2)
    listOf("first", "", "   ", "second").forEach(tail::accept)

    tail.text() shouldBe "first\nsecond"
  }

  @Test
  fun `a tail that saw nothing is empty`() {
    StderrTail().text() shouldBe ""
  }

  private companion object {
    const val SINK = "/out/export.mp4"
    const val SOURCE = "/clips/in.mp4"
    const val EACCES_SPAWN = "Cannot run program \"/opt/ffmpeg/bin/ffmpeg\": error=13, Permission denied"

    // What ffmpeg 6.0 and every release before it writes for a missing input and for a missing output directory.
    const val INPUT_6_0 = "$SOURCE: No such file or directory"
    const val OUTPUT_6_0 = "$SINK: No such file or directory"

    // What 6.1 and later write for the same two runs.
    const val INPUT_6_1 =
      "[in#0 @ 0x600001e1c000] Error opening input: No such file or directory\n" +
        "Error opening input file $SOURCE.\n" +
        "Error opening input files: No such file or directory"
    const val OUTPUT_6_1 =
      "[out#0/mp4 @ 0x600001e1c000] Error opening output $SINK: No such file or directory\n" +
        "Error opening output file $SINK.\n" +
        "Error opening output files: No such file or directory"

    val INPUTS = listOf(ResolvedInput(SOURCE, SOURCE))

    const val INPUT_REFUSED_6_1 =
      "[in#0 @ 0x600001e1c000] Error opening input: Permission denied\n" +
        "Error opening input file $SOURCE.\n" +
        "Error opening input files: Permission denied"
    const val OUTPUT_REFUSED_6_1 =
      "[out#0/mp4 @ 0x600001e1c000] Error opening output $SINK: Permission denied\n" +
        "Error opening output file $SINK.\n" +
        "Error opening output files: Permission denied"

    val MISSING_FILE_RUNS = listOf(1 to INPUT_6_0, 1 to OUTPUT_6_0, 254 to INPUT_6_1, 254 to OUTPUT_6_1)
  }
}
