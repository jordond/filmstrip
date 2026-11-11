package dev.jordond.filmstrip.player

import dev.jordond.filmstrip.player.contract.ContractRecorder
import dev.jordond.filmstrip.player.contract.awaitComposition
import dev.jordond.filmstrip.player.contract.awaitContract
import dev.jordond.filmstrip.player.contract.awaitStep
import dev.jordond.filmstrip.player.contract.contractTest
import dev.jordond.filmstrip.player.contract.withEngine
import dev.jordond.filmstrip.player.internal.BrowserPlayerEngine
import dev.jordond.filmstrip.webcodecs.internal.preview.BrowserPreview
import dev.jordond.filmstrip.webcodecs.internal.preview.PreviewFrame
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.kotest.matchers.types.shouldNotBeSameInstanceAs
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration

/**
 * How the browser engine gets a shuttle's frames to the surface.
 *
 * Nothing is presented on this backend, the surface reads each frame back on its own tick. The engine draws a
 * shuttle's frames itself so the shuttle runs at the rate the page draws at, and the surface is handed the frame
 * already drawn.
 */
class WebShuttleTest {
  @Test
  fun `a readback on the shuttle's playhead is handed the frame the shuttle drew`() =
    shuttleTest { engine, recorder, drawn ->
      engine.shuttleToTheEnd(recorder)
      val playhead = recorder.playhead ?: fail("The engine reported no playhead.")

      val read =
        engine.readback
          .frameAt(playhead)
          .shouldBeInstanceOf<ReadbackResult.Success>()
          .frame

      withClue("the frame read back at $playhead, with frames drawn at ${drawn.frames.keys}") {
        drawn.frames.keys shouldContain playhead
        read.presentationTime shouldBe playhead
        read.pixels shouldBeSameInstanceAs drawn.frames.getValue(playhead).pixels
      }
    }

  @Test
  fun `a readback away from the shuttle's playhead is drawn for it`() =
    shuttleTest { engine, recorder, drawn ->
      engine.shuttleToTheEnd(recorder)
      val playhead = recorder.playhead ?: fail("The engine reported no playhead.")
      val earlier = drawn.frames.keys.first { it != playhead }

      val read =
        engine.readback
          .frameAt(earlier)
          .shouldBeInstanceOf<ReadbackResult.Success>()
          .frame

      read.presentationTime shouldBe earlier
      read.pixels shouldNotBeSameInstanceAs drawn.frames.getValue(earlier).pixels
    }

  @Test
  fun `a readback after the shuttle ended is drawn again`() =
    shuttleTest { engine, recorder, drawn ->
      engine.shuttleToTheEnd(recorder)
      engine.endShuttle()
      awaitStep("the engine to go quiet") { engine.awaitQuiet() }
      val playhead = recorder.playhead ?: fail("The engine reported no playhead.")

      val read =
        engine.readback
          .frameAt(playhead)
          .shouldBeInstanceOf<ReadbackResult.Success>()
          .frame

      read.presentationTime shouldBe playhead
      drawn.frames[playhead]?.let { read.pixels shouldNotBeSameInstanceAs it.pixels }
    }

  // Runs the shuttle onto the last frame, where it holds, so the playhead and the frame held stay put.
  private suspend fun BrowserPlayerEngine.shuttleToTheEnd(recorder: ContractRecorder) {
    beginShuttle(RATE)
    awaitContract("the shuttle to reach the end") {
      recorder.events.any { it is PlaybackEvent.ShuttleReachedEdge && it.edge == ShuttleEdge.End }
    }
    awaitStep("the engine to go quiet") { awaitQuiet() }
  }

  private fun shuttleTest(
    body: suspend (engine: BrowserPlayerEngine, recorder: ContractRecorder, drawn: DrawnFrames) -> Unit,
  ) = contractTest { scope ->
    val drawn = DrawnFrames()
    val engine = WebEngineUnderTest(scope, drawShuttleFrame = drawn::draw).engine
    withEngine(engine) { recorder ->
      engine.awaitComposition(webFixtureComposition()).shouldBeInstanceOf<SetCompositionResult.Success>()
      awaitContract("the engine to become seekable") { recorder.lastState.hasComposition }
      body(engine, recorder, drawn)
    }
  }

  /**
   * Draws a shuttle's frames the way the engine does and keeps each one by the slot it sits on.
   */
  private class DrawnFrames {
    val frames = mutableMapOf<Duration, PreviewFrame>()

    suspend fun draw(
      preview: BrowserPreview,
      at: Duration,
    ): PreviewFrame? = preview.frameAt(at)?.also { frames[it.presentationTime] = it }
  }

  private companion object {
    const val RATE = 2f
  }
}
