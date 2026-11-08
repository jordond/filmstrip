package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.maps.shouldBeEmpty
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * What an engine reads from outside an edit to negotiate it, and how each answer is held for the engine's life.
 */
class PlanningReadsTest {
  private val fixture = PlannerFixture()
  private val trimmed = fixture.clip(trim = TimeRange.of(1_500.milliseconds, 4_000.milliseconds))
  private val infos = fixture.infos(composition(trimmed))
  private val device = fixture.device()
  private var deviceReads = 0
  private val openingReads = mutableListOf<Pair<MediaSource, Duration>>()

  @Test
  fun `a read hands back what each source is the device and where a copy opens`() =
    runTest {
      val read = reads().read(composition(trimmed)).shouldBeInstanceOf<PlanningReadResult.Read>()

      read.infos shouldBe infos
      read.device shouldBeSameInstanceAs device
      read.openings shouldBe mapOf(trimmed.source to OPENING)
    }

  @Test
  fun `the device is read once however often a negotiation asks`() =
    runTest {
      val reads = reads()

      reads.read(composition(trimmed))
      reads.device()
      reads.read(composition(trimmed))

      deviceReads shouldBe 1
    }

  @Test
  fun `each cut of a source is read once and another cut is read again`() =
    runTest {
      val reads = reads()
      val later = Clip(trimmed.source, TimeRange.of(2_000.milliseconds, 4_000.milliseconds))

      reads.read(composition(trimmed))
      reads.read(composition(trimmed))
      reads.read(composition(later))

      openingReads shouldBe listOf(trimmed.source to 1_500.milliseconds, trimmed.source to 2_000.milliseconds)
    }

  // A cut with no sync sample this engine can name is an answer too, and asking again would get the same one.
  @Test
  fun `a cut with no opening is held and not asked again`() =
    runTest {
      val reads = PlanningReads(prober(), { device }, { source, cut -> null.also { openingReads += source to cut } })

      val first = reads.read(composition(trimmed)).shouldBeInstanceOf<PlanningReadResult.Read>()
      reads.read(composition(trimmed))

      first.openings.shouldBeEmpty()
      openingReads.size shouldBe 1
    }

  @Test
  fun `an untrimmed clip and an edit of several clips read no opening`() =
    runTest {
      val untrimmed = Clip(trimmed.source)
      val reads = reads()

      reads.read(composition(untrimmed))
      reads.read(EditComposition(listOf(Track(listOf(trimmed, trimmed)))))

      openingReads.shouldBeEmpty()
    }

  @Test
  fun `a source named twice in one edit is probed once`() =
    runTest {
      var probes = 0
      val counted = MediaProber { source -> ProbeResult.Success(infos.getValue(source)).also { probes++ } }
      val reads = PlanningReads(counted, { device }, { _, _ -> OPENING })

      val read =
        reads
          .read(
            EditComposition(listOf(Track(listOf(trimmed, trimmed)))),
          ).shouldBeInstanceOf<PlanningReadResult.Read>()

      probes shouldBe 1
      read.infos shouldBe infos
    }

  @Test
  fun `a source that fails to probe fails the read before the device is asked`() =
    runTest {
      val error = FilmstripError.SourceUnreadable("gone", "the prober could not open it")
      val reads = PlanningReads({ ProbeResult.Failure(error) }, { device.also { deviceReads++ } }, { _, _ -> OPENING })

      val failed = reads.read(composition(trimmed)).shouldBeInstanceOf<PlanningReadResult.Failed>()

      failed.error shouldBeSameInstanceAs error
      deviceReads shouldBe 0
    }

  private fun reads(): PlanningReads =
    PlanningReads(
      prober(),
      readDevice = { device.also { deviceReads++ } },
      readOpening = { source, cut ->
        openingReads += source to cut
        OPENING
      },
    )

  private fun prober(): MediaProber = MediaProber { source -> ProbeResult.Success(infos.getValue(source)) }

  private companion object {
    val OPENING = 1_000.milliseconds
  }
}
