package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import io.kotest.matchers.maps.shouldNotBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.mockk.every
import io.mockk.mockk
import io.mockk.mockkStatic
import io.mockk.unmockkAll
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * Where an item's [SlotRepeat] is laid, in an export, in a player and in what a frame reader runs.
 */
class SlotRepeatLoweringTest {
  private val bounds = samplesAt(sourceRate = 30, seconds = 4)

  @BeforeTest
  fun stubAndroid() {
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    every { Uri.parse(any()) } returns mockk(relaxed = true)
  }

  @AfterTest
  fun releaseAndroid() {
    unmockkAll()
  }

  @Test
  fun `an export opens a slowed item on a repeat of every slot its samples are held for`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)
    val composition = resolvedTrack(listOf(clip), frameRate = 30)

    val effects =
      composition
        .toMedia3(
          readsOf(listOf(clip), bounds),
        ).sequences
        .first()
        .editedMediaItems
        .single()
        .effects

    val expected = checkNotNull(ShownFrames.of(clip, bounds, 30)).repeatsUs
    expected.shouldNotBeEmpty()
    val repeat = effects.videoEffects.first().shouldBeInstanceOf<SlotRepeat>()
    repeat.repeatsUs shouldBe expected
    repeat.startUs shouldBe 0L
    repeat.toleranceUs shouldBe slotRepeatToleranceUs(30)
  }

  @Test
  fun `an item is keyed to where it starts on the timeline`() {
    val first = resolvedClip(30, 1.seconds, speed = 0.5, source = "first.mp4")
    val second = resolvedClip(30, 1.seconds, speed = 0.5, at = first.span.endExclusive!!, source = "second.mp4")
    val composition = resolvedTrack(listOf(first, second), frameRate = 30)

    val items =
      composition
        .toMedia3(readsOf(listOf(first, second), bounds))
        .sequences
        .first()
        .editedMediaItems

    items.map {
      it.effects.videoEffects
        .first()
        .shouldBeInstanceOf<SlotRepeat>()
        .startUs
    } shouldBe
      listOf(0L, outputSlotTime(outputSlotOf(first.span.endExclusive!!, 30), 30).inWholeMicroseconds)
  }

  @Test
  fun `an export lays no repeat on an item that shows each sample once`() {
    val clip = resolvedClip(30, 1.seconds)
    val composition = resolvedTrack(listOf(clip), frameRate = 30)

    val effects =
      composition
        .toMedia3(
          readsOf(listOf(clip), bounds),
        ).sequences
        .first()
        .editedMediaItems
        .single()
        .effects

    effects.videoEffects.filterIsInstance<SlotRepeat>() shouldBe emptyList()
  }

  // media3's limit: with no samples read there is no slot to name a repeat for.
  @Test
  fun `an export of a slowed item whose samples were never read lays no repeat`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)

    val effects =
      resolvedTrack(listOf(clip), frameRate = 30)
        .toMedia3(Media3ClipReads.None)
        .sequences
        .first()
        .editedMediaItems
        .single()
        .effects

    effects.videoEffects.filterIsInstance<SlotRepeat>() shouldBe emptyList()
  }

  @Test
  fun `a player opens a slowed item on a repeat of every slot its samples are held for`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)
    val composition = resolvedTrack(listOf(clip), frameRate = 30)

    val item =
      composition
        .toMedia3Preview(readsOf(listOf(clip), bounds))
        .composition.sequences
        .first()
        .editedMediaItems

    item
      .single()
      .effects.videoEffects
      .first()
      .shouldBeInstanceOf<SlotRepeat>()
  }

  // A player holds a still for its length in whole milliseconds, so after many of one slot's length the clip it came
  // before starts earlier than the exact lengths say. An export lays by the exact lengths.
  @Test
  fun `a player keys an item by the lengths it holds the stills before it for`() {
    val stills = (0 until STILLS).map { still(at = outputSlotTime(it.toLong(), 30)) }
    val clip = resolvedClip(30, 1.seconds, speed = 0.5, at = outputSlotTime(STILLS.toLong(), 30))
    val track = resolvedTrack(stills + clip, frameRate = 30)
    val reads = readsOf(listOf(clip), bounds)

    val held = STILLS * SLOT_MILLISECONDS * MICROS_PER_MILLISECOND
    val exact = outputSlotTime(STILLS.toLong(), 30).inWholeMicroseconds

    startOfRepeat(track.toMedia3Preview(reads).composition) shouldBe held
    startOfRepeat(track.toMedia3(reads)) shouldBe exact
  }

  private fun startOfRepeat(composition: androidx.media3.transformer.Composition): Long =
    composition.sequences
      .first()
      .editedMediaItems
      .last()
      .effects.videoEffects
      .first()
      .shouldBeInstanceOf<SlotRepeat>()
      .startUs

  private fun still(at: kotlin.time.Duration): ResolvedClip {
    val length = outputSlotTime(1, 30)
    return ResolvedClip(
      source = MediaSource.Image(ImageSource.of("/fixtures/still.jpeg"), length),
      info = imageMediaInfoOf(Size(640, 360), EXIF_ORIENTATION_NORMAL, "jpeg", length),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + length),
      sourceIndex = 0,
    )
  }

  // A frame reader draws the one frame it is asked for, so it never repeats it, and it moves that frame onto the slot
  // asked for instead.
  @Test
  fun `a reader draws a slot a held sample is shown on at that slot's own time`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)
    val preview = resolvedTrack(listOf(clip), frameRate = 30).toMedia3Preview(readsOf(listOf(clip), bounds))
    val first = outputSlotTime(0, 30)
    val held = outputSlotTime(1, 30)

    val (onFirst, onHeld) = preview.readbacksAt(listOf(first, held), revision = 1).shouldNotBeNull()

    onFirst.effects.filterIsInstance<SlotRestamp>() shouldBe emptyList()
    onHeld.effects.filterIsInstance<SlotRestamp>() shouldBe
      listOf(
        SlotRestamp(first.inWholeMicroseconds, held.inWholeMicroseconds),
        SlotRestamp(held.inWholeMicroseconds, first.inWholeMicroseconds),
      )
    onHeld.effects.filterIsInstance<SlotRepeat>() shouldBe emptyList()
  }

  @Test
  fun `a later slot of a held sample is seeked to through the slot its sample is written on`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)
    val preview = resolvedTrack(listOf(clip), frameRate = 30).toMedia3Preview(readsOf(listOf(clip), bounds))

    preview.firstSlotOf(outputSlotTime(0, 30)) shouldBe outputSlotTime(0, 30)
    preview.firstSlotOf(outputSlotTime(1, 30)) shouldBe outputSlotTime(0, 30)
    preview.firstSlotOf(outputSlotTime(2, 30)) shouldBe outputSlotTime(2, 30)
    preview.firstSlotOf(outputSlotTime(3, 30)) shouldBe outputSlotTime(2, 30)
  }

  @Test
  fun `a clip that holds nothing is seeked to where it is asked`() {
    val clip = resolvedClip(30, 1.seconds)
    val preview = resolvedTrack(listOf(clip), frameRate = 30).toMedia3Preview(readsOf(listOf(clip), bounds))

    (0L..5L).forEach { preview.firstSlotOf(outputSlotTime(it, 30)) shouldBe outputSlotTime(it, 30) }
  }

  @Test
  fun `a preview pins a repeat it rests on and lets go of any other slot`() {
    val clip = resolvedClip(30, 1.seconds, speed = 0.5)
    val preview = resolvedTrack(listOf(clip), frameRate = 30).toMedia3Preview(readsOf(listOf(clip), bounds))
    val repeat =
      preview.composition.sequences
        .first()
        .editedMediaItems
        .single()
        .effects.videoEffects
        .first()
        .shouldBeInstanceOf<SlotRepeat>()

    preview.pinSlot(outputSlotTime(1, 30))
    repeat.pin.shouldNotBeNull().atUs shouldBe outputSlotTime(1, 30).inWholeMicroseconds
    preview.pinSlot(outputSlotTime(2, 30))
    repeat.pin.shouldNotBeNull().atUs shouldBe null
    preview.pinSlot(outputSlotTime(3, 30))
    preview.pinSlot(null)
    repeat.pin.shouldNotBeNull().atUs shouldBe null
  }

  private companion object {
    // Enough one slot stills for the whole milliseconds they are held for to drift past a quarter of a slot.
    const val STILLS = 40
    const val SLOT_MILLISECONDS = 33L
    const val MICROS_PER_MILLISECOND = 1_000L
  }
}
