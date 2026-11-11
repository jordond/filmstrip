package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.encode.keyFrameIntervalOf
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * How the planner settles the key frame spacing a transcode is handed.
 */
class KeyFramePlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a transcode with no interval spaces key frames a second apart`() {
    val spec = ExportSpec(frameRate = 24)

    transcode(spec).output.keyFrameSpacing shouldBe keyFrameIntervalOf(spec, 24)
  }

  @Test
  fun `an asked interval resolves through the shared count at the output rate`() {
    val spec = ExportSpec(frameRate = 25, keyFrameInterval = 500.milliseconds)

    transcode(spec).output.keyFrameSpacing shouldBe keyFrameIntervalOf(spec, 25)
  }

  @Test
  fun `the count follows the rate the encoder is held to`() {
    val slow = fixture.device(video = listOf(fixture.encoder(VideoCodec.H264, maxFrameRate = 30)))
    val spec = ExportSpec(frameRate = 60, keyFrameInterval = 500.milliseconds)

    val plan =
      fixture
        .plan(
          composition(fixture.clip()),
          spec,
          device = slow,
        ).shouldBeInstanceOf<Verdict.Degraded>()
        .plan

    plan.output.frameRate shouldBe 30
    plan.output.keyFrameSpacing shouldBe keyFrameIntervalOf(spec, 30)
  }

  @Test
  fun `an interval neither blocks a copy nor applies to it`() {
    val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(keyFrameInterval = 500.milliseconds))

    val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy shouldBe emptyList()
    plan.output.keyFrameSpacing.shouldBeNull()
  }

  @Test
  fun `an audio-only export settles no key frame spacing`() {
    val composition = composition(fixture.clip(audioRate = 44_100)).withAudio(AudioSpec.AudioOnly)

    val verdict = fixture.plan(composition, ExportSpec(keyFrameInterval = 500.milliseconds))

    verdict
      .shouldBeInstanceOf<Verdict.Capable>()
      .plan.output.keyFrameSpacing
      .shouldBeNull()
  }

  @Test
  fun `an interval of zero or less is refused`() {
    for (interval in listOf(Duration.ZERO, (-500).milliseconds)) {
      val verdict = fixture.plan(composition(fixture.clip()), ExportSpec(keyFrameInterval = interval))

      val error = verdict.shouldBeInstanceOf<Verdict.Incapable>().reasons.single()
      error.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldContain
        "a key frame every $interval, and the interval has to be positive"
    }
  }

  private fun transcode(spec: ExportSpec) =
    fixture.plan(composition(fixture.clip()), spec).shouldBeInstanceOf<Verdict.Capable>().plan
}
