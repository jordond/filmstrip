package dev.jordond.filmstrip.compose

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StripSpecExportTest {
  private fun strip(exportSpec: ExportSpec = ExportSpec()) =
    StripSpec(Composition(emptyList()), 1L, emptyList(), null, 1L, 0, exportSpec)

  @Test
  fun aNewExportSpecMissesWhatTheStripHolds() {
    strip(ExportSpec(hdr = HdrMode.ToneMapToSdr)).invalidates(strip()) shouldBe true
  }

  @Test
  fun theSameExportSpecKeepsWhatTheStripHolds() {
    strip().invalidates(strip()) shouldBe false
  }
}
