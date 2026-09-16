package dev.jordond.filmstrip.playback

import kotlin.test.Test
import kotlin.test.assertEquals

/**
 * Which floor the web pixel suites hold an exported frame to, read off the user agent.
 *
 * The suites only ever run in one browser, so versions either side of Chrome 152 and a user agent
 * with no version in it are checked here instead.
 */
class EncodedMinSsimTest {
  @Test
  fun chrome152AndOlderGetTheLowerFloor() {
    assertEquals(RELAXED, encodedMinSsim(userAgent(151)))
    assertEquals(RELAXED, encodedMinSsim(userAgent(152)))
    assertEquals(RELAXED, encodedMinSsim(userAgent(152, product = "HeadlessChrome")))
  }

  @Test
  fun chrome153AndNewerKeepTheStrictFloor() {
    assertEquals(STRICT, encodedMinSsim(userAgent(153)))
    assertEquals(STRICT, encodedMinSsim(userAgent(160, product = "HeadlessChrome")))
  }

  @Test
  fun aUserAgentWithNoChromeVersionKeepsTheStrictFloor() {
    assertEquals(STRICT, encodedMinSsim(""))
    assertEquals(STRICT, encodedMinSsim("Mozilla/5.0 (X11; Linux x86_64; rv:140.0) Gecko/20100101 Firefox/140.0"))
    assertEquals(STRICT, encodedMinSsim("Mozilla/5.0 Chrome/unknown"))
  }

  private fun userAgent(
    major: Int,
    product: String = "Chrome",
  ): String =
    "Mozilla/5.0 (X11; Linux x86_64) AppleWebKit/537.36 (KHTML, like Gecko) $product/$major.0.0.0 Safari/537.36"

  private companion object {
    const val STRICT = 0.985
    const val RELAXED = 0.975
  }
}
