package dev.jordond.filmstrip.media3.internal

import android.net.Uri
import android.util.SparseArray
import androidx.media3.common.C
import androidx.media3.common.audio.SonicAudioProcessor
import androidx.media3.common.audio.SpeedProvider
import androidx.media3.common.util.SpeedProviderUtil
import androidx.media3.common.util.SpeedProviderUtil.SpeedProviderMapper
import androidx.media3.transformer.EditedMediaItem
import androidx.media3.transformer.EditedMediaItemSequence
import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.CLIP_SPEED_RANGE
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputLengthOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.edit.sourceLengthOf
import dev.jordond.filmstrip.edit.sourceOffsetAt
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.media3.internal.bounds.ClipWindow
import dev.jordond.filmstrip.media3.internal.bounds.FrameBounds
import dev.jordond.filmstrip.media3.internal.bounds.Media3ClipReads
import dev.jordond.filmstrip.media3.internal.preview.toMedia3Preview
import dev.jordond.filmstrip.transform.internal.nearestSourceFrame
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.sourceTimeOfSlot
import io.kotest.assertions.withClue
import io.kotest.matchers.comparables.shouldBeLessThanOrEqualTo
import io.kotest.matchers.doubles.plusOrMinus
import io.kotest.matchers.longs.shouldBeInRange
import io.kotest.matchers.longs.shouldBeLessThanOrEqual
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.just
import io.mockk.mockk
import io.mockk.mockkConstructor
import io.mockk.mockkStatic
import io.mockk.runs
import io.mockk.unmockkAll
import kotlin.math.absoluteValue
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration
import kotlin.time.Duration.Companion.hours
import kotlin.time.Duration.Companion.microseconds
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Where the lowering lays each clip's video and audio on media3's sequences.
 *
 * Video lands on the output frame grid and audio stays exact, so a track carrying both lowers to a sequence of each.
 * Every expected length is read off the shared frame grid rather than written down here, and the clips are windowed off
 * whole milliseconds so a lowering that clipped in milliseconds cannot land on the same numbers by accident.
 */
class Media3FrameGridTest {
  @BeforeTest
  fun stubAndroid() {
    mockkStatic(Uri::class)
    every { Uri.fromFile(any()) } returns mockk(relaxed = true)
    every { Uri.parse(any()) } returns mockk(relaxed = true)
    // The mixer a run of clips takes keeps its matrices in a SparseArray, which the host's stubs throw from.
    mockkConstructor(SparseArray::class)
    every { anyConstructed<SparseArray<Any>>().put(any(), any()) } just runs
  }

  @AfterTest
  fun releaseAndroid() {
    unmockkAll()
  }

  // media3 draws the picture from the first sequence that carries video, so that one comes first.
  @Test
  fun `a track carrying audio and video lowers to a video sequence and then an audio sequence`() {
    val sequences = composition(laid(listOf(1_010.milliseconds)), frameRate = 25).lower()

    sequences.map { it.trackTypes } shouldBe listOf(setOf(C.TRACK_TYPE_VIDEO), setOf(C.TRACK_TYPE_AUDIO))
    sequences[0].editedMediaItems.single().removeAudio shouldBe true
    sequences[1].editedMediaItems.single().removeVideo shouldBe true
  }

  // Three clips of 1.010s at 25 fps cover 25, 26 and 25 slots. The middle one is the one a lowering
  // that rounded each length on its own gets wrong, and the one that runs past what it decodes.
  @Test
  fun `each video item runs for the slots its span covers`() {
    val clips = laid(List(3) { 1_010.milliseconds })

    val video = composition(clips, frameRate = 25).lower().first()

    video.editedMediaItems.map { it.presentationDurationUs } shouldBe clips.map { slotLengthUs(it, 25) }
  }

  // The cut sits between two of the source's samples, and the item opens on the one nearer its first slot's source
  // time.
  @Test
  fun `each video item opens on the sample its first slot shows`() {
    val clips = laid(List(3) { 1_010.milliseconds })

    val video = composition(clips, frameRate = 25).lower().first()

    video.editedMediaItems.map { it.mediaItem.clippingConfiguration.startPositionUs } shouldBe
      clips.map { shownAt(it, outputSlotsOf(it.span, 25).first, 25).inWholeMicroseconds }
  }

  // A trim from a third of a frame to most of one holds no sample, so its slot shows the one nearest its start, from
  // outside the trim on either side.
  @Test
  fun `an item on a window holding no sample opens on the nearest one and closes on the sample after it`() {
    val frame = 1.seconds / 30
    listOf(frame * 0.3 to 0L, frame * 1.8 to 2L).forEach { (start, nearest) ->
      listOf(1.0, CLIP_SPEED_RANGE.start).forEach { speed ->
        val clip = clip("clip0.mp4", sourceLengthOf(20.milliseconds, speed), Duration.ZERO, start, speed = speed)

        val item =
          composition(listOf(clip), frameRate = 30)
            .lower()
            .first()
            .editedMediaItems
            .single()

        item.window() shouldBe (sample(nearest).inWholeMicroseconds to sample(nearest + 1).inWholeMicroseconds)
        item.presentationDurationUs shouldBe slotLengthUs(clip, 30)
      }
    }
  }

  // The source's last sample has none after it, so the item closes where the video track ends.
  @Test
  fun `an item showing the source's last sample closes where its track ends`() {
    val clip = clip(source = "clip0.mp4", length = 2_020.milliseconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 30)
        .lower(clip to sourceEndingAt(2.seconds))
        .first()
        .editedMediaItems
        .single()

    item.window() shouldBe (0L to 2.seconds.inWholeMicroseconds)
  }

  // A preview's player plays the silence of a video item through the item's speed, and media3's pitch keeping at a
  // speed near zero sizes its buffer past what a device holds. A clip at speed one plays every stretch at about one,
  // the source's last frame included.
  @Test
  fun `an item at speed one plays its last frame at speed one`() {
    val clip = clip(source = "clip0.mp4", length = 2.seconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 30)
        .lower(clip to sourceEndingAt(2.seconds))
        .first()
        .editedMediaItems
        .single()

    val pace = item.speedProvider
    val starts = generateSequence(0L) { pace.getNextSpeedChangeTimeUs(it).takeIf { next -> next != C.TIME_UNSET } }
    starts.toList().forEach { start ->
      withClue(
        "the stretch at $start us",
      ) { pace.getSpeed(start).toDouble() shouldBe (1.0 plusOrMinus SPEED_ONE_SLACK) }
    }
    starts.last() shouldBe sampleBefore(2.seconds).inWholeMicroseconds
  }

  @Test
  fun `an item whose slots reach past its source holds its last frame until they are filled`() {
    val clip = clip(source = "clip0.mp4", length = 2_020.milliseconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 30)
        .lower(clip to sourceEndingAt(2.seconds))
        .first()
        .editedMediaItems
        .single()

    val slots = outputSlotsOf(clip.span, 30)
    val length = (outputSlotTime(slots.last + 1, 30) - outputSlotTime(slots.first, 30)).inWholeMicroseconds
    val decoded = item.clippedUs()
    item.presentationDurationUs shouldBe length
    SpeedProviderUtil.getDurationAfterSpeedProviderApplied(item.speedProvider, decoded) shouldBe length
    SpeedProviderMapper(item.speedProvider).getAdjustedTimeUs(decoded) shouldBe length
  }

  // One frame of the source held for four seconds, which is a speed near 0.008. Both of media3's readings of the held
  // length land on it exactly.
  @Test
  fun `a frame held for seconds runs for exactly the length asked`() {
    val from = sample(SOURCE_RATE - 1).inWholeMicroseconds
    val decoded = sample(SOURCE_RATE).inWholeMicroseconds

    listOf(1.seconds, 2_500.milliseconds, 4.seconds).forEach { held ->
      val length = from + held.inWholeMicroseconds
      val hold = holdingLast(from, decoded, length)

      SpeedProviderUtil.getDurationAfterSpeedProviderApplied(hold, decoded) shouldBe length
      SpeedProviderMapper(hold).getAdjustedTimeUs(decoded) shouldBe length
    }
  }

  // Past what a float speed can land to the microsecond, a hold runs a little long and never short, by no more than
  // media3's own step between two speeds. At least one of these misses, which is what says the limit is real.
  @Test
  fun `a frame held past what a float speed can land runs at most one speed step long`() {
    val from = sample(SOURCE_RATE - 1).inWholeMicroseconds
    val decoded = sample(SOURCE_RATE).inWholeMicroseconds

    val misses =
      listOf(30.seconds, 3.minutes, 1.hours).map { held ->
        val length = from + held.inWholeMicroseconds
        val hold = holdingLast(from, decoded, length)
        val readings =
          listOf(
            SpeedProviderUtil.getDurationAfterSpeedProviderApplied(hold, decoded),
            SpeedProviderMapper(hold).getAdjustedTimeUs(decoded),
          )

        readings.forEach { (it - length) shouldBeInRange 0L..speedStepUs(length - from) }
        readings.maxOf { it - length }
      }
    misses.any { it > 0 } shouldBe true
  }

  // Every stretch has to decode something and have time left to run in.
  @Test
  fun `a pace refuses a stretch that decodes nothing or has no time to run`() {
    val from = sample(SOURCE_RATE - 1).inWholeMicroseconds
    val decoded = sample(SOURCE_RATE).inWholeMicroseconds

    assertFailsWith<IllegalArgumentException> { holdingLast(fromUs = from, decodedUs = from, lengthUs = decoded) }
    assertFailsWith<IllegalArgumentException> { holdingLast(fromUs = from, decodedUs = decoded, lengthUs = from) }
  }

  // Each sample of a source at the output's own rate sits on its slot, so a frame in the middle of a held item and the
  // last frame itself are written when they always were.
  @Test
  fun `a held item keeps every frame on its own time`() {
    val clip = clip(source = "clip0.mp4", length = 2_020.milliseconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 30)
        .lower(clip to sourceEndingAt(2.seconds))
        .first()
        .editedMediaItems
        .single()

    val mapper = SpeedProviderMapper(item.speedProvider)
    mapper.getAdjustedTimeUs(1.seconds.inWholeMicroseconds) shouldBe 1.seconds.inWholeMicroseconds
    mapper.getAdjustedTimeUs(sampleBefore(2.seconds).inWholeMicroseconds) shouldBe
      sampleBefore(2.seconds).inWholeMicroseconds
  }

  // Cut 16.667ms in, a second of a 30 fps source opens within a microsecond of half way between two samples, which is
  // a tie, so the item opens on the earlier one. Its last slot ties too, so it shows the earlier one and closes on the
  // sample after it.
  @Test
  fun `an item whose cut sits inside a frame shows the nearer sample on every slot`() {
    val start = 16_667.microseconds
    val clip = clip(source = "clip0.mp4", length = 1.seconds, at = Duration.ZERO, start = start)

    val item =
      composition(listOf(clip), frameRate = 30)
        .lower()
        .first()
        .editedMediaItems
        .single()

    val slots = outputSlotsOf(clip.span, 30)
    item.window() shouldBe
      (
        shownAt(clip, slots.first, 30).inWholeMicroseconds to
          sampleAtOrAfter(shownAt(clip, slots.last, 30) + 1.microseconds).inWholeMicroseconds
      )
    item.presentationDurationUs shouldBe slotLengthUs(clip, 30)
  }

  // 1.010s from a sample at 25 fps covers 25 slots, which is less than it decodes, so it closes on the sample after the
  // last one its slots show.
  @Test
  fun `an item that decodes past its slots closes on the sample after the last one it shows`() {
    val clip = clip(source = "clip0.mp4", length = 1_010.milliseconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 25)
        .lower()
        .first()
        .editedMediaItems
        .single()

    val last = shownAt(clip, outputSlotsOf(clip.span, 25).last, 25)
    item.window() shouldBe (0L to sampleAtOrAfter(last + 1.microseconds).inWholeMicroseconds)
    item.presentationDurationUs shouldBe slotLengthUs(clip, 25)
  }

  // A reader asked for a time while the last frame is held seeks to that frame, since the item has
  // no sample past it.
  @Test
  fun `a preview reads a held stretch at the frame it holds`() {
    val clip = clip(source = "clip0.mp4", length = 2_020.milliseconds, at = Duration.ZERO, start = Duration.ZERO)
    val composition = composition(listOf(clip), frameRate = 30)

    val span = composition.toMedia3Preview(boundsOf(composition, clip to sourceEndingAt(2.seconds))).spans.single()

    span.positionIn(2_010.milliseconds) shouldBe sampleBefore(2.seconds)
    span.positionIn(1.seconds) shouldBe 1.seconds
  }

  // media3's float limit reads against the output length, and every reading lands inside one float step of it.
  @Test
  fun `an item at speed runs for the slots of its output length`() {
    SPEEDS.forEach { speed ->
      val clip =
        clip(source = "clip0.mp4", length = 3.seconds, at = Duration.ZERO, start = Duration.ZERO, speed = speed)

      val item =
        composition(listOf(clip), frameRate = 30)
          .lower()
          .first()
          .editedMediaItems
          .single()

      val length = slotLengthUs(clip, 30)
      val decoded = item.clippedUs()
      clip.outputDuration shouldBe outputLengthOf(3.seconds, speed)
      (item.presentationDurationUs - length) shouldBeInRange 0L..speedStepUs(length)
      (SpeedProviderMapper(item.speedProvider).getAdjustedTimeUs(decoded) - length) shouldBeInRange
        0L..speedStepUs(length)
      item.speedParameters.shouldMaintainPitch shouldBe true
    }
  }

  @Test
  fun `an item at speed one whose samples were never read carries no speed at all`() {
    val clip = clip(source = "clip0.mp4", length = 3.seconds, at = Duration.ZERO, start = Duration.ZERO)

    val item =
      composition(listOf(clip), frameRate = 30)
        .toMedia3(Media3ClipReads.None)
        .sequences
        .first()
        .editedMediaItems
        .single()

    item.speedProvider shouldBe SpeedProvider.DEFAULT
  }

  // Read the way media3's frame rate cap reads a decoded item: it keeps the first frame, then each one at or past a
  // tick a whole microsecond step of the output rate past the last tick, so what it keeps is every sample a slot shows
  // and each of those on the first slot showing it. Mid range speeds and the fast end, from a trim inside a frame.
  @Test
  fun `media3's frame rate cap keeps every sample a slot shows on that slot and drops the rest`() {
    listOf(0.7, 1.0, 1.5, 3.0, CLIP_SPEED_RANGE.endInclusive).forEach { speed ->
      val start = 1.seconds / 30 / 4
      val clip = clip(source = "clip0.mp4", length = 3.seconds, at = 1.seconds, start = start, speed = speed)

      val item =
        composition(listOf(clip), frameRate = 30)
          .lower()
          .first()
          .editedMediaItems
          .last()

      val opens = item.mediaItem.clippingConfiguration.startPositionUs
      val closes = item.mediaItem.clippingConfiguration.endPositionUs
      val mapper = SpeedProviderMapper(item.speedProvider)
      val decoded =
        (0 until SOURCE_LENGTH.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND)
          .map { sample(it).inWholeMicroseconds }
          .filter { it in opens..<closes }
          .map { mapper.getAdjustedTimeUs(it - opens) }
      val step = MICROS_PER_SECOND / 30
      var tick = -1L
      val kept =
        decoded.filter { written ->
          val keeps = tick < 0 || written >= tick
          if (keeps) tick = if (tick < 0) written + step else tick + step
          keeps
        }

      val slots = outputSlotsOf(clip.span, 30)
      val opensAt = outputSlotTime(slots.first, 30)
      val expected =
        slots
          .groupBy { shownAt(clip, it, 30) }
          .map { (_, showing) -> (outputSlotTime(showing.first(), 30) - opensAt).inWholeMicroseconds }
      withClue("speed $speed") { kept shouldBe expected }
    }
  }

  @Test
  fun `a preview reads a frame at speed through the shared offset`() {
    listOf(0.7, 1.5).forEach { speed ->
      val clip = clip(source = "clip0.mp4", length = 3.seconds, at = 1.seconds, start = Duration.ZERO, speed = speed)
      val composition = composition(listOf(clip), frameRate = 30, trackStart = 1.seconds)

      val span = composition.toMedia3Preview(boundsOf(composition)).spans.last()

      span.speed shouldBe speed
      val slot = outputSlotOf(1.seconds + clip.outputDuration / 2, 30)
      val at = outputSlotTime(slot, 30)
      val opens = span.item.clippingConfiguration.startPositionUs.microseconds
      (span.positionIn(at) - sourceOffsetAt(at - span.start, speed)).absoluteValue shouldBeLessThanOrEqualTo
        1.seconds / 30 / 2
      span.positionIn(at) shouldBe shownAt(clip, slot, 30) - opens
    }
  }

  @Test
  fun `an audio item at speed carries its window and runs its own stretch`() {
    listOf(0.7, 1.5).forEach { speed ->
      val clip = clip(source = "clip0.mp4", length = 3.seconds, at = Duration.ZERO, start = 1.seconds, speed = speed)

      val item =
        composition(listOf(clip), frameRate = 30)
          .lower()
          .last()
          .editedMediaItems
          .single()

      item.window() shouldBe (1.seconds.inWholeMicroseconds to 4.seconds.inWholeMicroseconds)
      item.presentationDurationUs shouldBe clip.outputDuration.inWholeMicroseconds
      item.speedProvider shouldBe SpeedProvider.DEFAULT
      item.effects.audioProcessors.count { it is SonicAudioProcessor } shouldBe 1
    }
  }

  @Test
  fun `each audio item carries its clip's exact window in microseconds`() {
    val clips = laid(List(3) { 1_010.milliseconds })

    val audio = composition(clips, frameRate = 25).lower().last()

    audio.editedMediaItems.map { it.window() } shouldBe
      clips.map { it.start.inWholeMicroseconds to it.end.inWholeMicroseconds }
  }

  // A 10ms span at 25 fps reaches no point half way between two slots, so it covers none. Its sound
  // still plays for the whole 10ms.
  @Test
  fun `a clip covering no slot lays an audio item and no video item`() {
    val clips = laid(listOf(1.seconds, 10.milliseconds, 1.seconds))
    check(outputSlotsOf(clips[1].span, 25).isEmpty())

    val (video, audio) = composition(clips, frameRate = 25).lower()

    video.editedMediaItems.map { it.presentationDurationUs } shouldBe
      listOf(clips[0], clips[2]).map { slotLengthUs(it, 25) }
    audio.editedMediaItems.map { it.window() } shouldBe
      clips.map { it.start.inWholeMicroseconds to it.end.inWholeMicroseconds }
  }

  // A source with no picture of its own still covers the slots its span reaches, so the sequence
  // carries a gap for them rather than dropping the clip out of the video track entirely.
  @Test
  fun `a clip with no picture of its own holds its slots with a gap in the video sequence`() {
    val video = clip(source = "clip0.mp4", length = 1.seconds, at = Duration.ZERO, start = Duration.ZERO)
    val silent = audioOnlyClip(source = "audio0.m4a", length = 1_010.milliseconds, at = 1.seconds)

    val sequence = composition(listOf(video, silent), frameRate = 25).lower().first()

    sequence.editedMediaItems.size shouldBe 2
    sequence.editedMediaItems[0].presentationDurationUs shouldBe slotLengthUs(video, 25)
    sequence.editedMediaItems[1].stillGapUs() shouldBe slotLengthUs(silent, 25)
  }

  // 20ms at 25 fps is half a slot, which rounds up onto slot one. The audio waits exactly as long as
  // the track asked.
  @Test
  fun `a track starting half a slot in leads its video with a whole slot and its audio exactly`() {
    val start = 20.milliseconds
    val (video, audio) = composition(laid(listOf(1.seconds), from = start), frameRate = 25, trackStart = start).lower()

    video.editedMediaItems.first().stillGapUs() shouldBe outputSlotTime(outputSlotOf(start, 25), 25).inWholeMicroseconds
    audio.editedMediaItems.first().gapUs() shouldBe start.inWholeMicroseconds
  }

  @Test
  fun `a track starting under half a slot in leads its video with no gap at all`() {
    val start = 19.milliseconds
    val (video, audio) = composition(laid(listOf(1.seconds), from = start), frameRate = 25, trackStart = start).lower()

    check(outputSlotOf(start, 25) == 0L)
    video.editedMediaItems
      .first()
      .isGap shouldBe false
    audio.editedMediaItems.first().gapUs() shouldBe start.inWholeMicroseconds
  }

  // Well inside the timeline rather than at its first slot, where a lowering that kept the exact
  // start and one that snapped it can still agree.
  @Test
  fun `a track starting between two slots leads its video with the slot it lands on`() {
    val start = 530.milliseconds
    val (video, audio) = composition(laid(listOf(1.seconds), from = start), frameRate = 25, trackStart = start).lower()

    video.editedMediaItems.first().stillGapUs() shouldBe outputSlotTime(outputSlotOf(start, 25), 25).inWholeMicroseconds
    audio.editedMediaItems.first().gapUs() shouldBe start.inWholeMicroseconds
  }

  // A still with nothing drawn on it, where media3's own gap is an opaque black picture, stretched over the output
  // frame the way every clip is placed in it.
  @Test
  fun `a gap in the video grid is a transparent still held at the output rate`() {
    val start = 530.milliseconds
    val gap =
      composition(laid(listOf(1.seconds), from = start), frameRate = 25, trackStart = start)
        .lower()
        .first()
        .editedMediaItems
        .first()
    val lead = outputSlotTime(outputSlotOf(start, 25), 25)

    gap.isGap shouldBe true
    gap.mediaItem.localConfiguration?.mimeType shouldBe "image/png"
    gap.mediaItem.localConfiguration?.imageDurationMs shouldBe lead.inWholeMilliseconds
    gap.durationUs shouldBe lead.inWholeMicroseconds
    gap.frameRate shouldBe 25
    gap.removeAudio shouldBe true
    gap.effects.videoEffects shouldBe
      listOf(PlacedFrame(NormalizedRect.Full, FRAME))
  }

  private fun ResolvedComposition.lower(vararg read: Pair<ResolvedClip, FrameBounds>): List<EditedMediaItemSequence> =
    toMedia3(boundsOf(this, *read)).sequences

  /**
   * What the driver would read for every clip of [composition] off a 30 fps source, with [read] in place of that for
   * the clips it names.
   */
  private fun boundsOf(
    composition: ResolvedComposition,
    vararg read: Pair<ResolvedClip, FrameBounds>,
  ): Media3ClipReads {
    val named = read.toMap()
    return Media3ClipReads(
      composition.tracks.flatMap { it.clips }.associate { clip ->
        ClipWindow(clip.source, clip.start, clip.end) to
          (named[clip] ?: readAround(clip.start, clip.end))
      },
    )
  }

  // What the walk reads off a source of SOURCE_LENGTH, from the sample before [start] to two past [end].
  private fun samplesAround(
    start: Duration,
    end: Duration,
  ): List<Duration> {
    val last = SOURCE_LENGTH.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND - 1
    val from = (start.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND - 1).coerceAtLeast(0)
    val to = (end.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND + 2).coerceAtMost(last)
    return (from..to).map(::sample)
  }

  // What the walk reads around [start] and [end], stopping on the sample after the last or at the end of SOURCE_LENGTH.
  private fun readAround(
    start: Duration,
    end: Duration,
  ): FrameBounds {
    val samples = samplesAround(start, end)
    return FrameBounds(samples, sampleAtOrAfter(samples.last() + 1.microseconds).coerceAtMost(SOURCE_LENGTH))
  }

  // A source whose container ends at [end], which is every sample before it.
  private fun sourceEndingAt(end: Duration): FrameBounds =
    FrameBounds((0L..end.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND).map(::sample).filter { it < end }, end)

  // The sample the shared rule picks for [slot] of [clip].
  private fun shownAt(
    clip: ResolvedClip,
    slot: Long,
    frameRate: Int,
  ): Duration {
    val samples = samplesAround(clip.start, clip.end)
    val time = sourceTimeOfSlot(clip.start, clip.span.start, clip.speed, slot, frameRate)
    return samples[checkNotNull(nearestSourceFrame(samples, time))]
  }

  // A pace that plays up to [fromUs] at speed one and holds the sample there until [lengthUs].
  private fun holdingLast(
    fromUs: Long,
    decodedUs: Long,
    lengthUs: Long,
  ): SlotPace =
    SlotPace(
      ShownFrames(
        opens = Duration.ZERO,
        closes = decodedUs.microseconds,
        inputsUs = listOf(0L, fromUs),
        outputsUs = listOf(0L, fromUs),
        lengthUs = lengthUs,
        shown = listOf(0L to 0L, fromUs to fromUs),
      ),
      clipSpeed = 1.0,
    )

  // The source's samples sit on its own 30 fps ladder, each at k * 1/30 s in whole microseconds.
  private fun sample(index: Long): Duration = (index * MICROS_PER_SECOND / SOURCE_RATE).microseconds

  private fun sampleAtOrAfter(time: Duration): Duration {
    var index = time.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND
    while (sample(index) < time) index++
    return sample(index)
  }

  private fun sampleBefore(time: Duration): Duration {
    var index = time.inWholeMicroseconds * SOURCE_RATE / MICROS_PER_SECOND
    while (sample(index) >= time) index--
    return sample(index)
  }

  private fun EditedMediaItem.clippedUs(): Long =
    mediaItem.clippingConfiguration.endPositionUs - mediaItem.clippingConfiguration.startPositionUs

  private fun EditedMediaItem.window(): Pair<Long, Long> =
    mediaItem.clippingConfiguration.startPositionUs to mediaItem.clippingConfiguration.endPositionUs

  private fun EditedMediaItem.gapUs(): Long {
    check(mediaItem.localConfiguration == null) { "expected a gap and found $mediaItem" }
    return durationUs
  }

  private fun EditedMediaItem.stillGapUs(): Long {
    check(isGap && mediaItem.localConfiguration != null) { "expected a transparent gap and found $mediaItem" }
    return durationUs
  }

  private fun slotLengthUs(
    clip: ResolvedClip,
    frameRate: Int,
  ): Long {
    val slots = outputSlotsOf(clip.span, frameRate)
    return (outputSlotTime(slots.last + 1, frameRate) - outputSlotTime(slots.first, frameRate)).inWholeMicroseconds
  }

  private fun composition(
    clips: List<ResolvedClip>,
    frameRate: Int,
    trackStart: Duration = Duration.ZERO,
    content: TrackContent = TrackContent.AudioAndVideo,
    path: ExportPath = ExportPath.Transcode,
  ): ResolvedComposition =
    ResolvedComposition(
      tracks = listOf(ResolvedTrack(content = content, looping = false, start = trackStart, clips = clips)),
      compositionEffects = emptyList(),
      output =
        OutputFormat(
          size = FRAME,
          videoCodec = VideoCodec.H264,
          audioCodec = AudioCodec.Aac,
          bitrate = null,
          frameRate = frameRate,
          audioFormat = null,
        ),
      layoutSize = FRAME,
      fill = Fill.Black,
      duration = trackStart + clips.fold(Duration.ZERO) { total, clip -> total + clip.sourceDuration },
      hdr = ResolvedHdr.Keep,
      hdrTransfer = null,
      audio = AudioSpec.Keep,
      adjustments = emptyList(),
      path = path,
    )

  /**
   * Clips of these lengths laid end to end from [from], each cut from [TRIM_START] of its own source.
   */
  private fun laid(
    lengths: List<Duration>,
    from: Duration = Duration.ZERO,
  ): List<ResolvedClip> {
    var at = from
    return lengths.mapIndexed { index, length ->
      clip(source = "clip$index.mp4", length = length, at = at).also { at += length }
    }
  }

  private fun clip(
    source: String,
    length: Duration,
    at: Duration,
    start: Duration = TRIM_START,
    speed: Double = 1.0,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(source),
      info =
        MediaInfo(
          duration = SOURCE_LENGTH,
          video =
            VideoTrackInfo(
              codedSize = FRAME,
              displaySize = FRAME,
              rotationDegrees = 0,
              pixelAspectRatio = 1f,
              frameRate = 30f,
              codec = trackCodecOf("avc1"),
              bitDepth = 8,
              colorSpace = ColorSpace.Bt709,
              hdrTransfer = null,
              bitrate = null,
            ),
          audioTracks =
            listOf(
              AudioTrackInfo(
                id = AudioTrackId(1),
                codec = trackCodecOf("mp4a"),
                sampleRate = 48_000,
                channelCount = 2,
                bitrate = null,
                language = null,
                isDefault = true,
              ),
            ),
          isExportable = true,
        ),
      start = start,
      end = start + length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, outputLengthOf(length, speed)),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + outputLengthOf(length, speed)),
      sourceIndex = 0,
      speed = speed,
    )

  /**
   * A clip whose source carries no picture of its own, the way a music file reads.
   */
  private fun audioOnlyClip(
    source: String,
    length: Duration,
    at: Duration,
  ): ResolvedClip =
    ResolvedClip(
      source = MediaSource.of(source),
      info =
        MediaInfo(
          duration = length,
          video = null,
          audioTracks =
            listOf(
              AudioTrackInfo(
                id = AudioTrackId(1),
                codec = trackCodecOf("mp4a"),
                sampleRate = 48_000,
                channelCount = 2,
                bitrate = null,
                language = null,
                isDefault = true,
              ),
            ),
          isExportable = true,
        ),
      start = Duration.ZERO,
      end = length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(at, at + length),
      sourceIndex = 0,
    )

  private companion object {
    val SPEEDS = listOf(CLIP_SPEED_RANGE.start, 0.7, 1.5, CLIP_SPEED_RANGE.endInclusive)
    val FRAME = Size(320, 180)
    val SOURCE_LENGTH = 4.seconds

    // Off a whole millisecond, so a window clipped in milliseconds reads differently from one in microseconds.
    val TRIM_START = 250.milliseconds + 250.microseconds

    const val SOURCE_RATE = 30L
    const val MICROS_PER_SECOND = 1_000_000L

    // A sample interval and a slot of the same rate differ by at most a microsecond in about 33 333.
    const val SPEED_ONE_SLACK = 1e-4
  }
}
