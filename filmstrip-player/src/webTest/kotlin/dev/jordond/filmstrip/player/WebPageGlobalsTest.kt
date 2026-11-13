@file:OptIn(ExperimentalWasmJsInterop::class)

package dev.jordond.filmstrip.player

import io.kotest.matchers.shouldBe
import kotlin.js.ExperimentalWasmJsInterop
import kotlin.test.Test
import kotlin.test.assertFailsWith

class WebPageGlobalsTest {
  @Test
  fun `a global that was absent stays absent afterwards`() {
    Reflect.has(globalThis, ABSENT) shouldBe false

    withoutGlobals(ABSENT) { Reflect.has(globalThis, ABSENT) shouldBe false }

    Reflect.has(globalThis, ABSENT) shouldBe false
  }

  @Test
  fun `a global taken off the page comes back`() {
    withoutGlobals(PRESENT) { Reflect.has(globalThis, PRESENT) shouldBe false }

    Reflect.has(globalThis, PRESENT) shouldBe true
  }

  // The page refuses to delete `undefined`, so the second deletion fails with the first one already done.
  @Test
  fun `a deletion that fails puts back what the earlier ones took`() {
    assertFailsWith<AssertionError> { withoutGlobals(PRESENT, "undefined") {} }

    Reflect.has(globalThis, PRESENT) shouldBe true
  }

  private companion object {
    const val PRESENT = "VideoDecoder"
    const val ABSENT = "filmstripNeverDefined"
  }
}
