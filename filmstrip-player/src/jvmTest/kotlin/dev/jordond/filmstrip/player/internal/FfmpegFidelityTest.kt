package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.capability.OutputProperty
import dev.jordond.filmstrip.capability.PreviewFidelity
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * What the ffmpeg preview says about how the exported grade looks, which follows the grade the plan settled on.
 */
class FfmpegFidelityTest {
  @Test
  fun `a kept grade is not what the pump shows`() {
    hdrAppearance(ResolvedHdr.Keep) shouldBe PreviewFidelity.NotPreviewable
  }

  @Test
  fun `a tone mapped export is what the pump shows`() {
    hdrAppearance(ResolvedHdr.ToneMap) shouldBe PreviewFidelity.Exact
  }

  @Test
  fun `a planned preview carries the note for the grade it settled on`() {
    fixturePlan()
      .info.fidelity
      .single { it.property == OutputProperty.HdrAppearance }
      .fidelity shouldBe hdrAppearance(fixturePlan().resolved.hdr)
  }

  private fun hdrAppearance(hdr: ResolvedHdr): PreviewFidelity =
    ffmpegFidelity(hdr).single { it.property == OutputProperty.HdrAppearance }.fidelity
}
