package dev.jordond.filmstrip.transform.internal.probe

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.PlannerFixture
import dev.jordond.filmstrip.transform.internal.composition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * The source a failed read is reported against when the backend does not say which one it was reading.
 */
class ReadSourcesTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a one source export names that source`() {
    val clip = fixture.clip()

    resolved(Composition(listOf(Track(listOf(clip))))).readSources shouldBe clip.source.describe()
  }

  // The primary repeats its first source after the second, and a looping bed lays its own source more than once, so
  // each is named once in the order it was first laid.
  @Test
  fun `every source is named once in the order it is first laid`() {
    val first = fixture.clip(duration = 1.seconds)
    val second = fixture.clip(duration = 1.seconds)
    val bed = fixture.audioOnlyClip(1.seconds)
    val composition =
      Composition(
        listOf(
          Track(listOf(first, second, first)),
          Track(listOf(bed), content = TrackContent.Audio, looping = true),
        ),
      )

    resolved(composition).readSources shouldBe
      listOf(first, second, bed).joinToString(", ") { it.source.describe() }
  }

  private fun resolved(composition: Composition): ResolvedComposition =
    fixture.resolve(composition, ExportSpec(frameRate = 25), resolvers = emptyList())
}
