package dev.jordond.filmstrip.sample.tryit

import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import java.io.File
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Holds the landing page's snippet to the edit this sample runs, since the snippet is source text a reader copies and
 * shares no code with [beachEdit].
 */
class LandingSnippetTest {
  private val snippet = File(System.getProperty("landing.snippet")).readText()

  private fun floatArgumentOf(call: String): Float =
    Regex("""$call\(([\d.]+)f\)""").find(snippet)!!.groupValues[1].toFloat()

  @Test
  fun `the snippet carries the grade the sample sets`() {
    floatArgumentOf("saturation") shouldBe GRADE_SATURATION
    floatArgumentOf("contrast") shouldBe GRADE_CONTRAST
  }

  @Test
  fun `the snippet carries the trim the sample keeps`() {
    val (start, end) = Regex("""trim\((\d+)\.seconds\.\.(\d+)\.seconds\)""").find(snippet)!!.destructured
    start.toInt().seconds shouldBe TRIM_RANGE.start
    end.toInt().seconds shouldBe TRIM_RANGE.endInclusive
  }

  @Test
  fun `the snippet carries the title the sample draws`() {
    snippet shouldContain "\"$TITLE_TEXT\""
  }
}
