package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the browser preview says about how the exported grade looks, which follows the grade the plan settled on.
 */
class BrowserFidelityTest {
  @Test
  fun `a kept grade is not what the canvas shows`() {
    hdrAppearance(ResolvedHdr.Keep) shouldBe PreviewFidelity.NotPreviewable
  }

  @Test
  fun `a tone mapped export is what the canvas shows`() {
    hdrAppearance(ResolvedHdr.ToneMap) shouldBe PreviewFidelity.Exact
  }

  private fun hdrAppearance(hdr: ResolvedHdr): PreviewFidelity =
    browserFidelity(hdr).single { it.property == OutputProperty.HdrAppearance }.fidelity
}
