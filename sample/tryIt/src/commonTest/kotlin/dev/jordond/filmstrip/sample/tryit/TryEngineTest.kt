package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.launch
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class TryEngineTest {
  private val source = MediaSource.ofUri("blob:beach")
  private val written = MediaSink.ofUri("blob:written")
  private val finished = ExportStatus.Success(written, MediaInfo(5.seconds, null, null, true), emptyList())

  private val saved = mutableListOf<MediaSink>()
  private val released = mutableListOf<MediaSink>()

  private fun engine(
    scope: CoroutineScope,
    exporter: (Composition) -> Flow<ExportStatus>,
  ) = TryEngine(scope, { edit, _ -> exporter(edit) }, { source }, saved::add, released::add)

  private fun TestScope.loaded(exporter: (Composition) -> Flow<ExportStatus>): TryEngine {
    val engine = engine(backgroundScope, exporter)
    engine.load()
    runCurrent()
    engine.state.value.source shouldBe source
    return engine
  }

  private fun running(onCancel: () -> Unit = {}): Flow<ExportStatus> =
    flow {
      emit(ExportStatus.Progress(0.37f, null, null))
      try {
        awaitCancellation()
      } finally {
        onCancel()
      }
    }

  @Test
  fun `export writes the edit the steps describe`() =
    runTest {
      var edit: Composition? = null
      val engine =
        loaded {
          edit = it
          flowOf()
        }
      val steps = Steps(trim = true, grade = true)

      engine.apply(Control.SetSteps(steps))
      engine.apply(Control.Export)
      runCurrent()

      edit shouldBe beachEdit(source, steps)
    }

  @Test
  fun `progress reaches the status mid run`() =
    runTest {
      val engine = loaded { running() }
      engine.apply(Control.Export)
      runCurrent()

      statusOf(engine.state.value).export shouldBe ExportState.Running(0.37f)
    }

  @Test
  fun `a finished export waits for save and then hands over the file`() =
    runTest {
      val engine = loaded { flowOf(finished) }
      engine.apply(Control.Export)
      runCurrent()
      engine.state.value.export shouldBe ExportState.Done(written)
      saved.shouldBeEmpty()

      engine.apply(Control.Save)

      saved shouldContainExactly listOf(written)
      released.shouldBeEmpty()
      engine.state.value.export shouldBe ExportState.Idle
    }

  @Test
  fun `the next export lets go of the file the last save handed over`() =
    runTest {
      val engine = loaded { flowOf(finished) }
      engine.apply(Control.Export)
      runCurrent()
      engine.apply(Control.Save)
      released.shouldBeEmpty()

      engine.apply(Control.Export)
      runCurrent()

      released shouldContainExactly listOf(written)
    }

  @Test
  fun `toggling a step drops a finished export`() =
    runTest {
      val engine = loaded { flowOf(finished) }
      engine.apply(Control.Export)
      runCurrent()

      engine.apply(Control.SetSteps(Steps(grade = true)))

      engine.state.value.export shouldBe ExportState.Idle
      released shouldContainExactly listOf(written)
      saved.shouldBeEmpty()
    }

  @Test
  fun `the same steps again keep a finished export`() =
    runTest {
      val engine = loaded { flowOf(finished) }
      engine.apply(Control.Export)
      runCurrent()

      engine.apply(Control.SetSteps(Steps()))

      engine.state.value.export shouldBe ExportState.Done(written)
      released.shouldBeEmpty()
    }

  @Test
  fun `toggling a step mid export stops the run the edit no longer matches`() =
    runTest {
      var cancelled = false
      val engine = loaded { running { cancelled = true } }
      engine.apply(Control.Export)
      runCurrent()

      engine.apply(Control.SetSteps(Steps(title = true)))
      runCurrent()

      cancelled shouldBe true
      engine.state.value.export shouldBe ExportState.Idle
    }

  @Test
  fun `cancel mid export leaves no result`() =
    runTest {
      var cancelled = false
      val engine = loaded { running { cancelled = true } }
      engine.apply(Control.Export)
      runCurrent()
      engine.state.value.export
        .shouldBeInstanceOf<ExportState.Running>()

      engine.apply(Control.Cancel)
      runCurrent()

      cancelled shouldBe true
      engine.state.value.export shouldBe ExportState.Idle
      saved.shouldBeEmpty()
    }

  @Test
  fun `a cancel that arrives after the run finished keeps the result`() =
    runTest {
      val engine = loaded { flowOf(finished) }
      engine.apply(Control.Export)
      runCurrent()

      engine.apply(Control.Cancel)

      engine.state.value.export shouldBe ExportState.Done(written)
    }

  @Test
  fun `export before the clip is here writes nothing`() =
    runTest {
      var exports = 0
      val engine =
        engine(backgroundScope) {
          exports++
          flowOf()
        }

      engine.apply(Control.Export)
      runCurrent()

      exports shouldBe 0
      engine.state.value.export shouldBe ExportState.Idle
    }

  @Test
  fun `a refused export shows why and the button goes back`() =
    runTest {
      val engine = loaded { flow { throw IllegalStateException("A frame this large is refused.") } }
      engine.apply(Control.Export)
      runCurrent()

      statusOf(engine.state.value).error shouldBe "A frame this large is refused."
      engine.state.value.export shouldBe ExportState.Idle
    }

  @Test
  fun `a step clears the last export's error`() =
    runTest {
      val engine = loaded { flow { throw IllegalStateException("A frame this large is refused.") } }
      engine.apply(Control.Export)
      runCurrent()

      engine.apply(Control.SetSteps(Steps(trim = true)))

      engine.state.value.error
        .shouldBeNull()
    }

  @Test
  fun `the status carries the trim only while it is on`() =
    runTest {
      val engine = loaded { flowOf() }
      statusOf(engine.state.value).trimRange.shouldBeNull()

      engine.apply(Control.SetSteps(Steps(trim = true)))

      statusOf(engine.state.value).trimRange shouldBe TRIM_RANGE
    }

  @Test
  fun `the page hears a status only when it changes`() =
    runTest {
      val states = MutableStateFlow(TryState(source = source))
      val heard = mutableListOf<TryStatus>()
      backgroundScope.launch { statusesOf(states).collect { heard += it } }
      runCurrent()

      states.value = states.value.copy(attempt = 2)
      runCurrent()
      heard.size shouldBe 1

      states.value = states.value.copy(position = 500.milliseconds)
      runCurrent()
      heard.map { it.position } shouldContainExactly listOf(0.milliseconds, 500.milliseconds)
    }
}
