package dev.jordond.filmstrip.sample.tryit

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.Saturation
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.sample.samplePresets
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

class BeachEditTest {
  private val source = MediaSource.ofUri("blob:beach")

  private val EditComposition.trim: TimeRange?
    get() =
      tracks
        .single()
        .clips
        .single()
        .trim

  @Test
  fun `the clip is the measured beach sunset`() {
    val preset = samplePresets.first { it.file == "beach-sunset.mp4" }
    beachClip shouldBe preset
    preset.bytes shouldBe 1_441_069L
    preset.duration shouldBe 12.seconds
  }

  @Test
  fun `no steps plays the clip raw`() {
    val edit = beachEdit(source, Steps())
    edit.trim.shouldBeNull()
    edit.effects.shouldBeEmpty()
  }

  @Test
  fun `trim alone keeps two seconds to eight`() {
    val edit = beachEdit(source, Steps(trim = true))
    edit.trim shouldBe TimeRange(2.seconds, 8.seconds)
    edit.effects.shouldBeEmpty()
  }

  @Test
  fun `grade alone sets the saturation and the contrast`() {
    val edit = beachEdit(source, Steps(grade = true))
    edit.trim.shouldBeNull()
    edit.effects.size shouldBe 2
    edit.effects[0].shouldBeInstanceOf<Saturation>().factor shouldBe 2f
    edit.effects[1].shouldBeInstanceOf<Contrast>().factor shouldBe 1.15f
  }

  @Test
  fun `title alone draws the text in the hero's place`() {
    val edit = beachEdit(source, Steps(title = true))
    edit.trim.shouldBeNull()
    val title = edit.effects.single().shouldBeInstanceOf<TextOverlay>()
    title.text shouldBe "SUMMER '96"
    title.anchor shouldBe Anchor(0.5f, 0.18f)
    title.style shouldBe titleStyle
  }

  @Test
  fun `every step on lands the trim the grade and the title together`() {
    val edit = beachEdit(source, Steps(trim = true, grade = true, title = true))
    edit.trim shouldBe TimeRange(2.seconds, 8.seconds)
    edit.effects.size shouldBe 3
    edit.effects[0].shouldBeInstanceOf<Saturation>().factor shouldBe 2f
    edit.effects[1].shouldBeInstanceOf<Contrast>().factor shouldBe 1.15f
    edit.effects[2].shouldBeInstanceOf<TextOverlay>().text shouldBe "SUMMER '96"
  }

  @Test
  fun `a position with no trim is already source time`() {
    sourcePosition(5250.milliseconds, Steps()) shouldBe 5250.milliseconds
  }

  @Test
  fun `a position mid trim lands past the trim's start in source time`() {
    sourcePosition(3250.milliseconds, Steps(trim = true)) shouldBe 5250.milliseconds
  }

  @Test
  fun `a source time inside the trim lands the same distance past its start`() {
    editPosition(5.seconds, Steps(trim = true)) shouldBe 5.seconds - TRIM_RANGE.start
    sourcePosition(editPosition(5.seconds, Steps(trim = true)), Steps(trim = true)) shouldBe 5.seconds
  }

  @Test
  fun `a source time the trim cuts lands on the nearest end of the trim`() {
    editPosition(1.seconds, Steps(trim = true)) shouldBe Duration.ZERO
    editPosition(10.seconds, Steps(trim = true)) shouldBe TRIM_RANGE.endInclusive - TRIM_RANGE.start
  }

  @Test
  fun `with no trim the edit position is the source time`() {
    editPosition(10.seconds, Steps()) shouldBe 10.seconds
  }
}
