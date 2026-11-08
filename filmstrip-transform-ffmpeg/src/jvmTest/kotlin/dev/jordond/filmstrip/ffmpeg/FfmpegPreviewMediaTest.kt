package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.codec.Bitrate
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotTime
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.color.Brightness
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.OverlayOffset
import dev.jordond.filmstrip.effects.overlay.animatedBy
import dev.jordond.filmstrip.effects.overlay.fade
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegRuntime
import dev.jordond.filmstrip.ffmpeg.internal.lowering.writtenPixelFormat
import dev.jordond.filmstrip.ffmpeg.internal.preview.FfmpegPreviewStream
import dev.jordond.filmstrip.ffmpeg.internal.preview.PreviewStreamResult
import dev.jordond.filmstrip.ffmpeg.internal.preview.openPreview
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.BAR_FILL
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_COLOR
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.OVERLAY_SIZE
import dev.jordond.filmstrip.ffmpeg.support.OverlayConstants.PROBE_SIDE
import dev.jordond.filmstrip.ffmpeg.support.assertNear
import dev.jordond.filmstrip.ffmpeg.support.channelsOf
import dev.jordond.filmstrip.ffmpeg.support.containedRect
import dev.jordond.filmstrip.ffmpeg.support.pngOf
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TestFrame
import dev.jordond.filmstrip.test.compareFrames
import dev.jordond.filmstrip.transform.internal.ResolveResult
import dev.jordond.filmstrip.transform.internal.heldTail
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.videoSlots
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeFalse
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.doubles.shouldBeGreaterThanOrEqual
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import java.awt.Color
import java.io.File
import kotlin.test.Test
import kotlin.test.fail
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * The pump, against a real ffmpeg and real clips.
 *
 * The first test is the parity claim on this backend at the level a caller reaches it: one edit,
 * two entry points, one filter graph on both command lines. [PreviewInvocationTest] pins the same
 * claim one layer down, where a second graph builder written "just for preview" would show up.
 *
 * Nothing here skips. The fixtures are a task dependency of `jvmTest` and the jvm lanes carry
 * ffmpeg, so a missing one is a broken build rather than a reason to report green.
 */
class FfmpegPreviewMediaTest {
  private val fixtures = File(System.getProperty("filmstrip.fixtures").orEmpty())
  private val landscape = File(fixtures, "export_landscape.mp4")
  private val portrait = File(fixtures, "export_portrait.mp4")

  private val commands = mutableListOf<DiagnosticEvent>()

  private val components =
    ComponentRegistry
      .Builder()
      .addEffectResolver(BuiltInEffectResolver())
      .addDiagnosticListener(DiagnosticListener { event -> synchronized(commands) { commands += event } })
      .build()

  private val engine = FfmpegExportEngine(components, FfmpegRuntime.of(FfmpegConfig()))
  private val filterFrames = FilterFrames()

  @Test
  fun `the preview and the export run the same filter graph`() =
    runTest(timeout = TIMEOUT) {
      val composition = graded()
      val output = File.createTempFile("filmstrip-preview-parity", ".mp4").also { it.delete() }

      val verdict = engine.plan(composition, ExportSpec())
      val plan = verdict.shouldBeInstanceOf<Verdict.Capable>().plan
      val finished = engine.export(plan, MediaSink.of(output.absolutePath)).toList().last()
      if (finished is ExportStatus.Failure) fail("the export refused the fixture: ${finished.error.message}")

      val opened = engine.openPreview(composition, ExportSpec()).shouldBeInstanceOf<PreviewStreamResult.Opened>()
      opened.stream.close()
      output.delete()

      val written = writtenPixelFormat(null)
      graphOf(DiagnosticEvent.PREVIEW) shouldBe
        "${graphOf(DiagnosticEvent.INVOCATION)};[vout]format=pix_fmts=$written[vshown]"
    }

  // Reading forward from the head and seeking straight there have to reach the same frame, or the
  // seek is landing on a different part of the clip than playback would.
  @Test
  fun `an input seek lands on the frame reading forward reaches`() =
    runTest(timeout = TIMEOUT) {
      val composition = graded()

      val read = open(composition, Duration.ZERO)
      val forward =
        try {
          repeat(PROBE_FRAME) {
            withClue("the pump ran out before frame $PROBE_FRAME") {
              read.next().shouldNotBeNull()
            }
          }
          withClue("the pump ran out before frame $PROBE_FRAME") { read.next().shouldNotBeNull() }
        } finally {
          read.close()
        }

      val seeked = open(composition, PROBE_POSITION)
      val landed =
        try {
          withClue("the seeked pump delivered no frame") { seeked.next().shouldNotBeNull() }
        } finally {
          seeked.close()
        }

      seeked.startPosition shouldBe PROBE_POSITION
      withClue("the seek landed on a different frame than reading forward did") {
        forward.contentEquals(landed).shouldBeTrue()
      }
    }

  // An overlay image cannot be seeked into, so its branch is carried to the scrub instead. Without
  // that the animation restarts wherever the seek lands and the overlay is drawn at its opening
  // value. The probe is a fifth of the way into the fade, because both ends of a ramp read the same
  // under a restarted animation as under a carried one.
  @Test
  fun `a seek into an overlay animation draws what the export draws`() =
    runTest(timeout = TIMEOUT) {
      val faded = overlaid(watermark(animation = fade(inDuration = FIXTURE_LENGTH)))

      val read = open(faded, Duration.ZERO)
      val forward =
        try {
          repeat(PROBE_FRAME) {
            withClue("the pump ran out before frame $PROBE_FRAME") {
              read.next().shouldNotBeNull()
            }
          }
          withClue("the pump ran out before frame $PROBE_FRAME") { read.next().shouldNotBeNull() }
        } finally {
          read.close()
        }

      val landed = firstFrame(faded, PROBE_POSITION)
      withClue("the scrubbed animation drew a different frame than playback did") {
        forward.contentEquals(landed).shouldBeTrue()
      }

      // Both ends of the same fade, scrubbed to the same frame of the same clip, so the overlay is
      // all that differs between them. A fifth of the way up the ramp is neither.
      val hidden = firstFrame(overlaid(watermark(opacity = 0f)), PROBE_POSITION)
      val whole = firstFrame(overlaid(watermark(opacity = 1f)), PROBE_POSITION)
      withClue("the overlay changed nothing at the probe") { hidden.contentEquals(whole).shouldBeFalse() }
      withClue("the scrubbed animation drew no overlay at all") { landed.contentEquals(hidden).shouldBeFalse() }
      withClue("the scrubbed animation drew the overlay whole") { landed.contentEquals(whole).shouldBeFalse() }
    }

  // Past the ramp the fade holds the authored opacity, which a resting mixer applies on the frames the geq is switched
  // off on. Both switch on the frame's own time, so a scrub landing past the ramp has to turn the mixer on there. The
  // settled opacity is below full strength, so a scrub that left the mixer off would draw the overlay whole.
  @Test
  fun `a seek past the end of a ramp draws the value it settled on`() =
    runTest(timeout = TIMEOUT) {
      val settled = overlaid(watermark(opacity = SETTLED_OPACITY, animation = fade(inDuration = SHORT_FADE)))

      val read = open(settled, Duration.ZERO)
      val forward =
        try {
          repeat(SETTLED_FRAME) {
            withClue("the pump ran out before frame $SETTLED_FRAME") {
              read.next().shouldNotBeNull()
            }
          }
          withClue("the pump ran out before frame $SETTLED_FRAME") { read.next().shouldNotBeNull() }
        } finally {
          read.close()
        }

      val landed = firstFrame(settled, SETTLED_POSITION)
      val whole = firstFrame(overlaid(watermark()), SETTLED_POSITION)

      withClue("the settled overlay was drawn differently than playback drew it") {
        forward.contentEquals(landed).shouldBeTrue()
      }
      withClue("the settled overlay was drawn whole, with its resting mixer off") {
        landed.contentEquals(whole).shouldBeFalse()
      }
    }

  // A scrub inside a frame period opens on the output slot it lands on, the clip and the overlay's
  // branch both, so the frame it hands over is the one reading forward reaches on that slot. Every
  // other probe here sits on the grid, where every reading of a scrub agrees anyway.
  @Test
  fun `a seek inside a frame period carries the branch onto the frame it lands on`() =
    runTest(timeout = TIMEOUT) {
      val faded = overlaid(watermark(animation = fade(inDuration = OFF_GRID_FADE)))
      val slot = outputSlotOf(OFF_GRID_POSITION, FIXTURE_RATE)

      val read = open(faded, Duration.ZERO)
      val forward =
        try {
          repeat(slot.toInt()) { withClue("the pump ran out before slot $slot") { read.next().shouldNotBeNull() } }
          withClue("the pump ran out before slot $slot") { read.next().shouldNotBeNull() }
        } finally {
          read.close()
        }

      val seeked = open(faded, OFF_GRID_POSITION)
      val landed =
        try {
          withClue("the seeked pump delivered no frame") { seeked.next().shouldNotBeNull() }
        } finally {
          seeked.close()
        }

      seeked.startPosition shouldBe outputSlotTime(slot, FIXTURE_RATE)
      withClue("the branch was read a frame off the one the clip delivered") {
        forward.contentEquals(landed).shouldBeTrue()
      }
    }

  // The window rides the overlay's own branch, which the scrub carries to the seek, so a frame
  // reached by seeking is drawn the way the same frame is when it is read forward. All three probes
  // land inside a frame period, where the pump opens on the slot each one lands on.
  @Test
  fun `a scrubbed preview opens an overlay's window on time`() =
    runTest(timeout = TIMEOUT) {
      val windowed = overlaid(watermark(visibleDuring = WINDOW))

      val early = firstFrame(windowed, BEFORE_WINDOW)
      val open = firstFrame(windowed, INSIDE_WINDOW)
      val shut = firstFrame(windowed, AFTER_WINDOW)

      withClue("the window was open before its start") {
        early.contentEquals(forwardFrame(windowed, BEFORE_WINDOW)).shouldBeTrue()
      }
      withClue("the window was shut at the scrub") {
        open.contentEquals(forwardFrame(windowed, INSIDE_WINDOW)).shouldBeTrue()
      }
      withClue("the window was open past its own end") {
        shut.contentEquals(forwardFrame(windowed, AFTER_WINDOW)).shouldBeTrue()
      }

      // The overlay is all the two references differ by, read at the same scrub, so a probe that
      // matched both would say nothing about the window.
      val always = overlaid(watermark())
      val hidden = overlaid(watermark(opacity = 0f))
      withClue("the overlay was drawn at some other value") {
        open.contentEquals(firstFrame(always, INSIDE_WINDOW)).shouldBeTrue()
      }
      withClue("the overlay changed nothing at the probe") {
        open.contentEquals(firstFrame(hidden, INSIDE_WINDOW)).shouldBeFalse()
      }
      withClue("the overlay was drawn before the window") {
        assertShownAsExported(windowed, BEFORE_WINDOW, early)
        early.contentEquals(firstFrame(always, BEFORE_WINDOW)).shouldBeFalse()
      }
      withClue("the overlay was still drawn past the window") {
        assertShownAsExported(windowed, AFTER_WINDOW, shut)
        shut.contentEquals(firstFrame(always, AFTER_WINDOW)).shouldBeFalse()
      }
    }

  // The clip has no frame past its own end to seek to, so a scrub into the held tail opens on the last one it covers
  // and the graph clones it from there. Every frame to the end is the one reading forward reaches on that last slot,
  // which is also what a thumbnail or a readback takes first, and the stream still says it opened where the scrub
  // landed.
  @Test
  fun `a scrub into the held tail delivers the primary's last frame to the end`() =
    runTest(timeout = TIMEOUT) {
      val edit = underBed()
      val resolved = resolved(edit)
      val rate = resolved.output.frameRate
      val at = insideTail(resolved)
      val held = forwardFrame(edit, outputSlotTime(resolved.heldTail.first - 1, rate))

      val stream = open(edit, at)
      val delivered = mutableListOf<ByteArray>()
      try {
        while (true) delivered += stream.next() ?: break
        stream.startPosition shouldBe outputSlotTime(outputSlotOf(at, rate), rate)
      } finally {
        stream.close()
      }

      delivered.size shouldBe (resolved.videoSlots - outputSlotOf(at, rate)).toInt()
      delivered.forEachIndexed { index, frame ->
        withClue("frame $index of the scrub is not the primary's last") { frame.contentEquals(held).shouldBeTrue() }
      }
    }

  // The overlay's window closes with the primary, so a branch carried to the scrub itself reads a time past it and
  // draws nothing. The branch is carried to the slot the clip opens on instead, so the held frame carries the overlay
  // the way the export's does.
  @Test
  fun `a scrub into the held tail draws the overlay the last frame carried`() =
    runTest(timeout = TIMEOUT) {
      val closing = underBed(watermark(visibleDuring = CLOSES_WITH_PRIMARY))
      val resolved = resolved(closing)
      val rate = resolved.output.frameRate
      val at = insideTail(resolved)

      val landed = firstFrame(closing, at)

      val lastCovered = outputSlotTime(resolved.heldTail.first - 1, rate)
      withClue("the scrub drew another frame than the last") {
        landed.contentEquals(forwardFrame(closing, lastCovered)).shouldBeTrue()
      }
      val hidden = firstFrame(underBed(watermark(opacity = 0f)), at)
      withClue("the held frame carries no overlay") { landed.contentEquals(hidden).shouldBeFalse() }
    }

  // The fade on the composition runs the whole bed, so it is still moving when the primary runs out, and the tail
  // only holds still if it freezes the frame the last covered slot finished on, fade included. Playing from a few
  // slots short of the tail into its middle, every frame is the one reading forward from the head writes on its slot,
  // which runs the export's graph from where the export does, and every tail frame is that held one.
  @Test
  fun `playing from a scrub into the held tail freezes the last covered frame`() =
    runTest(timeout = TIMEOUT) {
      val faded = underBed(watermark(animation = fade(inDuration = FIXTURE_LENGTH)))
      val resolved = resolved(faded)
      val rate = resolved.output.frameRate
      val tail = resolved.heldTail
      val lastCovered = tail.first - 1
      val opening = tail.first - PLAY_IN_SLOTS
      val middle = tail.first + (tail.last - tail.first) / 2

      val forward = frames(faded, Duration.ZERO, (middle + 1).toInt())
      val held = forward[lastCovered.toInt()]
      val scrub = outputSlotTime(lastCovered, rate)
      withClue("the fade had not begun") {
        held.contentEquals(firstFrame(underBed(watermark(opacity = 0f)), scrub)).shouldBeFalse()
      }
      withClue("the fade was over before the tail") {
        held.contentEquals(firstFrame(underBed(watermark()), scrub)).shouldBeFalse()
      }

      val played = frames(faded, outputSlotTime(opening, rate), (middle - opening + 1).toInt())
      val strayed =
        (opening..middle).filterNot { slot -> played[(slot - opening).toInt()].contentEquals(forward[slot.toInt()]) }
      val moved = (tail.first..middle).filterNot { slot -> played[(slot - opening).toInt()].contentEquals(held) }
      withClue("slots played differently than reading from the head") { strayed shouldBe emptyList() }
      withClue("tail slots that are not the frame slot $lastCovered finished on") { moved shouldBe emptyList() }

      // Landing the scrub itself on the last covered slot rather than short of it, the edge the split works out from.
      // The graph still renders that one frame and the pump holds it over the same tail.
      val playedFromEdge = frames(faded, scrub, (middle - lastCovered + 1).toInt())
      val strayedFromEdge =
        (lastCovered..middle).filterNot { slot ->
          playedFromEdge[(slot - lastCovered).toInt()].contentEquals(forward[slot.toInt()])
        }
      withClue("slots played from the edge differently than reading from the head") {
        strayedFromEdge shouldBe emptyList()
      }
    }

  // With the overlay on the primary's own clip, its branch ends on the merge, and a scrub onto the clip's last slot or
  // into the tail opens that branch on its last frame alone. Both scrubs deliver the frame reading forward reaches on
  // that last slot, with the overlay drawn on it, and reading forward does not hold the frame before it there.
  @Test
  fun `a scrub onto a clip overlay's last slot or into the tail delivers the clip's last frame`() =
    runTest(timeout = TIMEOUT) {
      val marked = underBed(watermark(), onClip = true)
      val hidden = underBed(watermark(opacity = 0f), onClip = true)
      val resolved = resolved(marked)
      val rate = resolved.output.frameRate
      val covered = resolved.heldTail.first
      val forward = frames(marked, Duration.ZERO, covered.toInt())
      val last = forward.last()
      withClue("reading forward holds the frame before the last") {
        last.contentEquals(forward[forward.size - 2]).shouldBeFalse()
      }

      listOf(outputSlotTime(covered - 1, rate), insideTail(resolved)).forEach { at ->
        val landed = firstFrame(marked, at)
        withClue("the scrub to $at delivered another frame than the clip's last") {
          landed.contentEquals(last).shouldBeTrue()
        }
        withClue("the scrub to $at drew no overlay") { landed.contentEquals(firstFrame(hidden, at)).shouldBeFalse() }
      }
    }

  // A copy runs no graph to cut it, and its input bound still takes in the source frame on the slot a trim's end rounds
  // down past. The pump hands over one frame for each slot the export writes from where it opened and none past them,
  // opened at the head and at a scrub inside the clip. One trim ends a third of the way into a frame period and one
  // three fifths of the way in, so one end rounds down and the other up.
  @Test
  fun `a trimmed copy pumps only the slots the export writes`() =
    runTest(timeout = TIMEOUT) {
      listOf(ROUNDS_DOWN_TRIM, ROUNDS_UP_TRIM).forEach { end ->
        val edit = copied(end)
        val resolved = resolved(edit)
        val rate = resolved.output.frameRate
        resolved.path shouldBe ExportPath.Copy

        listOf(Duration.ZERO, OFF_GRID_POSITION).forEach { at ->
          val stream = open(edit, at)
          var delivered = 0L
          try {
            while (stream.next() != null) delivered++
          } finally {
            stream.close()
          }
          delivered shouldBe resolved.videoSlots - outputSlotOf(at, rate)
        }
      }
    }

  // A clip overlay is placed on the clip's own frame, so on a portrait clip contained in a landscape frame the half
  // pushed off the clip's edge must not land in the bar beside it, and the clip stays where the fit put it. These are
  // the points the export's own test reads, so the preview and the export are held to the same picture.
  @Test
  fun `a clip overlay pushed off a letterboxed clip is cut at the clip's edge`() =
    runTest(timeout = TIMEOUT) {
      val tracks = resolved(letterboxed()).tracks
      val clip = tracks.first().clips.first()
      val source =
        clip.info.video
          .shouldNotBeNull()
          .displaySize
      val image = ImageSource.ofBytes(OVERLAY_SQUARE)
      val placed = ImageOverlay(image, Corner.TopStart, scale = CLIP_OVERLAY_SCALE).placedOn(source, OVERLAY_SIZE)
      val unmoved = placed.rectOn(source)
      val push = OverlayFrame(offset = OverlayOffset(-(unmoved.left + unmoved.width / 2), 0f))
      val pushed =
        ImageOverlay(image, Corner.TopStart, scale = CLIP_OVERLAY_SCALE, animation = OverlayAnimation { push })
      val rect = placed.animatedBy(push).rectOn(source)
      withClue("the overlay at $rect does not straddle the clip's edge") {
        (rect.left < 0f && rect.right > 0f).shouldBeTrue()
      }

      val (size, bare) = sizedFrame(letterboxed(), PROBE_POSITION)
      val (markedSize, marked) = sizedFrame(letterboxed(pushed), PROBE_POSITION)
      markedSize shouldBe size
      val picture = containedRect(source, size)
      val middle = rect.top + rect.height / 2

      assertNear(
        marked.readAt(size, picture, rect.right / 2, middle),
        channelsOf(OVERLAY_COLOR),
        "the overlay's half on the clip",
      )
      assertNear(marked.readAt(size, picture, rect.left / 2, middle), channelsOf(BAR_FILL), "the bar beside the clip")
      listOf(
        (rect.right + 1f) / 2 to middle,
        rect.right / 2 to (rect.bottom + 1f) / 2,
      ).forEach { (x, y) ->
        assertNear(
          marked.readAt(size, picture, x, y),
          bare.readAt(size, picture, x, y),
          "the clip at $x, $y",
        )
      }
    }

  @Test
  fun `a frame is the composition's own frame, tightly packed`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), Duration.ZERO)
      try {
        stream.size shouldBe FIXTURE_FRAME
        stream.next().shouldNotBeNull().size shouldBe FIXTURE_FRAME.width * FIXTURE_FRAME.height * CHANNELS
      } finally {
        stream.close()
      }
    }

  // A concat has branches the seek would move out from under it, so the only correct answer left is
  // to open at the head and let the caller read forward.
  @Test
  fun `a composition an input seek cannot window opens at the head`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(twoClips(), PROBE_POSITION)
      try {
        stream.startPosition shouldBe Duration.ZERO
      } finally {
        stream.close()
      }
    }

  // A pump that never ends is a coroutine that never returns and a process nobody closes, so the
  // last frame has to be followed by an end rather than by a wait.
  @Test
  fun `a preview run to the end of the composition ends rather than hanging`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), FIXTURE_LENGTH - FIXTURE_FRAME_STEP * TAIL_FRAMES)
      try {
        var frames = 0
        while (stream.next() != null) frames++
        frames shouldBe TAIL_FRAMES
      } finally {
        stream.close()
      }
    }

  // The child is spawned by filmstrip and reaped by filmstrip. A preview that outlives its stream
  // holds a decoder open and keeps writing into a pipe nobody reads.
  @Test
  fun `closing a stream leaves no process behind`() =
    runTest(timeout = TIMEOUT) {
      val stream = open(graded(), Duration.ZERO)
      val pid = withClue("the pump reported no process id") { stream.processId.shouldNotBeNull() }
      withClue("the pump delivered no frame") { stream.next().shouldNotBeNull() }

      stream.close()

      val handle = ProcessHandle.of(pid)
      (handle.isPresent && handle.get().isAlive) shouldBe false
    }

  private suspend fun open(
    composition: EditComposition,
    at: Duration,
  ): FfmpegPreviewStream {
    val result = engine.openPreview(composition, ExportSpec(), at = at)
    return result.shouldBeInstanceOf<PreviewStreamResult.Opened>().stream.shouldBeInstanceOf<FfmpegPreviewStream>()
  }

  private suspend fun firstFrame(
    composition: EditComposition,
    at: Duration,
  ): ByteArray = frames(composition, at, 1).single()

  // An export's overlay runs on every frame, shut or open, so a preview reads against that export rather than against a
  // plan holding no overlay at all.
  private suspend fun assertShownAsExported(
    composition: EditComposition,
    at: Duration,
    shown: ByteArray,
  ) {
    val slot = outputSlotOf(at, FIXTURE_RATE).toInt()
    val written = filterFrames.exportedFrame(composition, slot, ExportSpec(bitrate = HIGH_BITRATE))

    compareFrames(TestFrame(written, FIXTURE_FRAME), TestFrame(shown, FIXTURE_FRAME)).psnrDb shouldBeGreaterThanOrEqual
      ENCODER_LOSS_FLOOR_DB
  }

  // The frame on the slot [at] lands on, read forward from the head, which is the frame a scrub
  // landing on the same slot has to agree with.
  private suspend fun forwardFrame(
    composition: EditComposition,
    at: Duration,
  ): ByteArray = frames(composition, Duration.ZERO, outputSlotOf(at, FIXTURE_RATE).toInt() + 1).last()

  private suspend fun frames(
    composition: EditComposition,
    at: Duration,
    count: Int,
  ): List<ByteArray> {
    val stream = open(composition, at)
    return try {
      buildList {
        repeat(count) { index ->
          add(
            withClue("the pump ran out before frame $index at $at") {
              stream.next().shouldNotBeNull()
            },
          )
        }
      }
    } finally {
      stream.close()
    }
  }

  private fun overlaid(overlay: ImageOverlay): EditComposition {
    withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
      effects = listOf(overlay),
    )
  }

  // A white square in the corner the frame starts at, drawn big enough that what it is drawn at
  // shows on the frame.
  private fun watermark(
    opacity: Float = 1f,
    visibleDuring: TimeRange? = null,
    animation: OverlayAnimation? = null,
  ): ImageOverlay =
    ImageOverlay(
      image = ImageSource.ofBytes(WHITE_SQUARE),
      corner = Corner.TopStart,
      margin = 0f,
      scale = OVERLAY_SCALE,
      opacity = opacity,
      visibleDuring = visibleDuring,
      animation = animation,
    )

  // The fixture's first second as the primary under the whole fixture laid as an audio track, which runs a second past
  // it, with [overlay] on the composition or on the primary's own clip.
  private fun underBed(
    overlay: ImageOverlay? = null,
    onClip: Boolean = false,
  ): EditComposition {
    withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
    val source = MediaSource.of(landscape.absolutePath)
    val primary =
      Clip(source, TimeRange.of(Duration.ZERO, 1.seconds), effects = listOfNotNull(overlay.takeIf { onClip }))
    return EditComposition(
      tracks =
        listOf(
          Track(listOf(primary)),
          Track(listOf(Clip(source)), content = TrackContent.Audio),
        ),
      effects = listOfNotNull(overlay.takeIf { !onClip }),
    )
  }

  private suspend fun resolved(composition: EditComposition): ResolvedComposition =
    engine.resolve(composition, ExportSpec()).shouldBeInstanceOf<ResolveResult.Resolved>().composition

  // The middle of the held tail and a third of a slot on, so the scrub lands on a slot it is not sitting on.
  private fun insideTail(resolved: ResolvedComposition): Duration {
    val tail = resolved.heldTail
    return outputSlotTime(tail.first + (tail.last - tail.first) / 2, resolved.output.frameRate) + OFF_SLOT
  }

  private fun graded(): EditComposition {
    withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath))))),
      effects = listOf(Brightness(BRIGHTNESS)),
    )
  }

  // The fixture's opening up to [end], with nothing else on the edit, which a stream copy covers.
  private fun copied(end: Duration): EditComposition {
    withClue("the fixture ${landscape.absolutePath} was not downloaded") { landscape.isFile.shouldBeTrue() }
    return EditComposition(
      tracks = listOf(Track(listOf(Clip(MediaSource.of(landscape.absolutePath), TimeRange.of(Duration.ZERO, end))))),
    )
  }

  private fun twoClips(): EditComposition {
    withClue("the fixtures were not downloaded") { (landscape.isFile && portrait.isFile).shouldBeTrue() }
    return EditComposition(
      tracks =
        listOf(
          Track(
            listOf(
              Clip(MediaSource.of(landscape.absolutePath), TimeRange.of(Duration.ZERO, 1.seconds)),
              Clip(MediaSource.of(portrait.absolutePath), TimeRange.of(Duration.ZERO, 1.seconds)),
            ),
          ),
        ),
      effects = listOf(Brightness(BRIGHTNESS)),
    )
  }

  private suspend fun sizedFrame(
    composition: EditComposition,
    at: Duration,
  ): Pair<Size, ByteArray> {
    val stream = open(composition, at)
    return try {
      stream.size to withClue("the pump delivered no frame at $at") { stream.next().shouldNotBeNull() }
    } finally {
      stream.close()
    }
  }

  // The portrait fixture contained in a landscape frame over a [BAR_FILL] fill, with [clipOverlay] on its only clip.
  private fun letterboxed(clipOverlay: ImageOverlay? = null): EditComposition {
    withClue("the fixture ${portrait.absolutePath} was not downloaded") { portrait.isFile.shouldBeTrue() }
    return compositionOf {
      clip(MediaSource.of(portrait.absolutePath)) {
        clipOverlay?.let { mark -> effects { add(mark) } }
      }
      effects { crop(AspectRatio.Landscape, fit = Fit.Contain) }
      fill(Fill.Solid(BAR_FILL))
    }
  }

  // The mean color of a probe square centred on a point given as a fraction of [picture], which is itself a fraction
  // of this RGBA frame of [size].
  private fun ByteArray.readAt(
    size: Size,
    picture: NormalizedRect,
    x: Float,
    y: Float,
  ): Triple<Int, Int, Int> {
    val centreX = ((picture.left + x * picture.width) * size.width).toInt()
    val centreY = ((picture.top + y * picture.height) * size.height).toInt()
    val sums = IntArray(RGB)
    for (row in centreY - PROBE_SIDE / 2 until centreY + PROBE_SIDE / 2) {
      for (column in centreX - PROBE_SIDE / 2 until centreX + PROBE_SIDE / 2) {
        val at = (row * size.width + column) * CHANNELS
        repeat(RGB) { channel -> sums[channel] += this[at + channel].toInt() and 0xFF }
      }
    }
    val count = PROBE_SIDE * PROBE_SIDE
    return Triple(sums[0] / count, sums[1] / count, sums[2] / count)
  }

  // Read out of the command line each path actually spawned, rather than out of the lowering both
  // share, so a second graph builder written for the preview would fail this.
  private fun graphOf(name: String): String {
    val command =
      synchronized(commands) { commands.lastOrNull { it.name == name } }
        ?: fail("no $name command was reported")
    return command.detail
      .getValue(DiagnosticEvent.Detail.COMMAND)
      .substringAfter("-filter_complex ")
      .substringBefore(" -map ")
  }

  private companion object {
    val TIMEOUT = 2.minutes
    val HIGH_BITRATE = Bitrate.mbps(200)

    // 200 Mbps at this size reads 59.8 dB on both ffmpeg builds.
    const val ENCODER_LOSS_FLOOR_DB = 59.0

    val FIXTURE_FRAME = Size(640, 360)
    const val CHANNELS = 4
    const val BRIGHTNESS = 1.4f

    // Frame 12 on the fixture's 30fps grid, in the middle of the clip rather than at either end.
    val PROBE_POSITION = 400.milliseconds
    const val PROBE_FRAME = 12

    val FIXTURE_LENGTH = 2.seconds
    const val FIXTURE_RATE = 30
    val FIXTURE_FRAME_STEP = 1.seconds / FIXTURE_RATE
    const val TAIL_FRAMES = 3

    // A quarter of the frame width, so the overlay covers enough of the picture that the opacity it
    // is drawn at cannot round away.
    const val OVERLAY_SCALE = 0.25f

    // A fade that is over well before the probe, and an authored opacity it settles on below full strength.
    val SHORT_FADE = 500.milliseconds
    val SETTLED_POSITION = 1.seconds
    const val SETTLED_FRAME = 30
    const val SETTLED_OPACITY = 0.5f

    // A third of the way into a frame period, so the scrub lands on the slot it sits in while the
    // first source frame at or after it is the next one. The fade is steep enough that one frame of
    // it moves the drawn pixels.
    val OFF_GRID_POSITION = 510.milliseconds
    val OFF_GRID_FADE = 1.seconds

    // Trims ending three tenths and three fifths of the way into a frame period, well inside the fixture, so the
    // first one's end rounds down to its slot and the second one's rounds up.
    val ROUNDS_DOWN_TRIM = 1_010.milliseconds
    val ROUNDS_UP_TRIM = 1_020.milliseconds

    // A window in the middle of the fixture, with a scrub landing short of it, one well inside it
    // and one well past it. Every scrub sits a third of the way into a frame period, so the pump
    // opens on the slot it sits in and the overlay's branch has to be carried onto that slot too.
    val WINDOW = TimeRange.of(500.milliseconds, 1_500.milliseconds)
    val BEFORE_WINDOW = 210.milliseconds
    val INSIDE_WINDOW = 810.milliseconds
    val AFTER_WINDOW = 1_610.milliseconds

    // A window over the second half of the one second primary laid over the bed, closing with it, and a third of a
    // frame period for a scrub to sit off its slot by.
    val CLOSES_WITH_PRIMARY = TimeRange.of(500.milliseconds, 1.seconds)
    val OFF_SLOT = 10.milliseconds

    // Past the two frames each clip clones ahead of its cut, so a play from there runs the clip's own frames out first.
    const val PLAY_IN_SLOTS = 3L

    // Twice the default, so the half left on the clip and the half pushed into the bar each hold a probe square with
    // room to spare once the clip is scaled down into the frame.
    const val CLIP_OVERLAY_SCALE = 0.4f

    const val RGB = 3

    val WHITE_SQUARE: ByteArray = pngOf(Color.WHITE.rgb, OVERLAY_SIZE)
    val OVERLAY_SQUARE: ByteArray = pngOf(OVERLAY_COLOR, OVERLAY_SIZE)
  }
}
