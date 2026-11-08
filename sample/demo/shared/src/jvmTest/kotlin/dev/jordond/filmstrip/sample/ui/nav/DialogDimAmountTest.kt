package dev.jordond.filmstrip.sample.ui.nav

import dev.jordond.filmstrip.sample.ui.Tokens
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class DialogDimAmountTest {
  @Test
  fun `the dim amount is the backdrop alpha`() {
    dialogDimAmount() shouldBe Tokens.backdrop.alpha
  }
}
