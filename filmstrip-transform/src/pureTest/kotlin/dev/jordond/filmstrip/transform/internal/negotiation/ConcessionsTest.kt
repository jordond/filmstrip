package dev.jordond.filmstrip.transform.internal.negotiation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.AudioEncoderCapability
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.effect.EffectIds
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.color.Sepia
import dev.jordond.filmstrip.export.Adjustment
import dev.jordond.filmstrip.export.AdjustmentKind
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.DEFAULT_HDR_LADDER
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.fakePlatformEffect
import dev.jordond.filmstrip.transform.internal.refusal
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * What an export reports it gave up against its spec, read from the outcome of each phase of the negotiation.
 */
class ConcessionsTest {
  private val fixture = PlannerFixture()
  private val clip = fixture.clip()
  private val info = fixture.infos(composition(clip)).getValue(clip.source)
  private val aac = CodecChoice(AudioCodec.Aac, null)

  @Test
  fun `an export taken as asked concedes nothing`() {
    concessions().shouldBeEmpty()
  }

  @Test
  fun `every concession comes back in the order a caller reads them`() {
    val device =
      fixture.device(
        video = listOf(fixture.encoder(VideoCodec.H264)),
        audio = listOf(AudioEncoderCapability(AudioCodec.Aac, listOf(48_000), 2)),
      )
    val spec = ExportSpec(videoCodec = VideoCodec.Hevc, audioCodec = AudioCodec.Opus, frameRate = 120)
    val video = resolveVideoCodec(spec, device, false, listOf(VideoCodec.H264), DEFAULT_HDR_LADDER).shouldNotBeNull()
    val audio = resolveAudioCodec(spec, device, silent = false)
    val degraded = LoweredEffect(Sepia(1f), listOf(Sepia(1f)), fakePlatformEffect(), null, listOf("rounds"))

    val kinds =
      concessionsOf(
        spec = spec,
        framing = framingOf(clip, info, requestedSize = Size(1921, 1081)),
        grade = gradeOf(sourceIsHdr = true, hdr = ResolvedHdr.ToneMap),
        encoding = encodingOf(Size(1920, 1080), video = video, frameRate = 60),
        audio = audio,
        degraded = degraded.degraded,
        dropped = setOf(EffectIds.BRIGHTNESS),
      ).map { it.adjustment.kind }

    kinds shouldBe
      listOf(
        AdjustmentKind.CodecFallback,
        AdjustmentKind.CodecFallback,
        AdjustmentKind.ResolutionClamped,
        AdjustmentKind.FrameRateClamped,
        AdjustmentKind.HdrToneMapped,
        AdjustmentKind.EffectApproximated,
        AdjustmentKind.EffectDropped,
      )
  }

  @Test
  fun `a frame over the ceiling is scaled down and refused strictly against that ceiling`() {
    val ceiling = Size(1920, 1080)
    val encoding = encodingOf(ceiling, ceiling = ceiling, overCeiling = true)

    val clamp = concessions(framing = framingOf(clip, info, Size(3840, 2160)), encoding = encoding).single()

    val message =
      "This export encodes into 1920x1080 at most, so the frame is scaled down to fit inside it and keeps its shape."
    clamp.adjustment shouldBe Adjustment(AdjustmentKind.ResolutionClamped, "3840x2160", "1920x1080", message)
    val refusal = clamp.strictRefusal.shouldBeInstanceOf<FilmstripError.UnsupportedResolution>()
    refusal.requested shouldBe Size(3840, 2160)
    refusal.max shouldBe ceiling
    refusal.message shouldBe message
  }

  @Test
  fun `a frame only off the encoder's alignment rounds each side and names the frame it got`() {
    val clamp =
      concessions(framing = framingOf(clip, info, Size(1921, 1081)), encoding = encodingOf(Size(1920, 1080)))
        .single()

    val message = "The encoder wants aligned dimensions, so each side rounds to what it accepts."
    clamp.adjustment shouldBe Adjustment(AdjustmentKind.ResolutionClamped, "1921x1081", "1920x1080", message)
    clamp.strictRefusal.shouldBeInstanceOf<FilmstripError.UnsupportedResolution>().max shouldBe Size(1920, 1080)
  }

  @Test
  fun `a frame rate the encoder holds lower is reported and an unset or matching one is not`() {
    val clamped = concessions(spec = ExportSpec(frameRate = 120), encoding = encodingOf(SIZE, frameRate = 60)).single()

    val message = "The encoder does not accept 120 fps, so 60 is used instead."
    clamped.adjustment shouldBe Adjustment(AdjustmentKind.FrameRateClamped, "120 fps", "60 fps", message)
    clamped.strictRefusal.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe message
    concessions(spec = ExportSpec(frameRate = 60), encoding = encodingOf(SIZE, frameRate = 60)).shouldBeEmpty()
    concessions(spec = ExportSpec(), encoding = encodingOf(SIZE, frameRate = 60)).shouldBeEmpty()
  }

  @Test
  fun `only Auto reports a tone map and says why it landed there`() {
    fun toneMapped(
      mode: HdrMode,
      sourceIsHdr: Boolean = true,
      gradesAgree: Boolean = true,
    ) = concessions(
      spec = ExportSpec(hdr = mode),
      grade = gradeOf(sourceIsHdr = sourceIsHdr, gradesAgree = gradesAgree, hdr = ResolvedHdr.ToneMap),
    ).map { it.adjustment }

    toneMapped(HdrMode.Auto) shouldBe
      listOf(
        Adjustment(
          AdjustmentKind.HdrToneMapped,
          "Auto",
          "ToneMapToSdr",
          "This device cannot encode HDR, so the grade is tone-mapped to SDR.",
        ),
      )
    toneMapped(HdrMode.Auto, gradesAgree = false).single().message shouldBe
      "These sources do not share one HDR transfer, so no single grade describes the output and they are " +
      "tone-mapped to SDR together."
    toneMapped(HdrMode.ToneMapToSdr).shouldBeEmpty()
    toneMapped(HdrMode.Auto, sourceIsHdr = false).shouldBeEmpty()
  }

  // A fold stands for every effect the caller wrote, and the same cause reached on two clips is one approximation.
  @Test
  fun `an approximation is reported once for each authored effect and cause`() {
    val sources = listOf(Brightness(2f), Sepia(1f))
    val folded = { LoweredEffect(Sepia(1f), sources, fakePlatformEffect(), null, listOf("rounds", "converts")) }

    val approximated = concessions(lowered = listOf(folded(), folded())).map { it.adjustment }

    approximated shouldBe
      listOf(
        Adjustment(AdjustmentKind.EffectApproximated, EffectIds.BRIGHTNESS, "approximated", "rounds"),
        Adjustment(AdjustmentKind.EffectApproximated, EffectIds.BRIGHTNESS, "approximated", "converts"),
        Adjustment(AdjustmentKind.EffectApproximated, EffectIds.SEPIA, "approximated", "rounds"),
        Adjustment(AdjustmentKind.EffectApproximated, EffectIds.SEPIA, "approximated", "converts"),
      )
  }

  @Test
  fun `a dropped effect is named and refused strictly in the same words`() {
    val dropped = concessions(dropped = setOf(EffectIds.SEPIA)).single()

    val message = "${EffectIds.SEPIA} cannot run on this device and is not in this plan."
    dropped.adjustment shouldBe Adjustment(AdjustmentKind.EffectDropped, EffectIds.SEPIA, "removed", message)
    dropped.strictRefusal.shouldBeInstanceOf<FilmstripError.InvalidComposition>().message shouldBe message
  }

  private fun concessions(
    spec: ExportSpec = ExportSpec(),
    framing: Framing = framingOf(clip, info),
    grade: Grade = gradeOf(),
    encoding: Encoding = encodingOf(SIZE),
    lowered: List<LoweredEffect> = emptyList(),
    dropped: Set<String> = emptySet(),
  ): List<Concession> = concessionsOf(spec, framing, grade, encoding, aac, lowered.flatMap { it.degraded }, dropped)

  private companion object {
    val SIZE = Size(1920, 1080)
  }
}
