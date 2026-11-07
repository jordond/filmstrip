@file:OptIn(InternalFilmstripApi::class)

package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.CapabilitiesResult
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.VideoEncoderCapability
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.exportRefusalOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.awaitCancellation
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.test.runCurrent
import kotlinx.coroutines.test.runTest
import kotlin.test.Test

class TryEngineRefusalTest {
  private val source = MediaSource.ofUri("blob:beach")
  private var exportsStarted = 0

  private fun exports(): Flow<ExportStatus> =
    flow {
      exportsStarted++
      emit(ExportStatus.Progress(0.37f, null, null))
      awaitCancellation()
    }

  private fun engine(scope: CoroutineScope) = TryEngine(scope, { _, _ -> exports() }, { source }, {}, {})

  private fun answered(vararg video: VideoEncoderCapability) =
    CapabilitiesResult.Success(
      DeviceCapabilities(
        video = video.toList(),
        audio = emptyList(),
        supportsHdrEncoding = false,
        concurrentSessionBudget = null,
      ),
    )

  private val noEncoder = answered()
  private val reason = exportRefusalOf(noEncoder).shouldNotBeNull()
  private val h264 =
    VideoEncoderCapability(
      codec = VideoCodec.H264,
      encoderName = null,
      maxSize = Size(1920, 1080),
      maxFrameRate = null,
      maxBitrate = null,
      isHardwareAccelerated = null,
      sizeAlignment = 2,
    )

  @Test
  fun `export does nothing with no encoder and the status says why`() =
    runTest {
      val engine = engine(backgroundScope)
      engine.onCapabilities(noEncoder)
      engine.load()
      runCurrent()

      engine.apply(Control.Export)
      runCurrent()

      exportsStarted shouldBe 0
      engine.state.value.export shouldBe ExportState.Idle
      engine.state.value.error
        .shouldBeNull()
      statusOf(engine.state.value).exportRefusal shouldBe reason
    }

  @Test
  fun `the preview and the steps keep working with no encoder`() =
    runTest {
      val engine = engine(backgroundScope)
      engine.onCapabilities(noEncoder)
      engine.load()
      runCurrent()
      val steps = Steps(trim = true, grade = true, title = true)

      engine.apply(Control.SetSteps(steps))

      val state = engine.state.value
      state.source shouldBe source
      state.steps shouldBe steps
      statusOf(state).trimRange shouldBe TRIM_RANGE
      statusOf(state).exportRefusal shouldBe reason
    }

  @Test
  fun `an encoder on the list changes nothing`() =
    runTest {
      val engine = engine(backgroundScope)
      engine.onCapabilities(answered(h264))
      engine.load()
      runCurrent()
      statusOf(engine.state.value).exportRefusal.shouldBeNull()

      engine.apply(Control.Export)
      runCurrent()

      exportsStarted shouldBe 1
      engine.state.value.export
        .shouldBeInstanceOf<ExportState.Running>()
        .fraction shouldBe 0.37f
    }
}
