package dev.jordond.filmstrip.picture

import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.picture.heif.ASSEMBLY_TEXEL_BYTES
import dev.jordond.filmstrip.picture.heif.assemblyFrameBudget
import dev.jordond.filmstrip.picture.heif.assemblyPeakBytes
import io.kotest.assertions.withClue
import io.kotest.matchers.longs.shouldBeGreaterThan
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class PictureCeilingTest {
  @Test
  fun aPeakIsWhatIsHeldPlusWhatIsHeldOnTheWayPlusWhatIsMade() {
    picturePeakBytes(3_000L, 500L, 70_000L) shouldBe 73_500L
    picturePeakBytes(Long.MAX_VALUE - 2, 1L, 1L) shouldBe Long.MAX_VALUE
  }

  @Test
  fun aPeakWithAPartMissingNegativeOrPastLongIsNull() {
    withClue("held missing") { picturePeakBytes(null, 0L, 1L).shouldBeNull() }
    withClue("made missing") { picturePeakBytes(1L, 0L, null).shouldBeNull() }
    withClue("band negative") { picturePeakBytes(1L, -1L, 1L).shouldBeNull() }
    withClue("held and band past Long") { picturePeakBytes(Long.MAX_VALUE, 1L, 0L).shouldBeNull() }
    withClue("all three past Long") { picturePeakBytes(Long.MAX_VALUE - 2, 1L, 2L).shouldBeNull() }
  }

  @Test
  fun anAssemblyHoldsEveryInputAtItsSizeBesideTheCanvas() {
    val inputs = listOf(Size(8, 8), Size(16, 4), Size(3, 5))
    assemblyPeakBytes(Size(20, 12), inputs) shouldBe (20L * 12 + 64 + 64 + 15) * ASSEMBLY_TEXEL_BYTES
    assemblyPeakBytes(Size(20, 12), emptyList()) shouldBe 20L * 12 * ASSEMBLY_TEXEL_BYTES
  }

  @Test
  fun aFrameOfTheBudgetBesideACanvasHoldsTheCeilingAndOneTexelMorePassesIt() {
    val canvas = Size(4000, 3000)
    val budget = assemblyFrameBudget(canvas)
    budget shouldBe PICTURE_PEAK_CEILING - 4000L * 3000 * ASSEMBLY_TEXEL_BYTES
    val rows = (budget / ASSEMBLY_TEXEL_BYTES).toInt()
    assemblyPeakBytes(canvas, listOf(Size(1, rows))) shouldBe
      PICTURE_PEAK_CEILING - budget % ASSEMBLY_TEXEL_BYTES
    assemblyPeakBytes(canvas, listOf(Size(1, rows + 1))).shouldNotBeNull() shouldBeGreaterThan PICTURE_PEAK_CEILING
  }

  @Test
  fun aCanvasThatReachesTheCeilingLeavesNoBudget() {
    assemblyFrameBudget(Size(1, (PICTURE_PEAK_CEILING / ASSEMBLY_TEXEL_BYTES).toInt())) shouldBe 0L
    assemblyFrameBudget(Size(1 shl 16, 1 shl 16)) shouldBe 0L
    assemblyFrameBudget(Size(Int.MAX_VALUE, Int.MAX_VALUE)) shouldBe 0L
  }

  @Test
  fun anAssemblyPastLongIsNullRatherThanWrapped() {
    val widest = Size(Int.MAX_VALUE, Int.MAX_VALUE)
    withClue("one input past Long") { assemblyPeakBytes(Size(8, 8), listOf(widest)).shouldBeNull() }
    withClue("a canvas past Long") { assemblyPeakBytes(widest, emptyList()).shouldBeNull() }
    val tall = Size(Int.MAX_VALUE, 1 shl 28)
    withClue("inputs that pass Long only together") { assemblyPeakBytes(Size(8, 8), List(4) { tall }).shouldBeNull() }
  }
}
