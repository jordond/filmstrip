package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.CompositionDiff
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.diff
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class SolidClipTest {
  @Test
  fun aSolidOfTheSameColourAndLengthIsEqual() {
    MediaSource.ofSolid(BLACK, 2.seconds) shouldBe MediaSource.Solid(BLACK, 2.seconds)
    MediaSource.Solid(BLACK, 2.seconds).hashCode() shouldBe MediaSource.Solid(BLACK, 2.seconds).hashCode()
  }

  @Test
  fun aColourChangeIsAStructuralChange() {
    val black = Composition(listOf(Track(listOf(Clip(MediaSource.Solid(BLACK, 2.seconds))))))
    val red = Composition(listOf(Track(listOf(Clip(MediaSource.Solid(RED, 2.seconds))))))

    diff(black, red) shouldBe CompositionDiff.Structural
  }

  @Test
  fun aSolidClipIsAsLongAsItIsHeldAndATrimWins() {
    Clip(MediaSource.Solid(BLACK, 5.seconds)).duration shouldBe 5.seconds
    Clip(MediaSource.Solid(BLACK, 5.seconds), TimeRange(1.seconds, 3.seconds)).duration shouldBe 2.seconds
    Clip(MediaSource.Solid(BLACK, 5.seconds), TimeRange.from(1.seconds)).duration shouldBe 4.seconds
  }

  @Test
  fun onlyAPictureHeldForASpanReportsHowLong() {
    MediaSource.Solid(BLACK, 3.seconds).heldFor shouldBe 3.seconds
    MediaSource.Image(ImageSource.of("/photos/beach.jpg"), 4.seconds).heldFor shouldBe 4.seconds
    MediaSource.of("/clips/beach.mp4").heldFor.shouldBeNull()
  }

  @Test
  fun aSolidDescribesItsColourAndLength() {
    MediaSource.Solid(RED, 2.seconds).describe() shouldBe "solid #ffff0000 for 2s"
    MediaSource.Solid(0x0000FF, 2.seconds).describe() shouldBe "solid #000000ff for 2s"
  }

  @Test
  fun aSolidIsOnePixelOfVideoWithNoCadenceAndNoAudio() {
    val info = solidMediaInfoOf(MediaSource.Solid(BLACK, 2.seconds))

    info.duration shouldBe 2.seconds
    info.audioTracks shouldBe emptyList()
    info.video?.displaySize shouldBe Size(1, 1)
    info.video?.frameRate.shouldBeNull()
    info.video?.hasAlpha shouldBe false
  }

  @Test
  fun aSolidCarriesAlphaOnlyWhenItsColourDoes() {
    solidMediaInfoOf(MediaSource.Solid(0x80FF0000.toInt(), 2.seconds)).video?.hasAlpha shouldBe true
    solidMediaInfoOf(MediaSource.Solid(0xFEFFFFFF.toInt(), 2.seconds)).video?.hasAlpha shouldBe true
  }

  @Test
  fun theBuildersAppendASolid() {
    val edit =
      compositionOf {
        solid(RED, 2.seconds)
        track { solid(BLACK, 1.seconds) }
      }

    edit.tracks[0]
      .clips
      .single()
      .source shouldBe MediaSource.Solid(RED, 2.seconds)
    edit.tracks[1]
      .clips
      .single()
      .source shouldBe MediaSource.Solid(BLACK, 1.seconds)
  }
}

private const val BLACK = 0xFF000000.toInt()
private const val RED = 0xFFFF0000.toInt()
