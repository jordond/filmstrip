package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.avfoundation.internal.chain.CoreImageChain
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.CoreImageEffect
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import platform.CoreGraphics.CGRectMake
import platform.CoreImage.CIColor
import platform.CoreImage.CIImage
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * A step that throws while a chain draws, before and after a parameter swap.
 *
 * A frame still in flight from the parameters a swap replaced is stale, so what it throws neither latches the chain's
 * failure nor reaches its listener. A frame drawn against the parameters the swap put in place does both.
 */
@OptIn(ExperimentalForeignApi::class)
class CoreImageChainFailureTest {
  @Test
  fun `a stale frame from before a swap does not latch the failure the swap cleared`() {
    val chain = chainOf(throwing())
    val reported = mutableListOf<FilmstripError>()
    chain.onFailure = { reported += it }

    val stale = chain.current
    chain.updateParameters(resolved(throwing()))
    val passed = chain.drawn(stale, SOURCE, PAST_THE_CLIP)

    passed shouldBe SOURCE
    withClue("a stale frame latched the failure") { chain.failure.shouldBeNull() }
    reported shouldBe emptyList()

    chain.drawn(chain.current, SOURCE, PAST_THE_CLIP)

    val failure = chain.failure.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()
    failure.specId shouldBe SPEC_ID
    failure.message shouldBe REASON
    reported shouldBe listOf(failure)
  }

  @Test
  fun `a failure latched before a swap is cleared by it and a stale frame after it leaves it clear`() {
    val chain = chainOf(throwing())
    val before = chain.current
    chain.drawn(before, SOURCE, PAST_THE_CLIP)
    chain.failure.shouldBeInstanceOf<FilmstripError.UnsupportedEffect>()

    chain.updateParameters(resolved(throwing()))
    chain.drawn(before, SOURCE, PAST_THE_CLIP)

    withClue("a stale frame latched the failure again after the swap") { chain.failure.shouldBeNull() }
  }

  @Test
  fun `a swap landing while a step throws drops what that frame threw`() {
    lateinit var chain: CoreImageChain
    val swapping =
      ResolvedEffect(
        specId = SPEC_ID,
        effect =
          PlatformEffect(
            CoreImageEffect { _, _ ->
              chain.updateParameters(resolved(throwing()))
              throw IllegalStateException(REASON)
            },
          ),
        stage = EffectStage.Color,
      )
    chain = chainOf(swapping)
    val reported = mutableListOf<FilmstripError>()
    chain.onFailure = { reported += it }

    chain.drawn(chain.current, SOURCE, PAST_THE_CLIP)

    withClue("a frame the swap overtook latched the failure") { chain.failure.shouldBeNull() }
    reported shouldBe emptyList()
  }

  private fun chainOf(effect: ResolvedEffect): CoreImageChain =
    checkNotNull(resolved(effect).toAvComposition().chain) { "the composition lays no video" }

  private fun throwing(): ResolvedEffect =
    ResolvedEffect(
      specId = SPEC_ID,
      effect = PlatformEffect(CoreImageEffect { _, _ -> throw IllegalStateException(REASON) }),
      stage = EffectStage.Color,
    )

  // A still whose file is never opened, since every frame drawn here falls past its span.
  private fun resolved(effect: ResolvedEffect): ResolvedComposition =
    ResolvedComposition(
      tracks =
        listOf(
          ResolvedTrack(
            content = TrackContent.AudioAndVideo,
            looping = false,
            start = Duration.ZERO,
            clips = listOf(still()),
          ),
        ),
      compositionEffects = listOf(effect),
      output =
        OutputFormat(
          size = OUTPUT,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = FRAME_RATE,
          audioFormat = null,
        ),
      layoutSize = OUTPUT,
      fill = Fill.Black,
      duration = CLIP,
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = ExportPath.Transcode,
    )

  private fun still(): ResolvedClip =
    ResolvedClip(
      source = MediaSource.Image(PHOTO, CLIP),
      info = imageMediaInfoOf(OUTPUT, EXIF_ORIENTATION_NORMAL, "png", CLIP),
      start = Duration.ZERO,
      end = CLIP,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, CLIP),
      startsAtKeyFrame = false,
      span = TimeRange.of(Duration.ZERO, CLIP),
      sourceIndex = 0,
    )

  private companion object {
    const val SPEC_ID = "dev.jordond.filmstrip.test.throwing"
    const val REASON = "The picture is too large to draw at its color."
    const val FRAME_RATE = 30
    val OUTPUT = Size(640, 360)
    val CLIP = 1.seconds
    val PAST_THE_CLIP = 5.seconds
    val PHOTO = ImageSource.of("/filmstrip/does-not-exist.png")
    val SOURCE: CIImage =
      CIImage(color = CIColor.blackColor).imageByCroppingToRect(CGRectMake(0.0, 0.0, 640.0, 360.0))
  }
}
