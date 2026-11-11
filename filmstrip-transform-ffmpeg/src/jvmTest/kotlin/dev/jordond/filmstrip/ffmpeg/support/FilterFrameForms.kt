package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PREVIEW_PIXEL_FORMAT
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewArguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewFrameBytes
import dev.jordond.filmstrip.ffmpeg.internal.lowering.previewSeek
import dev.jordond.filmstrip.ffmpeg.internal.lowering.readingGraphFrom
import dev.jordond.filmstrip.ffmpeg.internal.process.ChosenForm
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.chooseForm
import dev.jordond.filmstrip.ffmpeg.internal.process.resolve
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.time.Duration

/**
 * The first [count] frames the graph [frames] runs writes, as RGBA, each frame's command handed through [rewrite].
 */
internal suspend fun FilterFrames.rawFrames(
  composition: Composition,
  count: Int,
  rewrite: (List<String>) -> List<String> = { it },
): ByteArray {
  val (toolchain, invocation) =
    engine.runs
      .lowered(composition, ExportSpec(), null)
      .graph
      .shouldNotBeNull()
  val scratch = Scratch.create(engine.scratchRoot)
  try {
    val command =
      invocation
        .previewArguments(
          toolchain,
          engine.runtime.config,
          invocation.inputs.map { scratch.resolve(it.source).path },
          invocation.sidecars.map { scratch.write(it) },
          Duration.ZERO,
        ).let(rewrite)
        .readingGraphFrom(scratch)
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val bytes = invocation.previewFrameBytes * count
    val read = process.inputStream.readNBytes(bytes)
    process.destroy()
    read.size shouldBe bytes
    return read
  } finally {
    scratch.delete()
  }
}

/**
 * Reads the first [frames] frames of [plan] from [at] in both forms and fails on any sample the live planes draw
 * differently from the drawn graph. The first and last frames have to differ, so a look that never moves fails too.
 */
internal suspend fun FilterFrames.assertFormsAgree(
  plan: Composition,
  frames: Int,
  at: Duration = Duration.ZERO,
) {
  val drawn = formPlanes(plan, frames, live = false, at = at)
  val planes = formPlanes(plan, frames, live = true, at = at)
  val differing = drawn.indices.count { drawn[it] != planes[it] }
  withClue("$differing of ${drawn.size} samples differ") { differing shouldBe 0 }
  val frameBytes = drawn.size / frames
  drawn.copyOfRange(0, frameBytes).contentEquals(drawn.copyOfRange(drawn.size - frameBytes, drawn.size)) shouldBe false
}

/**
 * The first [count] frames of [composition] from [at] as 8 bit limited range BT.709 planes, read from the form [live]
 * names, the drawn graph or the one on live planes, with the look's tick clock swapped for [clock] when it is given.
 */
internal suspend fun FilterFrames.formPlanes(
  composition: Composition,
  count: Int,
  live: Boolean,
  at: Duration = Duration.ZERO,
  spec: ExportSpec = ExportSpec(),
  clock: String? = null,
): ByteArray {
  val (toolchain, planned) =
    engine.runs
      .lowered(composition, spec, null)
      .graph
      .shouldNotBeNull()
  val chosen =
    if (live) {
      chooseForm(planned, planned.previewSeek(at), engine.runtime.bindFeeds).also { it.feeds.shouldNotBeNull() }
    } else {
      ChosenForm(planned, null, null)
    }
  val invocation = chosen.invocation
  val scratch = Scratch.create(engine.scratchRoot)
  try {
    val command =
      invocation
        .previewArguments(
          toolchain,
          engine.runtime.config,
          invocation.inputs.map { scratch.resolve(it.source, chosen.feeds).path },
          invocation.sidecars.map { scratch.write(it) },
          at,
        ).flatMap { if (it == PREVIEW_PIXEL_FORMAT) PLANES_ARGUMENTS else listOf(it) }
        .also { arguments -> if (clock != null) arguments.any(LOOK_TICK_CLOCK::containsMatchIn) shouldBe true }
        .map { if (clock == null) it else it.replace(LOOK_TICK_CLOCK, clock) }
        .readingGraphFrom(scratch)
    chosen.feeds?.start()
    val size = invocation.output.size
    val bytes = size.width * size.height * PLANES_BYTES_PER_TWO_PIXELS / 2 * count
    val process = ProcessBuilder(command).redirectError(ProcessBuilder.Redirect.DISCARD).start()
    val read = process.inputStream.readNBytes(bytes)
    process.destroy()
    read.size shouldBe bytes
    return read
  } finally {
    chosen.feeds?.close()
    scratch.delete()
  }
}

/**
 * The first [count] frames of a look's [composition] with each frame's tick written as the expression [tick] of `N`.
 *
 * The drawn graph runs with [tick] in place of its clock, so its bytes are the look's own at the tick a frame's exact
 * time gives. Those bytes come back for comparing against what the look draws from its own clock.
 */
internal suspend fun FilterFrames.planesAtExactTicks(
  composition: Composition,
  count: Int,
  tick: String,
): ByteArray = formPlanes(composition, count, live = false, clock = tick)

// A look's clock in a `geq`, the frame's time rounded to a whole microsecond and read at the look's own rate.
private val LOOK_TICK_CLOCK = Regex("""floor\(\(floor\(T\*[^)]*\)[^)]*\)[^)]*\)""")

/**
 * The command [frames] runs for [composition].
 */
internal suspend fun FilterFrames.commandOf(composition: Composition): List<String> {
  val (toolchain, invocation) =
    engine.runs
      .lowered(composition, ExportSpec(), null)
      .graph
      .shouldNotBeNull()
  return invocation.previewArguments(
    toolchain,
    engine.runtime.config,
    invocation.inputs.map { it.source.toString() },
    invocation.sidecars.map { it.extension },
    Duration.ZERO,
  )
}
