package dev.jordond.filmstrip.edit

import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class WithersTest {
  private val clip =
    Clip(
      source = MediaSource.of("/fixtures/clip.mp4"),
      trim = TimeRange.of(1.seconds, 3.seconds),
      audio = AudioLevel.Volume(0.5f),
      snapWithin = 200.milliseconds,
      fadeIn = 100.milliseconds,
      fadeOut = 300.milliseconds,
    )

  private val track =
    Track(
      clips = listOf(clip),
      content = TrackContent.Audio,
      audio = AudioLevel.Volume(0.25f),
      start = 2.seconds,
      looping = true,
      fadeIn = 400.milliseconds,
      fadeOut = 500.milliseconds,
    )

  @Test
  fun eachClipWitherChangesOnlyItsOwnProperty() {
    clip.withFadeIn(1.seconds) shouldBe
      Clip(clip.source, clip.trim, clip.effects, clip.audio, clip.snapWithin, 1.seconds, clip.fadeOut)
    clip.withFadeOut(1.seconds) shouldBe
      Clip(clip.source, clip.trim, clip.effects, clip.audio, clip.snapWithin, clip.fadeIn, 1.seconds)
  }

  @Test
  fun eachTrackWitherChangesOnlyItsOwnProperty() {
    fun copy(
      content: TrackContent = track.content,
      start: Duration = track.start,
      looping: Boolean = track.looping,
      fadeIn: Duration = track.fadeIn,
      fadeOut: Duration = track.fadeOut,
    ) = Track(track.clips, content, track.effects, track.audio, start, looping, fadeIn, fadeOut)

    track.withContent(TrackContent.AudioAndVideo) shouldBe copy(content = TrackContent.AudioAndVideo)
    track.withStart(5.seconds) shouldBe copy(start = 5.seconds)
    track.withLooping(false) shouldBe copy(looping = false)
    track.withFadeIn(1.seconds) shouldBe copy(fadeIn = 1.seconds)
    track.withFadeOut(1.seconds) shouldBe copy(fadeOut = 1.seconds)
  }
}
