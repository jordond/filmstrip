package dev.jordond.filmstrip.convention

import io.kotest.matchers.shouldBe
import kotlin.test.Test

/**
 * Pins which source sets the test-only import ban reaches. Every source set whose directory names a test is checked
 * against it, and no main source set is.
 */
class ForbiddenImportsTest {
  @Test
  fun `a source set named for a test is a test source`() {
    listOf(
      "commonTest/kotlin/dev/jordond/filmstrip/A.kt",
      "appleHardwareTest/kotlin/dev/jordond/filmstrip/A.kt",
      "iosSimulatorArm64Test/kotlin/dev/jordond/filmstrip/A.kt",
      "pureTest/kotlin/dev/jordond/filmstrip/A.kt",
    ).forEach { path -> isTestSource(path) shouldBe true }
  }

  @Test
  fun `a main source set is not a test source`() {
    listOf(
      "appleMain/kotlin/dev/jordond/filmstrip/test/TestScratch.kt",
      "iosMain/kotlin/dev/jordond/filmstrip/A.kt",
      "commonMain/kotlin/dev/jordond/filmstrip/Test.kt",
    ).forEach { path -> isTestSource(path) shouldBe false }
  }

  @Test
  fun `the temporary directory is banned in a test source and nowhere else`() {
    val fqn = "platform.Foundation.NSTemporaryDirectory"

    isForbiddenAt(inTest = true, fqn, emptySet(), TEST_FORBIDDEN_IMPORTS, emptySet()) shouldBe true
    isForbiddenAt(inTest = false, fqn, emptySet(), TEST_FORBIDDEN_IMPORTS, emptySet()) shouldBe false
  }

  @Test
  fun `a layer's allowed imports do not lift the test ban`() {
    val fqn = "platform.Foundation.NSTemporaryDirectory"

    isForbiddenAt(inTest = true, fqn, emptySet(), TEST_FORBIDDEN_IMPORTS, setOf("platform.Foundation")) shouldBe true
  }

  @Test
  fun `a test source keeps its layer's own rules`() {
    isForbiddenAt(
      inTest = true,
      "androidx.compose.ui.Modifier",
      setOf("androidx.compose"),
      TEST_FORBIDDEN_IMPORTS,
      emptySet(),
    ) shouldBe
      true
    isForbiddenAt(inTest = true, "platform.Foundation.NSURL", emptySet(), TEST_FORBIDDEN_IMPORTS, emptySet()) shouldBe
      false
  }
}
