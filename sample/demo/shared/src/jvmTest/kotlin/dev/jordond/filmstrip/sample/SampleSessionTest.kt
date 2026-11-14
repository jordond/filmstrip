package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.CodecKind
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.TrackCodec
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.player.EngineListener
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlayerEngine
import dev.jordond.filmstrip.player.PlayerEngineId
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldNotContain
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.NonCancellable
import kotlinx.coroutines.withContext
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * How a session moves between the start screen and the editor, and what the stage reads off it.
 */
@OptIn(InternalFilmstripApi::class)
class SampleSessionTest {
  private val state =
    SampleAppState(
      filmstrip =
        Filmstrip {
          autoRegister(false)
          addMediaProberFactory {
            MediaProber {
              ProbeResult.Success(
                MediaInfo(5.seconds, SOURCE_TRACK, emptyList(), true),
              )
            }
          }
        },
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  @Test
  fun `a pick opens the editor over the start screen`() {
    state.backStack.toList() shouldBe listOf(SampleRoute.Start)

    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")

    state.backStack.toList() shouldBe listOf(SampleRoute.Start, SampleRoute.Editor)
  }

  @Test
  fun `back from the editor closes the clip and lands on the start screen`() {
    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")

    state.navigateBack()

    state.backStack.toList() shouldBe listOf(SampleRoute.Start)
    state.source.shouldBeNull()
  }

  @Test
  fun `back from an overlay stays in the editor`() {
    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")
    state.openExport()

    state.navigateBack()

    state.backStack.toList() shouldBe listOf(SampleRoute.Start, SampleRoute.Editor)
  }

  @Test
  fun `a platform error dump stays off the stage`() {
    val dump = "error from player 0: MediaCodecVideoRenderer error, index=1, format=Format(1, null, video/mp4)"

    val errors =
      listOf(
        PlaybackError.SourceUnreadable(dump),
        PlaybackError.UnsupportedFormat(dump),
        PlaybackError.DecoderUnavailable(dump),
        PlaybackError.SourceNotExportable(dump),
        PlaybackError.BackendMissing("filmstrip-player", dump),
        PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, dump),
      )

    errors.forEach { it.stageSentence() shouldNotContain "format=" }
  }

  @Test
  fun `the size chip reads the source until the preview reports the composition`() {
    val engine = ReportingEngine(Size(640, 360))
    val state =
      SampleAppState(
        filmstrip =
          Filmstrip {
            autoRegister(false)
            addMediaProberFactory {
              MediaProber { ProbeResult.Success(MediaInfo(5.seconds, SOURCE_TRACK, emptyList(), true)) }
            }
            addPlayerEngineFactory { _, _ -> engine }
          },
        scope = CoroutineScope(Dispatchers.Unconfined),
      )
    state.compositionFrameLabel().shouldBeNull()

    state.onPicked(MediaSource.of("/tmp/clip.mp4"), "clip.mp4")

    state.compositionFrameLabel() shouldBe "1280 x 720"
    state.targetHeight shouldBe 1080

    state.startPreview()

    state.previewOutput shouldBe Size(640, 360)
    state.compositionFrameLabel() shouldBe "640 x 360"
    state.stopPreview()
  }

  @Test
  fun `a probe from a replaced clip leaves the next pick alone`() {
    val gates = mapOf(FIRST to CompletableDeferred<ProbeResult>(), SECOND to CompletableDeferred())
    val state = heldProbes(gates)

    state.onPicked(FIRST, "first.mp4")
    state.onPicked(SECOND, "second.mp4")
    state.probing shouldBe true

    gates.getValue(FIRST).complete(ProbeResult.Failure(FilmstripError.SourceUnreadable("first.mp4", "stale")))
    state.probe.shouldBeNull()
    state.probing shouldBe true

    gates.getValue(SECOND).complete(ProbeResult.Success(MediaInfo(7.seconds, SOURCE_TRACK, emptyList(), true)))
    state.info?.duration shouldBe 7.seconds
    state.probing shouldBe false
  }

  @Test
  fun `a probe from a closed clip leaves the next pick alone`() {
    val gates = mapOf(FIRST to CompletableDeferred<ProbeResult>(), SECOND to CompletableDeferred())
    val state = heldProbes(gates)

    state.onPicked(FIRST, "first.mp4")
    state.closeProject()
    state.probing shouldBe false

    state.onPicked(SECOND, "second.mp4")
    gates.getValue(FIRST).complete(ProbeResult.Failure(FilmstripError.SourceUnreadable("first.mp4", "stale")))

    state.probe.shouldBeNull()
    state.probing shouldBe true
  }

  @Test
  fun `an export is released once the session moves past it`() {
    val released = mutableListOf<MediaSink>()
    val state =
      SampleAppState(
        filmstrip =
          Filmstrip {
            autoRegister(false)
            addMediaProberFactory {
              MediaProber {
                ProbeResult.Success(
                  MediaInfo(5.seconds, SOURCE_TRACK, emptyList(), true),
                )
              }
            }
          },
        scope = CoroutineScope(Dispatchers.Unconfined),
        release = { released += it },
      )
    val output = MediaSink.ofUri("blob:one")

    fun finished() = ExportStatus.Success(output, MediaInfo(5.seconds, null, emptyList(), true), emptyList())
    state.onPicked(FIRST, "first.mp4")

    state.exported = finished()
    state.onEditChanged()
    released shouldBe listOf<MediaSink>(output)
    state.exported.shouldBeNull()

    state.onEditChanged()
    released shouldBe listOf<MediaSink>(output)

    state.exported = finished()
    state.onPicked(SECOND, "second.mp4")
    released shouldBe listOf<MediaSink>(output, output)

    state.exported = finished()
    state.closeProject()
    released shouldBe listOf<MediaSink>(output, output, output)
  }

  @Test
  fun `a download from a closed clip leaves the start screen alone`() {
    val gate = CompletableDeferred<MediaSource>()
    val state = heldDownloads(mapOf(PRESET to gate))

    state.pickPreset(PRESET)
    state.closeProject()
    state.loadingPreset.shouldBeNull()

    gate.complete(FIRST)

    state.backStack.toList() shouldBe listOf(SampleRoute.Start)
    state.source.shouldBeNull()
    state.sourcePreset.shouldBeNull()
  }

  @Test
  fun `a download from a replaced pick leaves the next pick alone`() {
    val gate = CompletableDeferred<MediaSource>()
    val state = heldDownloads(mapOf(PRESET to gate))

    state.pickPreset(PRESET)
    state.onPicked(SECOND, "second.mp4")
    state.loadingPreset.shouldBeNull()

    gate.complete(FIRST)

    state.source shouldBe SECOND
    state.sourceLabel shouldBe "second.mp4"
    state.sourcePreset.shouldBeNull()
  }

  @Test
  fun `a failed download from a closed clip reports nothing`() {
    val gate = CompletableDeferred<MediaSource>()
    val state = heldDownloads(mapOf(PRESET to gate))

    state.pickPreset(PRESET)
    state.closeProject()

    gate.completeExceptionally(IllegalStateException("stale"))

    state.pickFailure.shouldBeNull()
  }

  @Test
  fun `a stale download leaves the next download running`() {
    val gates = mapOf(PRESET to CompletableDeferred<MediaSource>(), NEXT_PRESET to CompletableDeferred())
    val state = heldDownloads(gates)

    state.pickPreset(PRESET)
    state.closeProject()
    state.pickPreset(NEXT_PRESET)

    gates.getValue(PRESET).complete(FIRST)
    state.loadingPreset shouldBe NEXT_PRESET
    state.source.shouldBeNull()

    gates.getValue(NEXT_PRESET).complete(SECOND)
    state.loadingPreset.shouldBeNull()
    state.source shouldBe SECOND
    state.sourcePreset shouldBe NEXT_PRESET
    state.backStack.toList() shouldBe listOf(SampleRoute.Start, SampleRoute.Editor)
  }

  private fun heldDownloads(gates: Map<SamplePreset, CompletableDeferred<MediaSource>>): SampleAppState =
    SampleAppState(
      filmstrip =
        Filmstrip {
          autoRegister(false)
          addMediaProberFactory {
            MediaProber {
              ProbeResult.Success(
                MediaInfo(5.seconds, SOURCE_TRACK, emptyList(), true),
              )
            }
          }
        },
      scope = CoroutineScope(Dispatchers.Unconfined),
      // Ignores its own cancellation the way a platform read past the point of no return does.
      download = { preset -> withContext(NonCancellable) { gates.getValue(preset).await() } },
    )

  private fun heldProbes(gates: Map<MediaSource, CompletableDeferred<ProbeResult>>): SampleAppState =
    SampleAppState(
      filmstrip =
        Filmstrip {
          autoRegister(false)
          addMediaProberFactory {
            // Ignores its own cancellation the way a platform read past the point of no return does.
            MediaProber { source -> withContext(NonCancellable) { gates.getValue(source).await() } }
          }
        },
      scope = CoroutineScope(Dispatchers.Unconfined),
    )

  internal companion object {
    val FIRST = MediaSource.of("/tmp/first.mp4")
    val SECOND = MediaSource.of("/tmp/second.mp4")
    val PRESET = SamplePreset("First", "held", "first.mp4", 1L, 5.seconds)
    val NEXT_PRESET = SamplePreset("Next", "held", "next.mp4", 1L, 5.seconds)

    val SOURCE_TRACK =
      VideoTrackInfo(
        codedSize = Size(1280, 720),
        displaySize = Size(1280, 720),
        rotationDegrees = 0,
        pixelAspectRatio = 1f,
        frameRate = 30f,
        codec = TrackCodec("avc1", CodecKind.Other),
        bitDepth = null,
        colorSpace = ColorSpace.Bt709,
        hdrTransfer = null,
        bitrate = null,
        hasAlpha = false,
      )
  }
}

/**
 * An engine with no pipeline under it, which reports [outputSize] as the frame its preview delivers.
 */
@OptIn(InternalFilmstripApi::class)
private class ReportingEngine(
  private val outputSize: Size,
) : PlayerEngine {
  override val id: PlayerEngineId = PlayerEngineId("reporting")

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override fun setComposition(
    request: SetCompositionRequest,
    callback: SetCompositionCallback,
  ): Cancellable {
    callback.onResult(SetCompositionResult.Success(5.seconds))
    return Cancellable { }
  }

  override fun addListener(listener: EngineListener): Cancellable {
    listener.onPreviewInfo(PreviewInfo(outputSize, 1f, EffectParity.Exact, emptyList(), emptyList()))
    return Cancellable { }
  }

  override fun setExportSpec(spec: ExportSpec): Unit = Unit

  override fun dispose(): Unit = Unit

  override fun play(): Unit = Unit

  override fun pause(): Unit = Unit

  override fun seekTo(
    position: Duration,
    accuracy: SeekAccuracy,
  ): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun beginScrub(): Unit = Unit

  override fun endScrub(): Unit = Unit

  override fun beginShuttle(rate: Float): Unit = Unit

  override fun endShuttle(): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit

  override fun setQualityPolicy(policy: PreviewQualityPolicy): Unit = Unit
}
