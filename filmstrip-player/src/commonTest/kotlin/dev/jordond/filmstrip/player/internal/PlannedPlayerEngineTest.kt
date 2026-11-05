package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.PlayerFeatures
import dev.jordond.filmstrip.player.PreviewFrameReadback
import dev.jordond.filmstrip.player.PreviewInfo
import dev.jordond.filmstrip.player.PreviewQualityPolicy
import dev.jordond.filmstrip.player.SeekAccuracy
import dev.jordond.filmstrip.player.SetCompositionRequest
import dev.jordond.filmstrip.player.SetCompositionResult
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.test.TestScope
import kotlinx.coroutines.test.runCurrent
import kotlin.coroutines.cancellation.CancellationException
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

@OptIn(ExperimentalCoroutinesApi::class)
class PlannedPlayerEngineTest {
  private val scope = TestScope()
  private val planner = RecordingPlanner()
  private val engine = PlanRecordingEngine(scope, planner)

  @Test
  fun `a new policy plans the loaded edit again from nothing`() {
    load(EDIT)

    engine.setQualityPolicy(CAPPED)
    scope.runCurrent()

    engine.policyOf() shouldBe CAPPED
    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full, EDIT to CAPPED)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Structural)
  }

  @Test
  fun `the policy already in place plans nothing`() {
    load(EDIT)

    engine.setQualityPolicy(PreviewQualityPolicy.Full)
    scope.runCurrent()

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full)
    engine.loadedOf() shouldBe EDIT
  }

  @Test
  fun `a policy set with nothing loaded is kept for the first plan`() {
    engine.setQualityPolicy(CAPPED)
    load(EDIT)

    planner.asked shouldBe listOf(EDIT to CAPPED)
  }

  @Test
  fun `a planned edit is answered with the duration of its plan`() {
    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    engine.planOf()?.resolved?.duration shouldBe PLAN_LENGTH
  }

  @Test
  fun `an equal edit over a standing graph is answered without planning`() {
    load(EDIT)

    load(EDIT) shouldBe listOf(SetCompositionResult.Success(PLAN_LENGTH))

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full)
    engine.changes shouldBe listOf(CompositionDiff.Structural)
  }

  @Test
  fun `an equal edit with no graph standing is planned again`() {
    load(EDIT)
    engine.graphStanding = false

    load(EDIT)

    planner.asked shouldBe listOf(EDIT to PreviewQualityPolicy.Full, EDIT to PreviewQualityPolicy.Full)
    engine.changes shouldBe listOf(CompositionDiff.Structural, CompositionDiff.Equal)
  }

  @Test
  fun `a plan apply refuses reaches the listeners and the caller once`() {
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)
    engine.refusal = refusal

    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(refusal))

    engine.failures shouldBe listOf(refusal)
  }

  @Test
  fun `a refused plan reaches the listeners and the caller once`() {
    val refusal = PlaybackError.UnsupportedFormat(BROKEN)
    planner.answer = { PlanResult.Refused(refusal) }

    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(refusal))

    engine.failures shouldBe listOf(refusal)
    engine.changes.shouldBeEmpty()
  }

  @Test
  fun `a plan that throws is refused rather than left waiting`() {
    planner.answer = { error(BROKEN) }

    val expected = PlaybackError.Underlying(PlaybackError.Underlying.NO_PLATFORM_CODE, BROKEN)
    load(EDIT) shouldBe listOf(SetCompositionResult.Failure(expected))

    engine.failures shouldBe listOf(expected)
  }

  @Test
  fun `a cancelled plan is not reported as a failure`() {
    planner.answer = { throw CancellationException(BROKEN) }

    load(EDIT).shouldBeEmpty()

    engine.failures.shouldBeEmpty()
  }

  private fun load(composition: EditComposition): List<SetCompositionResult> {
    val results = mutableListOf<SetCompositionResult>()
    engine.setComposition(SetCompositionRequest(composition)) { results += it }
    scope.runCurrent()
    return results
  }

  private companion object {
    const val BROKEN = "the prober threw"
    val CAPPED = PreviewQualityPolicy.CapHeight(360)
    val PLAN_LENGTH: Duration = 3.seconds
    val FRAME = Size(1280, 720)
    val EDIT =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("planned.mp4"), TimeRange.of(Duration.ZERO, 2.seconds))))),
      )
    val PLAN =
      PreviewPlan(
        resolved =
          ResolvedComposition(
            tracks = emptyList(),
            compositionEffects = emptyList(),
            output =
              OutputFormat(
                size = FRAME,
                videoCodec = VideoCodec.H264,
                audioCodec = AudioCodec.Aac,
                bitrate = null,
                frameRate = 30,
                audioFormat = null,
              ),
            layoutSize = FRAME,
            fill = Fill.Solid(),
            duration = PLAN_LENGTH,
            hdr = ResolvedHdr.ToneMap,
            hdrTransfer = null,
            path = ExportPath.Transcode,
            audio = AudioSpec.Keep,
            adjustments = emptyList(),
          ),
        info = PreviewInfo(FRAME, 1f, EffectParity.Exact, emptyList(), emptyList()),
        spec = ExportSpec(),
        layoutSize = null,
      )
  }

  // Records every edit and policy it is asked to plan, and answers each with [answer].
  private class RecordingPlanner : PreviewPlanner<PreviewPlan> {
    val asked: MutableList<Pair<EditComposition, PreviewQualityPolicy>> = mutableListOf()
    var answer: () -> PlanResult<PreviewPlan, PlaybackError> = { PlanResult.Ready(PLAN) }

    override suspend fun plan(
      composition: EditComposition,
      policy: PreviewQualityPolicy,
    ): PlanResult<PreviewPlan, PlaybackError> {
      asked += composition to policy
      return answer()
    }
  }
}

// Records how each applied edit changed and every failure it reports, and stands a graph once a plan is applied unless
// told to refuse it.
private class PlanRecordingEngine(
  parent: CoroutineScope,
  planner: PreviewPlanner<PreviewPlan>,
) : PlannedPlayerEngine<PreviewPlan>(parent, PreviewQualityPolicy.Full, planner) {
  val changes: MutableList<CompositionDiff> = mutableListOf()
  val failures: MutableList<PlaybackError> = mutableListOf()
  var refusal: PlaybackError? = null
  var graphStanding = false

  override val id: String = "filmstrip.planned"

  override val features: PlayerFeatures = PlayerFeatures(emptySet())

  override val readback: PreviewFrameReadback = PreviewFrameReadback { _, _ -> Cancellable { } }

  override val nativePlayer: Any? = null

  override val isGraphStanding: Boolean get() = graphStanding

  fun loadedOf(): EditComposition? = loaded

  fun planOf(): PreviewPlan? = plan

  fun policyOf(): PreviewQualityPolicy = policy

  override suspend fun apply(
    change: CompositionDiff,
    next: PreviewPlan,
    request: SetCompositionRequest,
  ): PlaybackError? {
    changes += change
    refusal?.let { return it }
    loaded = request.composition
    plan = next
    graphStanding = true
    return null
  }

  override fun fail(error: PlaybackError) {
    failures += error
  }

  override fun readPosition(): Duration = Duration.ZERO

  override fun isSeekReady(): Boolean = false

  override fun performSeek(
    position: Duration,
    accuracy: SeekAccuracy,
    onComplete: (landed: Duration) -> Unit,
  ): Unit = Unit

  override fun onPlay(): Unit = Unit

  override fun onPause(): Unit = Unit

  override fun onRelease(): Unit = Unit

  override fun stepFrames(frames: Int): Unit = Unit

  override fun setVolume(volume: Float): Unit = Unit

  override fun setLoopRange(range: TimeRange?): Unit = Unit

  override fun setPlaybackRange(range: TimeRange?): Unit = Unit
}
