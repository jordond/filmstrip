package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.FilmstripComponents
import dev.jordond.filmstrip.ffmpeg.internal.FfmpegExportEngine
import io.kotest.matchers.collections.shouldHaveSize
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlin.test.Test

/**
 * Which config the ffmpeg backend runs when the block sets one through `ffmpeg { }` or `ffmpegBackend(config)`.
 */
class FfmpegConfigOptionTest {
  @Test
  fun `a discovered backend runs the config the block set`() {
    val components = Filmstrip { ffmpeg { executablePath = PATH } }.components

    components.configs() shouldBe listOf(FfmpegConfig(executablePath = PATH))
  }

  @Test
  fun `a discovered backend runs the default config when the block sets none`() {
    Filmstrip().components.configs() shouldBe listOf(FfmpegConfig())
  }

  @Test
  fun `a config set after an explicit registration still applies`() {
    val components =
      Filmstrip {
        ffmpegBackend()
        ffmpeg { executablePath = PATH }
      }.components

    components.configs() shouldBe listOf(FfmpegConfig(executablePath = PATH))
  }

  @Test
  fun `the last config the block sets wins and registers one backend`() {
    val other = FfmpegConfig(executablePath = "/elsewhere/ffmpeg", threads = 2)

    val components =
      Filmstrip {
        ffmpeg { executablePath = PATH }
        ffmpegBackend(other)
      }.components

    components.exportEngineFactories shouldHaveSize 1
    components.configs() shouldBe listOf(other)
  }

  private fun FilmstripComponents.configs(): List<FfmpegConfig> =
    exportEngineFactories.map {
      it
        .create(this)
        .shouldBeInstanceOf<FfmpegExportEngine>()
        .runtime.config
    }

  private companion object {
    const val PATH = "/opt/ffmpeg/bin/ffmpeg"
  }
}
