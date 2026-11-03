package dev.jordond.filmstrip

import android.content.Context
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.types.shouldBeSameInstanceAs
import io.mockk.every
import io.mockk.mockk
import kotlin.test.Test

// A host test has only android.jar's stubs, which throw on every call, so a Context here can only be a mock.
class FilmstripContextTest {
  // An activity handed in has to be traded for the application, or installing it would hold the activity forever.
  @Test
  fun `installing keeps the application context rather than the one handed in`() {
    val application = applicationContext()

    FilmstripContext.install(contextOf(application))

    FilmstripContext.get() shouldBeSameInstanceAs application
  }

  @Test
  fun `installing the application context itself keeps that same context`() {
    val application = applicationContext()

    FilmstripContext.install(application)

    FilmstripContext.get() shouldBeSameInstanceAs application
  }

  @Test
  fun `a second install replaces the first`() {
    val first = applicationContext()
    val second = applicationContext()

    FilmstripContext.install(contextOf(first))
    FilmstripContext.get() shouldBeSameInstanceAs first

    FilmstripContext.install(contextOf(second))
    FilmstripContext.get() shouldBeSameInstanceAs second
  }

  @Test
  fun `the startup initializer installs the context it is created with and waits on nothing`() {
    val application = applicationContext()
    val initializer = FilmstripInitializer()

    initializer.create(contextOf(application))

    FilmstripContext.get() shouldBeSameInstanceAs application
    initializer.dependencies().shouldBeEmpty()
  }

  // The path an app that turned App Startup off takes instead of the initializer.
  @Test
  fun `a builder made against a context installs its application context`() {
    val application = applicationContext()

    FilmstripBuilder(contextOf(application))

    FilmstripContext.get() shouldBeSameInstanceAs application
  }

  private fun applicationContext(): Context = mockk<Context>().also { every { it.applicationContext } returns it }

  private fun contextOf(application: Context): Context = mockk { every { applicationContext } returns application }
}
