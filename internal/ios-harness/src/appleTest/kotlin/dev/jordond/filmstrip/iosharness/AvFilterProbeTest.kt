package dev.jordond.filmstrip.iosharness

import platform.Foundation.NSProcessInfo
import kotlin.test.Test
import kotlin.test.assertNotNull
import kotlin.test.assertTrue

/**
 * The two answers the Apple export backend is blocked on.
 *
 * Runs on the host against real AVFoundation, which is the point. The simulator conflates "the
 * decoder refused the fixture" with "the mechanism is wrong", and only one of those is a design
 * question.
 */
class AvFilterProbeTest {
  @Test
  fun theFilterHandlerReachesKotlinAndSaysWhatItIsRendering() {
    val fixtures = environment("FILMSTRIP_FIXTURES")
    assertNotNull(fixtures, "FILMSTRIP_FIXTURES was not set; the test task should provide it")

    val result = AvFilterProbe().run("$fixtures/$CLIP_A", "$fixtures/$CLIP_B", FRAMES)

    assertNull(result.failure?.reason)
    assertTrue(result.observations.isNotEmpty(), "the handler never ran, so the block does not bridge")

    val sizes = result.observations.map { it.sourceWidth to it.sourceHeight }.distinct()
    val renders = result.observations.map { it.renderWidth to it.renderHeight }.distinct()
    println("PROBE frames=${result.observations.size}")
    println("PROBE distinct source extents = $sizes")
    println("PROBE distinct render sizes = $renders")
    println("PROBE first = ${result.observations.first()}")
    println("PROBE last  = ${result.observations.last()}")

    // Whichever way this lands is a design input, so it is reported rather than asserted. One
    // distinct extent across two differently sized clips means AVFoundation fitted each to the
    // track, and a per-clip effect cannot be measured against the clip's own frame.
    val spansBothClips = result.observations.any { it.compositionTimeSeconds >= FIRST_CLIP_SECONDS }
    assertTrue(
      spansBothClips,
      "only reached ${result.observations.last().compositionTimeSeconds}s, never entered clip B",
    )
  }

  private fun assertNull(value: String?) {
    assertTrue(value == null, "probe failed: $value")
  }

  private fun environment(name: String): String? = NSProcessInfo.processInfo.environment[name] as? String

  private companion object {
    const val CLIP_A = "concat_a.mp4"
    const val CLIP_B = "concat_b.mp4"

    // concat_a is 3s at 30fps, so this reaches well into concat_b.
    const val FRAMES = 150
    const val FIRST_CLIP_SECONDS = 3.0
  }
}
