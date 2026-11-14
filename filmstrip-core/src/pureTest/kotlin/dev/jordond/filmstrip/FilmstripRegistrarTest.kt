package dev.jordond.filmstrip

import dev.jordond.filmstrip.diagnostics.BackendInfo
import dev.jordond.filmstrip.effect.EffectResolver
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.test.assertSame

class FilmstripRegistrarTest {
  private val added = mutableListOf<RegistrarId>()

  @AfterTest
  fun forget() {
    added.forEach { FilmstripRegistrars.remove(it) }
  }

  @Test
  fun `a discovered registrar is applied once at build`() {
    val runs = mutableListOf<String>()
    discover(Recording("test-a", runs))

    val backends = FilmstripBuilder().components().backends.names()

    backends shouldContainExactly listOf("test-a")
    runs shouldBe listOf("test-a")
  }

  @Test
  fun `a registrar installed twice runs once`() {
    val runs = mutableListOf<String>()
    val registrar = Recording("test-a", runs)

    FilmstripBuilder()
      .install(registrar)
      .install(registrar)
      .autoRegister(false)
      .components()

    runs shouldBe listOf("test-a")
  }

  @Test
  fun `a registrar the block installed is not applied again`() {
    val runs = mutableListOf<String>()
    val registrar = Recording("test-a", runs)
    discover(registrar)

    val backends =
      FilmstripBuilder()
        .install(registrar)
        .components()
        .backends
        .names()

    backends shouldContainExactly listOf("test-a")
    runs shouldBe listOf("test-a")
  }

  @Test
  fun `the block's registrations go ahead of discovered ones`() {
    val discovered = EffectResolver { _, _, _ -> null }
    val own = EffectResolver { _, _, _ -> null }
    discover(Recording("test-a", resolver = discovered))

    val components =
      FilmstripBuilder()
        .addEffectResolver(own)
        .addBackendInfo(BackendInfo(name = "own", artifact = "own"))
        .components()

    assertSame(own, components.effectResolvers[0])
    assertSame(discovered, components.effectResolvers[1])
    components.backends.names() shouldContainExactly listOf("test-a", "own")
  }

  @Test
  fun `turning discovery off leaves only what the block registered`() {
    discover(Recording("test-a"))

    FilmstripBuilder()
      .autoRegister(false)
      .components()
      .backends
      .names() shouldBe emptyList()
  }

  @Test
  fun `discovered registrars apply in id order whatever order they loaded in`() {
    val runs = mutableListOf<String>()
    discover(Recording("test-c", runs))
    discover(Recording("test-a", runs))
    discover(Recording("test-b", runs))

    FilmstripBuilder().components()

    runs shouldBe listOf("test-a", "test-b", "test-c")
  }

  @Test
  fun `a discovered registrar reads an option the block set`() {
    val key = OptionKey<String>("label")
    discover(Labelled(key))

    FilmstripBuilder()
      .option(key, "set")
      .components()
      .backends
      .names() shouldBe listOf("set")
    FilmstripBuilder().components().backends.names() shouldBe listOf("unset")
  }

  @Test
  fun `a dependency the block installed is not added again by a discovered registrar`() {
    val runs = mutableListOf<String>()
    val shared = Recording("test-shared", runs)
    discover(Dependent("test-a", shared))

    FilmstripBuilder().install(shared).components()

    runs shouldBe listOf("test-shared")
  }

  @Test
  fun `a registrar the block installed reads an option a discovered registrar set`() {
    val key = OptionKey<String>("label")
    val reader = Reading(key)
    discover(Setting(key, "discovered"))

    FilmstripBuilder().install(reader).components()

    reader.read() shouldBe "discovered"
  }

  @Test
  fun `an option a discovered registrar set stays on the builder it ran on`() {
    val key = OptionKey<String>("label")
    val setting = Setting(key, "discovered")
    val first = Reading(key)
    val second = Reading(key)
    discover(setting)
    FilmstripBuilder().install(first).components()
    FilmstripRegistrars.remove(setting.id)

    FilmstripBuilder().install(second).components()

    first.read() shouldBe "discovered"
    second.read() shouldBe null
  }

  @Test
  fun `a registrar id prints as its value`() {
    RegistrarId("filmstrip-test").toString() shouldBe "filmstrip-test"
  }

  private fun discover(registrar: FilmstripRegistrar) {
    FilmstripRegistrars.register(registrar)
    added += registrar.id
  }

  private fun List<BackendInfo>.names(): List<String> = map { it.name }

  private class Recording(
    private val name: String,
    private val runs: MutableList<String> = mutableListOf(),
    private val resolver: EffectResolver? = null,
  ) : FilmstripRegistrar {
    override val id: RegistrarId = RegistrarId(name)

    override fun register(builder: FilmstripBuilder) {
      runs += name
      builder.addBackendInfo(BackendInfo(name = name, artifact = name))
      resolver?.let { builder.addEffectResolver(it) }
    }
  }

  private class Labelled(
    private val key: OptionKey<String>,
  ) : FilmstripRegistrar {
    override val id: RegistrarId = RegistrarId("test-labelled")

    override fun register(builder: FilmstripBuilder) {
      val label = builder.optionOrNull(key) ?: "unset"
      builder.addBackendInfo(BackendInfo(name = label, artifact = id.value))
    }
  }

  private class Setting(
    private val key: OptionKey<String>,
    private val value: String,
  ) : FilmstripRegistrar {
    override val id: RegistrarId = RegistrarId("test-setting")

    override fun register(builder: FilmstripBuilder) {
      builder.option(key, value)
    }
  }

  private class Reading(
    private val key: OptionKey<String>,
  ) : FilmstripRegistrar {
    override val id: RegistrarId = RegistrarId("test-reading")
    private var builder: FilmstripBuilder? = null

    override fun register(builder: FilmstripBuilder) {
      this.builder = builder
    }

    fun read(): String? = builder?.optionOrNull(key)
  }

  private class Dependent(
    name: String,
    private val dependency: FilmstripRegistrar,
  ) : FilmstripRegistrar {
    override val id: RegistrarId = RegistrarId(name)

    override fun register(builder: FilmstripBuilder) {
      builder.install(dependency)
    }
  }
}
