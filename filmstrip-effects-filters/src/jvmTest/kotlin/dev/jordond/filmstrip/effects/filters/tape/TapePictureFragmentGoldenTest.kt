package dev.jordond.filmstrip.effects.filters.tape

import dev.jordond.filmstrip.effect.FilterBranch
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effects.filters.betamax.Betamax
import dev.jordond.filmstrip.effects.filters.hi8.Hi8
import dev.jordond.filmstrip.effects.filters.vhs.Tracking
import dev.jordond.filmstrip.effects.filters.vhs.Vhs
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import io.kotest.matchers.shouldBe
import java.security.MessageDigest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

/**
 * Pins the graph text a tape look with no settle lowers to, so adding a settle changes none of it. Each look is lowered
 * on a scope that opens with the composition, where a rolling band's anchor is the one it always had, and its graph is
 * held as a digest of every branch, node and argument the fragment holds, and the pad and size of each live input. The
 * digests were taken before a look could settle, so they are the graph of the look as it was, bar the crop that takes
 * the padding off, which is now an exact crop and changed every entry, and the exact read across the whole frame, which
 * now writes the alpha it reads. Its entry, vhs rolling anywhere, took a second change.
 *
 * A look that is meant to lower differently takes its new digests from the failure message, which prints them all.
 */
class TapePictureFragmentGoldenTest {
  @Test
  fun `a look that does not settle lowers to the graph it always has`() {
    val drawn =
      LOOKS.mapValues { (_, look) -> digest(render(tapePictureFragment(look, Size(WIDTH, HEIGHT)))) } +
        COVERAGES.mapKeys { "vhs rolling ${it.key}" }.mapValues { (_, opaque) ->
          digest(render(tapePictureFragment(LOOKS.getValue("vhs rolling"), Size(WIDTH, HEIGHT), opaque)))
        }

    drawn shouldBe GOLDEN
  }

  private fun render(fragment: FilterFragment): String =
    buildString {
      fun branches(
        label: String,
        list: List<FilterBranch>,
      ) {
        list.forEach { branch ->
          append(label).append(" [").append(branch.reads.joinToString(",")).append("] ")
          append(
            branch.nodes.joinToString(",") { node ->
              node.name + "=" + node.arguments.joinToString(":") { "${it.key}=${it.value}" }
            },
          )
          append(" [").append(branch.writes.joinToString(",")).appendLine("]")
        }
      }
      branches("drawn", fragment.branches)
      fragment.live?.let { live ->
        live.inputs.forEach { appendLine("input ${it.pad} ${it.width}x${it.height}") }
        branches("live", live.branches)
      }
    }

  private fun digest(text: String): String =
    MessageDigest
      .getInstance("SHA-256")
      .digest(text.encodeToByteArray())
      .joinToString("") { "%02x".format(it) }
      .take(DIGEST_LENGTH)

  private companion object {
    const val WIDTH = 640
    const val HEIGHT = 360
    const val DIGEST_LENGTH = 16

    // A picture with a clear bar down one side, which reads the exact taps beside its edge, and one that may be clear
    // anywhere, which reads them across the whole frame.
    val COVERAGES = mapOf("edges" to NormalizedRect(0.25f, 0f, 1f, 1f), "anywhere" to null)

    val LOOKS =
      mapOf(
        "vhs" to Vhs(),
        "vhs rolling" to Vhs(tracking = Tracking.Rolling()),
        "vhs rolling anchored" to
          Vhs(intensity = 0.6f, tracking = Tracking.Rolling(4.seconds, snow = 0.9f, at = 2.seconds)),
        "betamax events" to Betamax(intensity = 0.5f),
        "hi8 no band" to Hi8(intensity = 0.8f, tracking = Tracking.Off),
      )

    val GOLDEN =
      mapOf(
        "vhs" to "d6bc47bc737a1217",
        "vhs rolling" to "8c7eeeb4c7ca1a1c",
        "vhs rolling anchored" to "19034aff460178ad",
        "betamax events" to "34e366a8bddfbd1a",
        "hi8 no band" to "660c0884a509bd08",
        "vhs rolling edges" to "21c9c1d6f878d14b",
        // The exact read across the frame writes the alpha it reads, where it wrote none, so a look after another draws.
        "vhs rolling anywhere" to "b8cc42b5e1adb6b6",
      )
  }
}
