package dev.jordond.filmstrip

import android.content.Context
import dev.jordond.filmstrip.diagnostics.BackendInfo
import io.kotest.matchers.shouldBe
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test
import kotlin.test.assertSame

/**
 * Building against an explicit context, for an app that runs without App Startup.
 */
class AllBackendsAndroidTest {
  @Test
  fun `building with a context retains its application context`() {
    val application = mockk<Context>()
    val activity = mockk<Context> { every { applicationContext } returns application }

    Filmstrip(activity) { allBackends() }

    assertSame(application, FilmstripContext.get())
  }

  @Test
  fun `all backends with a context registers media3 and the player and then the rest`() {
    val application = mockk<Context>()
    val context = mockk<Context> { every { applicationContext } returns application }

    val components =
      Filmstrip(context) {
        allBackends()
        addBackendInfo(BackendInfo(name = "custom", artifact = "dev.jordond.filmstrip.test:custom"))
      }.components

    components.backends.map { it.name } shouldBe listOf("media3", "custom")
    components.playerEngineFactories.size shouldBe 1
    components.exportEngineFactories.size shouldBe 1
  }
}
