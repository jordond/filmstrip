@file:OptIn(ExperimentalFilmstripApi::class)

package dev.jordond.filmstrip.sample

import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.canonicalOrder
import dev.jordond.filmstrip.effects.color.brightness
import dev.jordond.filmstrip.effects.color.saturation
import dev.jordond.filmstrip.effects.geometry.flip
import dev.jordond.filmstrip.effects.geometry.kenBurns
import dev.jordond.filmstrip.effects.geometry.rotate
import dev.jordond.filmstrip.geometry.FlipAxis
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The grade and the transform set on one clip against the ones set on the whole edit.
 */
class ClipScopeTest {
  @Test
  fun `a clip grade lands on the clip and leaves the edit's effects alone`() {
    val sequence = clipsOf(FIRST, SECOND)
    sequence.clips[1].grade.saturation = 0.5f

    val built = EditState().composition(sequence)
    built.effects.shouldBeEmpty()
    built.clips shouldContainExactly
      compositionOf {
        clip(FIRST)
        clip(SECOND) { effects { saturation(0.5f) } }
      }.clips
  }

  @Test
  fun `a grade on the edit and one on a clip both reach the composition`() {
    val sequence = clipsOf(FIRST, SECOND)
    sequence.clips[0].grade.brightness = 1.2f
    val edit = EditState().apply { grade.saturation = 0.5f }

    val built = edit.composition(sequence)
    built.effects shouldBe compositionOf { effects { saturation(0.5f) } }.effects
    built.clips shouldContainExactly
      compositionOf {
        clip(FIRST) { effects { brightness(1.2f) } }
        clip(SECOND)
      }.clips
  }

  @Test
  fun `a clip's turn, grade and pan are written in the order the library runs them`() {
    val sequence = Sequence()
    val picture =
      sequence.add(ClipContent.Image(PICTURE), "picture").apply {
        holdSeconds = 3f
        panEnabled = true
        grade.brightness = 1.2f
        transform.rotationDegrees = 90
        transform.flipHorizontal = true
      }

    val expected =
      compositionOf {
        image(PICTURE, 3.seconds) {
          effects {
            rotate(90)
            flip(FlipAxis.Horizontal)
            brightness(1.2f)
            kenBurns(NormalizedRect.Full, ClipEdit.DEFAULT_PAN_TO)
          }
        }
      }.clips.single()

    val built = EditState().composition(sequence).clips.single()
    built.effects shouldContainExactly canonicalOrder(expected.effects)
    canonicalOrder(built.effects) shouldContainExactly built.effects
    picture.touched shouldBe true
  }

  @Test
  fun `the scope switch points the panels at the selected clip or at the edit`() {
    val state =
      SampleAppState(
        filmstrip = Filmstrip { autoRegister(false) },
        scope = CoroutineScope(Dispatchers.Unconfined),
      )
    state.addSolid()
    val clip = state.sequence.selectedClip!!

    state.gradeInScope shouldBe state.edit.grade
    state.transformInScope shouldBe state.edit.transform

    state.edit.scope = EditScope.Clip
    state.gradeInScope shouldBe clip.grade
    state.transformInScope shouldBe clip.transform

    state.edit.reset()
    state.edit.scope shouldBe EditScope.Edit
  }

  @Test
  fun `reset clears a clip's grade and turn`() {
    val sequence = clipsOf(FIRST)
    val clip = sequence.clips.single()
    clip.grade.sepia = 0.4f
    clip.transform.flipVertical = true
    clip.touched shouldBe true

    sequence.reset()

    clip.touched shouldBe false
    EditState().composition(sequence).clips shouldContainExactly compositionOf { clip(FIRST) }.clips
  }

  private companion object {
    val FIRST = MediaSource.of("/tmp/first.mp4")
    val SECOND = MediaSource.of("/tmp/second.mp4")
    val PICTURE = ImageSource.of("/tmp/picture.png")
  }
}
