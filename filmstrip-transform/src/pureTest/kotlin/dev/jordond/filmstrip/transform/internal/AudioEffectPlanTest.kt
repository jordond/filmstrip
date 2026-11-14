package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.audio.AudioEffectSpec
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.EffectId
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportPlan
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.InertReason
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.transform.internal.negotiation.Ask
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.runsAs
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * How the planner hands audio effects to a backend: which run of audio each lands on and in what order, which are
 * left out as inert, what they cost a copy, and how one a resolver refuses, degrades or fails is reported.
 */
class AudioEffectPlanTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a clip runs its own effects and then its track's and the mix runs the composition's`() {
    val first = heard(audioEffects = listOf(Fx(EffectId("a")), Fx(EffectId("b"))))
    val second = heard()
    val edit =
      Composition(
        listOf(Track(listOf(first, second), audioEffects = listOf(Fx(EffectId("c"))))),
        audioEffects = fx(EffectId("d")),
      )

    val resolved = fixture.resolve(edit)

    resolved.tracks[0].clips.map { clip -> clip.audioEffects.map { it.specId } } shouldContainExactly
      listOf(listOf(EffectId("a"), EffectId("b"), EffectId("c")), listOf(EffectId("c")))
    resolved.mixAudioEffects.map { it.specId } shouldContainExactly listOf(EffectId("d"))
  }

  @Test
  fun `the same effect declared twice at one scope runs twice`() {
    val resolved = fixture.resolve(single(heard(audioEffects = listOf(Fx(EffectId("a")), Fx(EffectId("a"))))))

    firstClipOf(resolved).audioEffects.map { it.specId } shouldContainExactly listOf(EffectId("a"), EffectId("a"))
  }

  @Test
  fun `each effect is asked over the run of audio it lands on`() {
    val resolver = FakeAudioResolver()
    val first = heard(trim = TimeRange(0.seconds, 2.seconds), audioEffects = fx(EffectId("clip")))
    val second = heard(trim = TimeRange(1.seconds, 4.seconds))
    val edit =
      Composition(
        listOf(Track(listOf(first, second), audioEffects = fx(EffectId("track")))),
        audioEffects = fx(EffectId("mix")),
      )

    fixture.resolve(edit, audioResolvers = listOf(resolver))

    resolver.asked shouldContainExactly
      listOf(
        EffectId("clip") to TimeRange.of(0.seconds, 2.seconds),
        EffectId("track") to TimeRange.of(0.seconds, 2.seconds),
        EffectId("track") to TimeRange.of(2.seconds, 5.seconds),
        EffectId("mix") to TimeRange.of(0.seconds, 5.seconds),
      )
  }

  @Test
  fun `every pass of a looping track runs the track's effects over its own slot`() {
    val resolver = FakeAudioResolver()
    val picture = heard(trim = TimeRange(0.seconds, 5.seconds))
    val bed = heard(trim = TimeRange(0.seconds, 2.seconds))
    val music = Track(listOf(bed), TrackContent.Audio, looping = true, audioEffects = fx(EffectId("bed")))

    val resolved =
      fixture.resolve(
        Composition(listOf(Track(listOf(picture)), music)),
        audioResolvers = listOf(resolver),
      )

    resolved.tracks[1].clips.map { clip -> clip.audioEffects.map { it.specId } } shouldContainExactly
      listOf(listOf(EffectId("bed")), listOf(EffectId("bed")), listOf(EffectId("bed")))
    resolver.asked.map { it.second } shouldContainExactly
      listOf(
        TimeRange.of(0.seconds, 2.seconds),
        TimeRange.of(2.seconds, 4.seconds),
        TimeRange.of(4.seconds, 5.seconds),
      )
  }

  @Test
  fun `an effect that declares itself inert is left out at every scope and named`() {
    val resolver = FakeAudioResolver()
    val clip = heard(audioEffects = listOf(Fx(EffectId("clip"), inert = true)))
    val edit =
      Composition(
        listOf(Track(listOf(clip), audioEffects = listOf(Fx(EffectId("track"), inert = true)))),
        audioEffects = listOf(Fx(EffectId("mix"), inert = true), Fx(EffectId("kept"))),
      )

    val plan = planOf(edit, resolver)

    plan.inertNotices.map { it.subject to it.reason } shouldContainExactly
      listOf(
        "clip" to InertReason.NoChangeToAudio,
        "track" to InertReason.NoChangeToAudio,
        "mix" to InertReason.NoChangeToAudio,
      )
    plan.inertNotices.map { it.message } shouldContainExactly
      listOf(
        "The clip on clip 0 of track 0 leaves every sample as it was, so it was left out.",
        "The track on track 0 leaves every sample as it was, so it was left out.",
        "The mix on the composition leaves every sample as it was, so it was left out.",
      )
    resolver.asked.map { it.first } shouldContainExactly listOf(EffectId("kept"))
  }

  @Test
  fun `a muted clip's effects have no stream and its track's reach only the clips that are heard`() {
    val muted = heard(audioEffects = fx(EffectId("muted"))).withAudio(AudioLevel.Mute)
    val silent = fixture.clip(audioEffects = fx(EffectId("no audio")))
    val audible = heard()
    val edit = Composition(listOf(Track(listOf(muted, silent, audible), audioEffects = fx(EffectId("track")))))

    val plan = planOf(edit)
    val resolved = fixture.resolve(edit)

    plan.inertNotices.map { it.subject to it.reason } shouldContainExactly
      listOf("muted" to InertReason.NoStreamToApplyTo, "no audio" to InertReason.NoStreamToApplyTo)
    plan.inertNotices.first().message shouldBe
      "The muted on clip 0 of track 0 has no audio to apply to, so it was left out."
    resolved.tracks[0].clips.map { clip -> clip.audioEffects.map { it.specId } } shouldContainExactly
      listOf(emptyList(), emptyList(), listOf(EffectId("track")))
  }

  @Test
  fun `a muted track's effects and its clips' have no stream`() {
    val track =
      Track(
        listOf(heard(audioEffects = fx(EffectId("clip")))),
        audio = AudioLevel.Mute,
        audioEffects = fx(EffectId("track")),
      )

    val plan = planOf(Composition(listOf(track), audioEffects = fx(EffectId("mix"))))

    plan.inertNotices.map { it.subject to it.reason } shouldContainExactly
      listOf("clip" to InertReason.NoStreamToApplyTo, "track" to InertReason.NoStreamToApplyTo)
  }

  @Test
  fun `a muted or removed composition leaves every audio effect without a stream`() {
    listOf(AudioSpec.Mute, AudioSpec.Remove).forEach { audio ->
      val resolver = FakeAudioResolver()
      val track = Track(listOf(heard(audioEffects = fx(EffectId("clip")))), audioEffects = fx(EffectId("track")))

      val plan = planOf(Composition(listOf(track), audio = audio, audioEffects = fx(EffectId("mix"))), resolver)

      plan.inertNotices.map { it.subject to it.reason } shouldContainExactly
        listOf(
          "clip" to InertReason.NoStreamToApplyTo,
          "track" to InertReason.NoStreamToApplyTo,
          "mix" to InertReason.NoStreamToApplyTo,
        )
      resolver.asked.shouldBeEmpty()
    }
  }

  @Test
  fun `a mix effect over clips that are all muted still runs and keeps the audio track`() {
    val edit =
      Composition(listOf(Track(listOf(heard().withAudio(AudioLevel.Mute)))), audioEffects = fx(EffectId("hiss")))

    val plan = planOf(edit)
    val resolved = fixture.resolve(edit)

    plan.inertNotices.shouldBeEmpty()
    plan.output.audioCodec shouldNotBe AudioCodec.None
    resolved.mixAudioEffects.map { it.specId } shouldContainExactly listOf(EffectId("hiss"))
  }

  @Test
  fun `an audio effect at each scope blocks the copy and the plan names the scope`() {
    val plain = heard()
    planOf(single(plain)).path shouldBe ExportPath.Copy

    blockersOf(single(plain.withAudioEffects(fx(EffectId("a"))))) shouldContainExactly
      listOf(CopyBlocker.ClipHasAudioEffects)
    blockersOf(Composition(listOf(Track(listOf(plain), audioEffects = fx(EffectId("a")))))) shouldContainExactly
      listOf(CopyBlocker.TrackHasAudioEffects)
    blockersOf(Composition(listOf(Track(listOf(plain))), audioEffects = fx(EffectId("a")))) shouldContainExactly
      listOf(CopyBlocker.CompositionHasAudioEffects)
  }

  @Test
  fun `a clip whose only audio effect is inert copies`() {
    val plan = planOf(single(heard(audioEffects = listOf(Fx(EffectId("a"), inert = true)))))

    plan.path shouldBe ExportPath.Copy
    plan.copyBlockedBy.shouldBeEmpty()
  }

  @Test
  fun `an effect no resolver claims is refused by id`() {
    val edit = single(heard(audioEffects = fx(EffectId("acme.reverb"))))

    val refused = fixture.plan(edit, audioResolvers = emptyList()).shouldBeInstanceOf<Verdict.Incapable>()

    val error = refused.reasons.single().shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
    error.specId shouldBe EffectId("acme.reverb")
    error.message shouldBe
      "No audio effect resolver claimed acme.reverb. Register the one that realizes it with addAudioEffectResolver."
  }

  @Test
  fun `a refused effect is named and the plan without it plays clean audio`() {
    val resolver = FakeAudioResolver(refuse = setOf(EffectId("clip"), EffectId("mix")))
    val clip = heard(audioEffects = listOf(Fx(EffectId("clip")), Fx(EffectId("kept"))))
    val edit = Composition(listOf(Track(listOf(clip))), audioEffects = fx(EffectId("mix")))

    val refused = fixture.plan(edit, audioResolvers = listOf(resolver)).shouldBeInstanceOf<Verdict.Incapable>()
    val without = negotiated(edit, resolver, dropped = setOf(EffectId("clip"), EffectId("mix")))

    refused.reasons.map { it.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>().specId } shouldContainExactly
      listOf(EffectId("clip"), EffectId("mix"))
    refused.withoutUnsupported
      .shouldNotBeNull()
      .adjustments
      .map { it.kind to it.requested } shouldContainExactly
      listOf(AdjustmentKind.EffectDropped to "clip", AdjustmentKind.EffectDropped to "mix")
    firstClipOf(without).audioEffects.map { it.specId } shouldContainExactly listOf(EffectId("kept"))
    without.mixAudioEffects.shouldBeEmpty()
  }

  @Test
  fun `a degraded effect is an approximation the verdict names`() {
    val edit = single(heard(audioEffects = fx(EffectId("a"))))

    val verdict = fixture.plan(edit, audioResolvers = listOf(FakeAudioResolver(degrade = setOf(EffectId("a")))))

    val adjustment = verdict.shouldBeInstanceOf<Verdict.Degraded>().adjustments.single()
    adjustment.kind shouldBe AdjustmentKind.EffectApproximated
    adjustment.requested shouldBe "a"
    adjustment.message shouldBe ROUNDS_IT.message
  }

  @Test
  fun `an effect whose resolution fails refuses the export with its error and no fallback`() {
    val edit = single(heard(audioEffects = fx(EffectId("a"))))

    val refused =
      fixture
        .plan(edit, audioResolvers = listOf(FakeAudioResolver(fail = setOf(EffectId("a")))))
        .shouldBeInstanceOf<Verdict.Incapable>()

    refused.reasons shouldContain AUDIO_READ_FAILED
    refused.withoutUnsupported.shouldBeNull()
  }

  @Test
  fun `effects planned again from equal specs are the ones already running and a changed or moved one is not`() {
    fun mixOf(vararg effects: AudioEffectSpec) =
      fixture.resolve(Composition(listOf(Track(listOf(heard()))), audioEffects = effects.toList())).mixAudioEffects
    val standing = mixOf(Fx(EffectId("a"), amount = 0.5f), Fx(EffectId("b")))

    mixOf(Fx(EffectId("a"), amount = 0.5f), Fx(EffectId("b"))).runsAs(standing) shouldBe true
    mixOf(Fx(EffectId("a"), amount = 0.6f), Fx(EffectId("b"))).runsAs(standing) shouldBe false
    mixOf(Fx(EffectId("b")), Fx(EffectId("a"), amount = 0.5f)).runsAs(standing) shouldBe false
    mixOf(Fx(EffectId("a"), amount = 0.5f)).runsAs(standing) shouldBe false
  }

  private fun heard(
    trim: TimeRange? = null,
    audioEffects: List<AudioEffectSpec> = emptyList(),
  ): Clip = fixture.clip(audioRate = 48_000, trim = trim, audioEffects = audioEffects)

  private fun single(clip: Clip): Composition = Composition(listOf(Track(listOf(clip))))

  private fun fx(id: EffectId): List<AudioEffectSpec> = listOf(Fx(id))

  private fun planOf(
    edit: Composition,
    resolver: FakeAudioResolver = FakeAudioResolver(),
  ): ExportPlan =
    when (val verdict = fixture.plan(edit, audioResolvers = listOf(resolver))) {
      is Verdict.Capable -> verdict.plan
      is Verdict.Degraded -> verdict.plan
      is Verdict.Incapable -> error("The edit was refused: ${verdict.reasons}")
    }

  private fun blockersOf(edit: Composition): List<CopyBlocker> = planOf(edit).copyBlockedBy

  private fun negotiated(
    edit: Composition,
    resolver: FakeAudioResolver,
    dropped: Set<EffectId>,
  ): ResolvedComposition =
    fixture
      .planner(audioResolvers = listOf(resolver))
      .negotiate(edit, Ask(ExportSpec(), fixture.device(), fixture.infos(edit)), dropped)
      .composition
      .shouldNotBeNull()

  private data class Fx(
    override val id: EffectId,
    val inert: Boolean = false,
    val amount: Float = 1f,
  ) : AudioEffectSpec {
    override val isInert: Boolean get() = inert
  }
}
