package dev.jordond.filmstrip.avfoundation.internal

import dev.jordond.filmstrip.avfoundation.internal.time.toCMTime
import dev.jordond.filmstrip.avfoundation.internal.time.toDuration
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.resolve.GainSegment
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.cinterop.FloatVar
import kotlinx.cinterop.alloc
import kotlinx.cinterop.memScoped
import kotlinx.cinterop.ptr
import kotlinx.cinterop.readValue
import kotlinx.cinterop.value
import platform.AVFoundation.AVAudioMixInputParameters
import platform.AVFoundation.AVMediaTypeAudio
import platform.AVFoundation.AVMutableAudioMix
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVMutableCompositionTrack
import platform.AVFoundation.addMutableTrackWithMediaType
import platform.CoreMedia.CMTimeRange
import platform.CoreMedia.kCMPersistentTrackID_Invalid
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The audio mix written from each placement's gain curve, read back off the ramps AVFoundation holds.
 */
@OptIn(ExperimentalForeignApi::class)
class AvAudioMixTest {
  private val composition = AVMutableComposition()

  @Test
  fun `no mix is written when every clip is at full volume`() {
    val mixed = listOf(audioTrack() to listOf(placed(Duration.ZERO, 1.seconds)), audioTrack() to emptyList())

    mixed.toAudioMix().shouldBeNull()
  }

  // One quiet clip anywhere writes a mix, and every track carries parameters of its own so the
  // full volume one is held at full volume rather than left to a default.
  @Test
  fun `one quiet clip writes parameters for every track by its own id`() {
    val loud = audioTrack()
    val quiet = audioTrack()
    val mix =
      listOf(
        loud to listOf(placed(Duration.ZERO, 1.seconds)),
        quiet to listOf(placed(Duration.ZERO, 1.seconds, ResolvedGain.constant(0.5f, Duration.ZERO, 1.seconds))),
      ).toAudioMix().shouldNotBeNull()

    mix.parametersOf(loud).rampAt(500.milliseconds)?.start shouldBe 1f
    mix.parametersOf(quiet).rampAt(500.milliseconds)?.start shouldBe 0.5f
  }

  @Test
  fun `a constant clip holds its level across its own placement`() {
    val media = audioTrack()
    val clip = placed(1.seconds, 2_500.milliseconds, ResolvedGain.constant(0.4f, Duration.ZERO, 1_500.milliseconds))

    val ramp =
      listOf(media to listOf(clip))
        .toAudioMix()
        .parametersOf(media)
        .rampAt(2.seconds)
        .shouldNotBeNull()

    ramp.start shouldBe 0.4f
    ramp.end shouldBe 0.4f
    ramp.rangeStart shouldBe 1.seconds
    ramp.rangeLength shouldBe 1_500.milliseconds
  }

  // The curve is measured from the clip's own start, so each segment moves onto the timeline by
  // where the placement opens.
  @Test
  fun `a ramping clip writes each segment shifted to where it was placed`() {
    val media = audioTrack()
    val curve =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 400.milliseconds, 0.2f, 0.8f),
          GainSegment(400.milliseconds, 1.seconds, 0.8f, 0.3f),
        ),
      )
    val parameters = listOf(media to listOf(placed(1.seconds, 2.seconds, curve))).toAudioMix().parametersOf(media)

    val rising = parameters.rampAt(1_200.milliseconds).shouldNotBeNull()
    rising.start shouldBe 0.2f
    rising.end shouldBe 0.8f
    rising.rangeStart shouldBe 1.seconds
    rising.rangeLength shouldBe 400.milliseconds
    val falling = parameters.rampAt(1_700.milliseconds).shouldNotBeNull()
    falling.start shouldBe 0.8f
    falling.end shouldBe 0.3f
    falling.rangeStart shouldBe 1_400.milliseconds
  }

  // AVFoundation has no ramp across no time, so the step is a level set at the instant it lands on.
  @Test
  fun `a zero length segment is a step at the instant it lands on`() {
    val media = audioTrack()
    val step =
      ResolvedGain(
        listOf(
          GainSegment(Duration.ZERO, 400.milliseconds, 1f, 1f),
          GainSegment(400.milliseconds, 400.milliseconds, 1f, 0f),
          GainSegment(400.milliseconds, 500.milliseconds, 0f, 0f),
        ),
      )
    val stepping = listOf(media to listOf(placed(500.milliseconds, 1.seconds, step)))
    val parameters = stepping.toAudioMix().parametersOf(media)

    parameters.rampAt(700.milliseconds)?.start shouldBe 1f
    val after = parameters.rampAt(950.milliseconds).shouldNotBeNull()
    after.start shouldBe 0f
    after.end shouldBe 0f
  }

  private fun audioTrack(): AVMutableCompositionTrack =
    checkNotNull(composition.addMutableTrackWithMediaType(AVMediaTypeAudio, kCMPersistentTrackID_Invalid))

  private fun placed(
    start: Duration,
    end: Duration,
    gain: ResolvedGain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
  ): Placement = Placement(laidClip(SOURCE, start, end - start, gain = gain), start, end)

  private fun AVMutableAudioMix?.parametersOf(media: AVMutableCompositionTrack): AVAudioMixInputParameters =
    withClue("no parameters for track ${media.trackID}") {
      withClue("no mix was written") { this.shouldNotBeNull() }
        .inputParameters
        .filterIsInstance<AVAudioMixInputParameters>()
        .singleOrNull { it.trackID == media.trackID }
        .shouldNotBeNull()
    }

  private fun AVAudioMixInputParameters.rampAt(time: Duration): VolumeRamp? =
    memScoped {
      val start = alloc<FloatVar>()
      val end = alloc<FloatVar>()
      val range = alloc<CMTimeRange>()
      val found =
        getVolumeRampForTime(time.toCMTime(), startVolume = start.ptr, endVolume = end.ptr, timeRange = range.ptr)
      if (!found) return@memScoped null
      VolumeRamp(start.value, end.value, range.start.readValue().toDuration(), range.duration.readValue().toDuration())
    }

  private class VolumeRamp(
    val start: Float,
    val end: Float,
    val rangeStart: Duration,
    val rangeLength: Duration,
  )

  private companion object {
    val SOURCE = MediaSource.of("/clips/sound.m4a")
  }
}
