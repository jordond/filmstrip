package dev.jordond.filmstrip.effects.overlay

import dev.jordond.filmstrip.effects.overlay.EncodedPictures.SIZE
import dev.jordond.filmstrip.effects.overlay.EncodedPictures.TEXEL
import dev.jordond.filmstrip.test.AppleDisplayP3
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldBeNull
import kotlin.test.Test

/**
 * The iOS simulator's own ImageIO answers. It writes no HEVC image sequence, so ImageSequenceReadHardwareTest is left
 * out of the simulator's run.
 *
 * This fails once the simulator writes a HEICS, which says that case can run here too.
 */
class ImageIoSimulatorHardwareTest {
  @Test
  fun `imageio writes no heics on the simulator`() {
    withClue("the simulator now writes $HEICS") { AppleDisplayP3.picture(HEICS, SIZE, TEXEL).shouldBeNull() }
  }

  private companion object {
    const val HEICS = "public.heics"
  }
}
