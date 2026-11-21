@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.AudioLevel
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.EnvelopeAnchor
import dev.jordond.filmstrip.edit.EnvelopePoint
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.kenBurns
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.motion.Easing
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotBeEmpty
import io.kotest.matchers.floats.plusOrMinus
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The clip model checked against what the library's own builders write for the same edit.
 */
class SequenceTest {
  @Test
  fun `one untouched clip builds the edit a single clip always built`() {
    val sequence = clipsOf(FIRST)

    EditState().composition(sequence) shouldBe
      compositionOf {
        clip(FIRST)
        fill(Fill.Solid(EditState.BLACK))
      }
  }

  @Test
  fun `a trimmed, muted, snapped clip builds what the clip builder writes`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 2.5f
      trimEndSeconds = 7.25f
      audio = AudioLevel.Mute
      snapWithinSeconds = 0.5f
    }

    EditState().composition(sequence).clips shouldContainExactly
      compositionOf {
        clip(FIRST) {
          trim(2500.milliseconds, 7250.milliseconds)
          audio(AudioLevel.Mute)
          snapWithin(500.milliseconds)
        }
      }.clips
  }

  @Test
  fun `clips start where the clips before them end`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 2.5f
      trimEndSeconds = 7.25f
    }
    sequence.addProbed(SECOND, 6.seconds).speed = 2.0
    sequence.addProbed(THIRD, 4.seconds)

    val built = EditState().composition(sequence).clips
    val lengths = listOf(built[0].duration!!, built[1].withTrim(TimeRange(Duration.ZERO, 6.seconds)).duration!!)
    sequence.clipStarts() shouldContainExactly listOf(Duration.ZERO, lengths[0], lengths[0] + lengths[1])
    sequence.clipStarts()[2] shouldBe 7750.milliseconds
  }

  @Test
  fun `the sequence runs as long as the composition it builds`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 1f
      trimEndSeconds = 4f
    }
    sequence.addProbed(SECOND, 6.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 0f
      trimEndSeconds = 5f
      speed = 2.0
    }

    sequence.duration shouldBe EditState().composition(sequence).duration
    sequence.duration shouldBe 5500.milliseconds
  }

  @Test
  fun `an untrimmed clip is measured by its probe and one not probed yet has no length`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    sequence.duration shouldBe 10.seconds

    sequence.add(ClipContent.Video(SECOND), "second")
    sequence.duration.shouldBeNull()
    sequence.clipStarts() shouldContainExactly listOf(Duration.ZERO, 10.seconds)
  }

  @Test
  fun `a colour held after the clip builds the solid the end card built`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    sequence.add(ClipContent.Solid, "colour").apply {
      color = CARD
      holdSeconds = 1.5f
    }

    EditState().composition(sequence).clips shouldContainExactly
      compositionOf {
        clip(FIRST)
        solid(CARD, 1500.milliseconds)
      }.clips
    sequence.duration shouldBe 11500.milliseconds
  }

  @Test
  fun `the strip composition is the selected clip alone and untrimmed`() {
    val sequence = Sequence()
    val first =
      sequence.addProbed(FIRST, 10.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 2f
        trimEndSeconds = 6f
      }
    sequence.add(ClipContent.Solid, "colour")

    EditState().clipComposition(first).clips shouldContainExactly compositionOf { clip(FIRST) }.clips
  }

  @Test
  fun `removing the selected clip selects the one that takes its place`() {
    val sequence = clipsOf(FIRST, SECOND, THIRD)
    val (first, second, third) = sequence.clips

    sequence.select(second.id)
    sequence.remove(second.id)
    sequence.selected shouldBe third.id

    sequence.remove(third.id)
    sequence.selected shouldBe first.id

    sequence.remove(first.id)
    sequence.selected.shouldBeNull()
    sequence.clips.shouldBeEmpty()
  }

  @Test
  fun `removing another clip keeps the selection`() {
    val sequence = clipsOf(FIRST, SECOND, THIRD)
    val (first, second, third) = sequence.clips

    sequence.selected shouldBe third.id
    sequence.remove(first.id)
    sequence.selected shouldBe third.id
    sequence.clips shouldContainExactly listOf(second, third)
  }

  @Test
  fun `moving a clip changes the order it plays in and keeps its name`() {
    val sequence = clipsOf(FIRST, SECOND, THIRD)
    val (first, second, third) = sequence.clips

    sequence.move(third.id, 0)
    sequence.clips shouldContainExactly listOf(third, first, second)

    sequence.move(third.id, 1)
    sequence.clips shouldContainExactly listOf(first, third, second)

    sequence.move(first.id, 9)
    sequence.clips shouldContainExactly listOf(third, second, first)
  }

  @Test
  fun `reset puts a clip's knobs back and opens the trim over the whole file`() {
    val sequence = Sequence()
    val clip =
      sequence.addProbed(FIRST, 10.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 2f
        trimEndSeconds = 6f
        snapWithinSeconds = 0.5f
        speed = 2.0
        fadeInSeconds = 0.5f
        fadeOutSeconds = 0.25f
        audio = AudioLevel.Volume(0.5f)
        holdSeconds = 4f
        color = CARD
      }

    sequence.reset()

    val fresh = Sequence().apply { addProbed(FIRST, 10.seconds) }
    EditState().composition(sequence) shouldBe EditState().composition(fresh)
    clip.trimEndSeconds shouldBe 10f
    clip.holdSeconds shouldBe ClipEdit.DEFAULT_HOLD_SECONDS
    clip.color shouldBe EditState.BLACK
    sequence.clips shouldContainExactly listOf(clip)
  }

  @Test
  fun `a source time in the middle of a fast clip plays where its start and speed put it`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds).apply {
      trimEnabled = true
      trimStartSeconds = 2.5f
      trimEndSeconds = 7.25f
    }
    val fast =
      sequence.addProbed(SECOND, 6.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 1f
        trimEndSeconds = 5f
        speed = 2.0
      }
    val start = sequence.clipStarts()[1]

    sequence.timeOf(fast.id, 3.seconds) shouldBe start + 1.seconds
    sequence.timeOf(fast.id, 3.seconds) shouldBe 5750.milliseconds
    sequence.sourceTimeOf(fast.id, start + 1.seconds) shouldBe 3.seconds
    sequence.sourceTimeOf(fast.id, start + 500.milliseconds) shouldBe 2.seconds
  }

  @Test
  fun `a time the clip does not play is held at the nearer end of its run`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    val fast =
      sequence.addProbed(SECOND, 6.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 1f
        trimEndSeconds = 5f
        speed = 2.0
      }
    val start = sequence.clipStarts()[1]

    sequence.timeOf(fast.id, 500.milliseconds) shouldBe start
    sequence.timeOf(fast.id, 5800.milliseconds) shouldBe start + fast.duration!!
    sequence.sourceTimeOf(fast.id, 4.seconds) shouldBe 1.seconds
    sequence.sourceTimeOf(fast.id, sequence.duration!!) shouldBe 5.seconds
    sequence.timeOf(ClipId(99), 1.seconds).shouldBeNull()
    sequence.sourceTimeOf(ClipId(99), 1.seconds).shouldBeNull()
  }

  @Test
  fun `the clip under a time is the one playing there`() {
    val sequence = Sequence()
    val first = sequence.addProbed(FIRST, 10.seconds)
    val waiting = sequence.add(ClipContent.Video(SECOND), "second")
    val last = sequence.addProbed(THIRD, 4.seconds).apply { speed = 2.0 }
    val boundary = sequence.clipStarts()[2]

    sequence.clipAt(4.seconds) shouldBe first
    sequence.clipAt(boundary - 1.milliseconds) shouldBe first
    sequence.clipAt(boundary) shouldBe last
    sequence.clipAt(boundary + last.duration!!) shouldBe last
    sequence.clipAt(boundary + 1.seconds) shouldBe last
    sequence.clips[1] shouldBe waiting
    sequence.startOf(last.id) shouldBe boundary
    sequence.startOf(ClipId(99)).shouldBeNull()
  }

  @Test
  fun `the strip over one fast, trimmed clip lays its whole source out at the pace it was shot`() {
    val fast =
      Sequence().addProbed(SECOND, 6.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 1f
        trimEndSeconds = 5f
        speed = 2.0
      }

    EditState().clipComposition(fast).clips shouldContainExactly compositionOf { clip(SECOND) }.clips
  }

  @Test
  fun `a clip at twice the speed runs half as long`() {
    val clip =
      Sequence().addProbed(FIRST, 10.seconds).apply {
        trimEnabled = true
        trimStartSeconds = 2.5f
        trimEndSeconds = 7.25f
        speed = 2.0
      }
    val built = clip.clip()

    built shouldBe
      compositionOf {
        clip(FIRST) {
          trim(2500.milliseconds, 7250.milliseconds)
          speed(2.0)
        }
      }.clips.single()
    built.duration shouldBe built.withSpeed(1.0).duration!! / 2
    clip.duration shouldBe 2375.milliseconds
  }

  @Test
  fun `the speed slider runs the library's range on a log scale`() {
    speedOfSlider(0f) shouldBe CLIP_SPEED_RANGE.start
    speedOfSlider(0.5f) shouldBe 1.0
    speedOfSlider(0.7f) shouldBe 4.0
    speedOfSlider(1f) shouldBe CLIP_SPEED_RANGE.endInclusive
    sliderOfSpeed(4.0) shouldBe (0.7f plusOrMinus 0.0001f)
    sliderOfSpeed(0.25) shouldBe (0.3f plusOrMinus 0.0001f)
  }

  @Test
  fun `an envelope edited point by point reaches the clip with its end anchor`() {
    val clip = Sequence().addProbed(FIRST, 10.seconds)
    clip.audioMode = ClipAudioMode.Envelope
    clip.envelope.shouldBeEmpty()

    repeat(3) { clip.addEnvelopePoint() }
    clip.setEnvelopePoint(1, EnvelopePoint(1500.milliseconds, 0.35f))
    clip.setEnvelopePoint(2, EnvelopePoint(500.milliseconds, 0.8f, EnvelopeAnchor.End))

    val expected =
      AudioLevel.Envelope(
        listOf(
          EnvelopePoint(Duration.ZERO, 1f),
          EnvelopePoint(1500.milliseconds, 0.35f),
          EnvelopePoint(500.milliseconds, 0.8f, EnvelopeAnchor.End),
        ),
      )
    clip.clip() shouldBe compositionOf { clip(FIRST) { audio(expected) } }.clips.single()
    clip.audioMode shouldBe ClipAudioMode.Envelope

    clip.removeEnvelopePoint(0)
    clip.envelope shouldContainExactly expected.points.drop(1)
  }

  @Test
  fun `switching the level keeps what the mode already held and starts the others at no change`() {
    val clip = Sequence().addProbed(FIRST, 10.seconds)

    clip.audioMode = ClipAudioMode.Volume
    clip.audio shouldBe AudioLevel.Volume(1f)
    clip.audio = AudioLevel.Volume(0.4f)
    clip.audioMode = ClipAudioMode.Volume
    clip.audio shouldBe AudioLevel.Volume(0.4f)

    clip.audioMode = ClipAudioMode.Mute
    clip.audio shouldBe AudioLevel.Mute
    clip.audioMode = ClipAudioMode.Inherit
    clip.audio shouldBe AudioLevel.Inherit
  }

  @Test
  fun `the audio track picked for a clip reaches it`() {
    val clip = Sequence().addProbed(FIRST, 10.seconds).apply { audioTrack = AudioTrackId(2) }

    clip.clip(open = true) shouldBe compositionOf { clip(FIRST) { audioTrack(AudioTrackId(2)) } }.clips.single()
  }

  @Test
  fun `a picture held three seconds with a pan builds the image clip the library writes`() {
    val sequence = Sequence()
    sequence.add(ClipContent.Image(PICTURE), "picture").apply {
      holdSeconds = 3f
      panEnabled = true
      panFrom = NormalizedRect.Full
      panTo = CENTRE_HALF
      panEasing = Easing.EaseOut
    }

    EditState().composition(sequence).clips shouldContainExactly
      compositionOf {
        image(PICTURE, 3.seconds) {
          effects { kenBurns(NormalizedRect.Full, CENTRE_HALF, Easing.EaseOut) }
        }
      }.clips
  }

  @Test
  fun `a pan switched off or held back for the stage leaves the clip without one`() {
    val sequence = Sequence()
    val picture = sequence.add(ClipContent.Image(PICTURE), "picture").apply { panTo = CENTRE_HALF }
    val plain = compositionOf { image(PICTURE, ClipEdit.DEFAULT_HOLD_SECONDS.asSeconds()) }.clips

    EditState().composition(sequence).clips shouldContainExactly plain

    picture.panEnabled = true
    EditState().composition(sequence, unpanned = picture.id).clips shouldContainExactly plain
    EditState()
      .composition(sequence)
      .clips
      .single()
      .effects
      .shouldNotBeEmpty()
  }

  @Test
  fun `a colour never pans`() {
    val sequence = Sequence()
    sequence.add(ClipContent.Solid, "colour").apply {
      color = CARD
      panEnabled = true
    }

    EditState().composition(sequence).clips shouldContainExactly
      compositionOf { solid(CARD, ClipEdit.DEFAULT_HOLD_SECONDS.asSeconds()) }.clips
  }

  @Test
  fun `a colour between two clips keeps its place`() {
    val sequence = Sequence()
    sequence.addProbed(FIRST, 10.seconds)
    sequence.addProbed(SECOND, 6.seconds)
    sequence.add(ClipContent.Solid, "colour", at = 1).apply {
      color = CARD
      holdSeconds = 1.5f
    }

    EditState().composition(sequence).clips shouldContainExactly
      compositionOf {
        clip(FIRST)
        solid(CARD, 1500.milliseconds)
        clip(SECOND)
      }.clips
    val built = EditState().composition(sequence).clips
    sequence.clipStarts()[2] shouldBe 10.seconds + built[1].duration!!
  }

  @Test
  fun `a hold, a colour or a pan off its start marks the clip touched and reset clears it`() {
    val sequence = Sequence()
    val picture = sequence.add(ClipContent.Image(PICTURE), "picture")
    val colour = sequence.add(ClipContent.Solid, "colour")
    picture.touched shouldBe false
    colour.touched shouldBe false

    picture.holdSeconds = 4.5f
    colour.color = CARD
    picture.touched shouldBe true
    colour.touched shouldBe true

    sequence.reset()
    picture.panEnabled = true
    picture.touched shouldBe true

    sequence.reset()
    picture.touched shouldBe false
    colour.touched shouldBe false
    picture.panTo shouldBe ClipEdit.DEFAULT_PAN_TO
  }

  private companion object {
    val FIRST = MediaSource.of("/tmp/first.mp4")
    val SECOND = MediaSource.of("/tmp/second.mp4")
    val THIRD = MediaSource.of("/tmp/third.mp4")
    val PICTURE = ImageSource.of("/tmp/picture.png")
    val CENTRE_HALF = NormalizedRect(0.25f, 0.25f, 0.75f, 0.75f)
    const val CARD = 0xFF1A1A21.toInt()
  }
}
