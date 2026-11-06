package dev.jordond.filmstrip.thumbnail

import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.HdrMode
import io.kotest.matchers.shouldBe
import kotlin.test.Test
import kotlin.test.assertFailsWith
import kotlin.time.Duration

class ThumbnailRequestTest {
  @Test
  fun `a request stands in for the default export unless told otherwise`() {
    val plain = ThumbnailRequest(COMPOSITION_FOR_SPEC, Duration.ZERO, null, 1L, precise = true)
    val mapped =
      ThumbnailRequest(
        COMPOSITION_FOR_SPEC,
        Duration.ZERO,
        null,
        1L,
        precise = true,
        exportSpec = ExportSpec(hdr = HdrMode.ToneMapToSdr),
      )

    plain.exportSpec shouldBe ExportSpec()
    (plain == mapped) shouldBe false
  }

  @Test
  fun `null asks for the composition's own height`() {
    request(null).heightPx shouldBe null
  }

  @Test
  fun `a height of zero or less is refused when the request is built`() {
    assertFailsWith<IllegalArgumentException> { request(0) }
    assertFailsWith<IllegalArgumentException> { request(-1) }
  }

  private fun request(heightPx: Int?): ThumbnailRequest =
    ThumbnailRequest(EditComposition(tracks = emptyList()), Duration.ZERO, heightPx, 0L, precise = false)
}

private val COMPOSITION_FOR_SPEC = EditComposition(emptyList())
