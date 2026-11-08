package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.diagnostics.DiagnosticEvent
import dev.jordond.filmstrip.diagnostics.DiagnosticEvent.Detail
import dev.jordond.filmstrip.diagnostics.DiagnosticListener
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.LiveBranches
import dev.jordond.filmstrip.effect.LiveInput
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effects.filters.film.FilmSignal
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.ffmpeg.internal.lowering.MAX_LIVE_INPUTS
import dev.jordond.filmstrip.ffmpeg.internal.process.LiveBinding
import dev.jordond.filmstrip.ffmpeg.support.FILTER_RATE
import dev.jordond.filmstrip.ffmpeg.support.FilterFrames
import io.kotest.assertions.withClue
import io.kotest.matchers.ints.shouldBeLessThanOrEqual
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import java.io.File
import kotlin.math.abs
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.TimeSource

/**
 * A fake look with a live form and a drawn form that write one code per tick, read through a real ffmpeg.
 *
 * The drawn form is the oracle, so a frame that reads the wrong tick shows up against the graph's own clock.
 */
class FfmpegLiveInputMediaTest {
  private var planeAt: (Int) -> ByteArray = { tick -> ByteArray(WIDTH * HEIGHT) { codeOf(tick).toByte() } }
  private val resolver =
    EffectResolver { spec, _, _ ->
      if (spec is TickLook) EffectResolution.Resolved(PlatformEffect(fragment())) else null
    }
  private val events = mutableListOf<DiagnosticEvent>()
  private val listener = DiagnosticListener { event -> synchronized(events) { events += event } }
  private val live = FilterFrames(resolver, listener)
  private val drawn =
    FilterFrames(resolver, listener, bindFeeds = { LiveBinding.Refused(REFUSAL) })

  @Test
  fun `a plane per tick lands on its frame from the top`() =
    runBlocking<Unit> { assertFormsAgree(plan(clip()), Duration.ZERO, opensOn = 0) }

  @Test
  fun `a second clip reads its ticks from the composition clock`() =
    runBlocking<Unit> {
      val plan = plan(clip(), clip())
      val slots = FILTER_RATE * CLIP_SECONDS * 2

      val read = live.frames(plan, Duration.ZERO, slots)
      val oracle = drawn.frames(plan, Duration.ZERO, slots)
      read.forEachIndexed { slot, frame ->
        withClue("slot $slot") { frame.contentEquals(oracle[slot]) shouldBe true }
        assertReadsTick(frame, slot)
      }
    }

  @Test
  fun `a windowed preview reads the tick of the time it opened on`() =
    runBlocking<Unit> {
      val at = 1.2.seconds
      assertFormsAgree(plan(clip()), at, opensOn = (at.inWholeMilliseconds * FILTER_RATE / 1000).toInt())
    }

  @Test
  fun `a seek to another time reads that time on a new stream`() =
    runBlocking<Unit> {
      val plan = plan(clip())
      listOf(500.milliseconds, 1.5.seconds, 300.milliseconds).forEach { at ->
        assertFormsAgree(plan, at, opensOn = (at.inWholeMilliseconds * FILTER_RATE / 1000).toInt())
      }
    }

  @Test
  fun `a composition look runs on the whole timeline`() =
    runBlocking<Unit> {
      val plain = live.clipOf(WIDTH, HEIGHT, ::grey)
      val plan = live.composition(listOf(plain, plain), listOf(TickLook))

      live.frames(plan, Duration.ZERO, FILTER_RATE * CLIP_SECONDS + 4).forEachIndexed { slot, frame ->
        assertReadsTick(frame, slot)
      }
    }

  @Test
  fun `the stream carries its tag and its stamp`() =
    runBlocking<Unit> {
      val form = live.invocationOf(plan(clip())).liveForm.shouldNotBeNull()

      form.filterGraph shouldContain
        "setparams=range=tv:colorspace=bt709,format=pix_fmts=yuva420p,settb=expr=1/1000000,setpts=expr="
      val (opens, closes) = FilmSignal.grainTickOpening("|").split("|")
      form.filterGraph shouldContain "setpts=expr=$opens"
      form.filterGraph shouldContain closes
    }

  @Test
  fun `a refused bind runs the drawn graph and says why`() =
    runBlocking<Unit> {
      val opened = drawn.opened(plan(clip()))
      opened.close()

      val reason = synchronized(events) { events.last { it.name == DiagnosticEvent.PREVIEW } }.detail[Detail.REASON]
      reason.shouldNotBeNull() shouldContain REFUSAL
      synchronized(events) { events.last() }.detail[Detail.COMMAND].shouldNotBeNull() shouldNotContain "tcp://"
    }

  @Test
  fun `a run on live planes names no fallback`() =
    runBlocking<Unit> {
      live.opened(plan(clip())).close()

      val event = synchronized(events) { events.last { it.name == DiagnosticEvent.PREVIEW } }
      event.detail[Detail.REASON].shouldBeNull()
      event.detail[Detail.COMMAND].shouldNotBeNull() shouldContain "tcp://127.0.0.1:"
    }

  @Test
  fun `too many live inputs fall back on the drawn graph by name`() =
    runBlocking<Unit> {
      val one = clip()
      val invocation = live.invocationOf(plan(*Array(MAX_LIVE_INPUTS + 1) { one }))

      invocation.liveForm.shouldBeNull()
      invocation.liveFallback.shouldNotBeNull() shouldContain "at most $MAX_LIVE_INPUTS"
    }

  @Test
  fun `closing a preview mid stream leaves no process and no feed`() =
    runBlocking<Unit> {
      val opened = live.opened(plan(clip()))
      opened.next().shouldNotBeNull()
      val process = ProcessHandle.of(opened.processId.shouldNotBeNull())

      withTimeout(BOUND) { opened.close() }

      process.map { it.isAlive }.orElse(false) shouldBe false
      awaitNoFeedThreads()
    }

  @Test
  fun `an export on live planes ends with its feeds gone`() =
    runBlocking<Unit> {
      val statuses = withTimeout(EXPORT_BOUND) { live.exportStatuses(plan(clip()), output()) }

      statuses.last().shouldBeInstanceOf<ExportStatus.Success>()
      awaitNoFeedThreads()
    }

  @Test
  fun `cancelling an export leaves no process and no feed`() =
    runBlocking<Unit> {
      val job = launch(Dispatchers.IO) { live.exportStatuses(plan(clip(), clip()), output()) }
      withTimeout(EXPORT_BOUND) {
        while (!commandRan()) delay(SETTLE)
      }

      withTimeout(EXPORT_BOUND) {
        job.cancel()
        job.join()
      }

      awaitNoFeedThreads()
    }

  @Test
  fun `a plane that cannot be drawn fails the export by name`() =
    runBlocking<Unit> {
      planeAt = { tick ->
        check(tick < FAILS_AT) { "no plane at tick $tick" }
        ByteArray(WIDTH * HEIGHT)
      }

      val finished = withTimeout(EXPORT_BOUND) { live.exportStatuses(plan(clip()), output()) }.last()

      finished.shouldBeInstanceOf<ExportStatus.Failure>().error.message shouldContain "no plane at tick $FAILS_AT"
      awaitNoFeedThreads()
    }

  @Test
  fun `a plane that throws an error fails the export rather than truncating it`() =
    runBlocking<Unit> {
      planeAt = { tick ->
        if (tick >= FAILS_AT) throw OutOfMemoryError("no room for tick $tick")
        ByteArray(WIDTH * HEIGHT)
      }

      val finished = withTimeout(EXPORT_BOUND) { live.exportStatuses(plan(clip()), output()) }.last()

      finished.shouldBeInstanceOf<ExportStatus.Failure>().error.message shouldContain "no room for tick $FAILS_AT"
      awaitNoFeedThreads()
    }

  @Test
  fun `a plane that cannot be drawn reads as a failed preview`() =
    runBlocking<Unit> {
      planeAt = { tick ->
        check(tick < FAILS_AT) { "no plane at tick $tick" }
        ByteArray(WIDTH * HEIGHT)
      }
      val opened = live.opened(plan(clip()))
      withTimeout(EXPORT_BOUND) {
        while (opened.next() != null) {
          // Reads until the graph runs out of its stream.
        }
      }

      (opened.exitStatus() == 0) shouldBe false
      opened.errors() shouldContain "no plane at tick $FAILS_AT"
      opened.close()
    }

  private fun commandRan(): Boolean = synchronized(events) { events.any { it.name == DiagnosticEvent.INVOCATION } }

  private fun output(): File = File.createTempFile("live-input", ".mp4").apply { delete() }

  private fun clip(): Clip = live.clipOf(WIDTH, HEIGHT, ::grey, listOf(TickLook))

  private fun plan(vararg clips: Clip): EditComposition = live.composition(clips.toList())

  private suspend fun assertFormsAgree(
    plan: EditComposition,
    at: Duration,
    opensOn: Int,
  ) {
    val read = live.frames(plan, at, FRAMES)
    val oracle = drawn.frames(plan, at, FRAMES)
    read.forEachIndexed { index, frame ->
      withClue("frame $index of a preview at $at") {
        frame.contentEquals(oracle[index]) shouldBe true
        assertReadsTick(frame, opensOn + index)
      }
    }
  }

  private fun assertReadsTick(
    frame: ByteArray,
    slot: Int,
  ) {
    // A frame exactly on a tick reads the side its source's stamp rounds to, which the drawn graph settles.
    if (slot * TICK_RATE % FILTER_RATE == 0) return
    val tick = FilmSignal.grainTickAt(slot.toDouble() / FILTER_RATE)
    val red = frame[0].toInt() and 0xFF
    val expected = ((codeOf(tick) - LUMA_BLACK) * 255.0 / LUMA_CODES).roundToInt()
    withClue("slot $slot at tick $tick") { abs(red - expected) shouldBeLessThanOrEqual 1 }
  }

  private fun fragment(): FilterFragment {
    val code = "$CODE_BASE+mod(floor(floor(T*1000000.0+0.5)/1000000.0*$TICK_RATE+1.0E-9)*$CODE_STEP,$CODE_SPAN)"
    val add = { shortest: Boolean ->
      FilterNode(
        "lut2",
        *((if (shortest) listOf("shortest" to "1") else emptyList()) + listOf("c0" to "y", "c1" to "x", "c2" to "x"))
          .toTypedArray(),
      )
    }
    return FilterFragment(
      readsCompositionClock = true,
      branches =
        listOf(
          FilterBranch(listOf(FilterBranch.INPUT), emptyList(), listOf("base", "mapIn")),
          FilterBranch(
            listOf("mapIn"),
            listOf(FilterNode("geq", "lum" to code, "cb" to "128", "cr" to "128")),
            listOf("map"),
          ),
          FilterBranch(listOf("base", "map"), listOf(add(false)), listOf("out")),
        ),
      live =
        LiveBranches(
          listOf(
            LiveInput(
              "plane",
              WIDTH,
              HEIGHT,
              TICK_RATE.toDouble(),
              "tick",
              { tick -> planeAt(tick) },
              FilmSignal::grainTickOpening,
            ),
          ),
          listOf(FilterBranch(listOf(FilterBranch.INPUT, "plane"), listOf(add(true)), listOf("out"))),
        ),
    )
  }

  private fun grey(fraction: Double): DoubleArray = doubleArrayOf(0.5, 0.5, 0.5 + 0 * fraction)

  private fun awaitNoFeedThreads() {
    val deadline = TimeSource.Monotonic.markNow() + BOUND
    val alive = { Thread.getAllStackTraces().keys.count { it.name.startsWith("filmstrip-ffmpeg-live-") } }
    while (alive() > 0 && deadline.hasNotPassedNow()) Thread.sleep(SETTLE.inWholeMilliseconds)
    alive() shouldBe 0
  }

  private object TickLook : EffectSpec {
    override val id: String = "test.tick-look"
  }

  private companion object {
    const val WIDTH = 64
    const val HEIGHT = 36
    const val FRAMES = 8
    const val CLIP_SECONDS = 2
    const val TICK_RATE = 24
    const val CODE_BASE = 20
    const val CODE_STEP = 7
    const val CODE_SPAN = 200
    const val LUMA_BLACK = 16
    const val LUMA_CODES = 219
    const val FAILS_AT = 6
    const val REFUSAL = "sandboxed"
    val SETTLE = 20.milliseconds
    val BOUND = 5.seconds
    val EXPORT_BOUND = 60.seconds

    fun codeOf(tick: Int): Int = CODE_BASE + tick * CODE_STEP % CODE_SPAN
  }
}
