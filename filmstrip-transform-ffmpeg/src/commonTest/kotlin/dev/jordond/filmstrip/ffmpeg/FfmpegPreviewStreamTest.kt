package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.ffmpeg.internal.lowering.PreviewFrames
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.process.FrameStream
import dev.jordond.filmstrip.ffmpeg.internal.process.ResolvedInput
import dev.jordond.filmstrip.ffmpeg.internal.process.Scratch
import dev.jordond.filmstrip.ffmpeg.internal.process.classifyExit
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration

/**
 * The pump's side of a preview, against a child that hands over numbered one byte frames.
 *
 * The graph renders the frames the split takes from it and the pump holds the last of them for the rest, so what
 * reaches a caller is the rendered run followed by that last frame again.
 */
class FfmpegPreviewStreamTest {
  @Test
  fun `holds the last rendered frame once the graph has rendered them all`() =
    runTest {
      val stream = streamOf(Numbered(RENDERED), PreviewFrames(rendered = RENDERED.toLong(), held = HELD.toLong()))

      drain(stream) shouldBe (1..RENDERED).toList() + List(HELD) { RENDERED }
    }

  // A child that dies short of the frames it was asked for has no last frame to hold, and a held frame would hide that
  // it died.
  @Test
  fun `ends with a child that renders short of the split`() =
    runTest {
      val stream = streamOf(Numbered(RENDERED - 1), PreviewFrames(rendered = RENDERED.toLong(), held = HELD.toLong()))

      drain(stream) shouldBe (1 until RENDERED).toList()
    }

  @Test
  fun `hands every held frame over as bytes of its own`() =
    runTest {
      val stream = streamOf(Numbered(1), PreviewFrames(rendered = 1, held = 2))
      stream.next().shouldNotBeNull().fill(0)
      val first = stream.next().shouldNotBeNull()
      first.single().toInt() shouldBe 1
      first.fill(0)

      stream.next()?.single()?.toInt() shouldBe 1
      stream.close()
    }

  @Test
  fun `reads a graph with nothing to hold to its own end`() =
    runTest {
      val stream = streamOf(Numbered(RENDERED), PreviewFrames(rendered = null, held = 0))

      drain(stream) shouldBe (1..RENDERED).toList()
    }

  // Each exit names a different input, so a stream classifying against anything but its own inputs fails here.
  @Test
  fun `classifies a failed exit the way an export does`() =
    runTest {
      val exits =
        listOf(
          NO_SUCH_FILE to FilmstripError.SourceUnreadable("scripted.mp4", NO_SUCH_FILE),
          INPUT_REFUSED to FilmstripError.SourceUnreadable("refused.mp4", INPUT_REFUSED),
        )

      exits.forEach { (stderr, expected) ->
        val stream = streamOf(Numbered(0, stderr), PreviewFrames(rendered = null, held = 0), listOf(GONE, REFUSED))

        val error = stream.exitError(FAILED)
        stream.close()

        error shouldBe classifyExit(FAILED, stderr, SINK, listOf(GONE, REFUSED), missingSource = GONE.source)
        error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>() shouldBe expected
      }
    }

  private fun streamOf(
    frames: FrameStream,
    split: PreviewFrames,
    inputs: List<ResolvedInput> = emptyList(),
  ): FfmpegPreviewStream =
    FfmpegPreviewStream(
      frames,
      Scratch.create(),
      Size(1, 1),
      Duration.ZERO,
      inputs = inputs,
      sink = SINK,
      frameBytes = 1,
      split = split,
    )

  private suspend fun drain(stream: FfmpegPreviewStream): List<Int> =
    try {
      buildList { while (true) add(stream.next()?.single()?.toInt() ?: break) }
    } finally {
      stream.close()
    }

  // Writes frames numbered from one up to [count], then ends.
  private class Numbered(
    private val count: Int,
    private val stderr: String = "",
  ) : FrameStream {
    private var written = 0

    override val processId: Long? = null
    override val isAlive: Boolean get() = written < count

    override suspend fun read(frame: ByteArray): Boolean {
      if (written == count) return false
      written++
      frame.fill(written.toByte())
      return true
    }

    override suspend fun exitStatus(): Int? = 0

    override fun errors(): String = stderr

    override fun close() = Unit
  }

  private companion object {
    const val RENDERED = 4
    const val HELD = 3
    const val FAILED = 1
    const val SINK = "pipe:1"
    const val NO_SUCH_FILE = "/filmstrip/missing/scripted.mp4: No such file or directory"
    const val INPUT_REFUSED =
      "[in#0 @ 0x600001e1c000] Error opening input: Permission denied\n" +
        "Error opening input file /filmstrip/refused/scripted.mp4."

    val GONE = ResolvedInput(path = "/filmstrip/missing/scripted.mp4", source = "scripted.mp4")
    val REFUSED = ResolvedInput(path = "/filmstrip/refused/scripted.mp4", source = "refused.mp4")
  }
}
