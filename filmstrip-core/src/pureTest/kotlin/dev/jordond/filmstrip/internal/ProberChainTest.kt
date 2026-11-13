package dev.jordond.filmstrip.internal

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackId
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.EXIF_ORIENTATION_NORMAL
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaProberFactory
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.media.imageMediaInfoOf
import dev.jordond.filmstrip.media.trackCodecOf
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class ProberChainTest {
  private val source = MediaSource.of("/fixtures/clip.mp4")

  @Test
  fun `the last prober answers after every registered one declines`() =
    runTest {
      val asked = mutableListOf<String>()
      val components =
        ComponentRegistry
          .Builder()
          .addMediaProberFactory(MediaProberFactory { refusing("registered", asked) })
          .build()

      val result = chainedProber(components, last = refusing("last", asked)).probe(source)

      asked shouldBe listOf("registered", "last")
      result.shouldBeInstanceOf<ProbeResult.Failure>().error.message shouldBe "registered"
    }

  @Test
  fun `a prober registered and passed as the last one is asked once`() =
    runTest {
      val asked = mutableListOf<String>()
      val prober = refusing("shared", asked)
      val components = ComponentRegistry.Builder().addMediaProberFactory(MediaProberFactory { prober }).build()

      chainedProber(components, last = prober).probe(source)

      asked shouldBe listOf("shared")
    }

  @Test
  fun `a prober a registered decorator already asked is not asked again`() =
    runTest {
      val asked = mutableListOf<String>()
      val last = refusing("last", asked)
      val decorator =
        object : MediaProberDecorator {
          override val decorated = last

          override suspend fun probe(source: MediaSource): ProbeResult {
            asked += "decorator"
            return decorated.probe(source)
          }
        }
      val components = ComponentRegistry.Builder().addMediaProberFactory(MediaProberFactory { decorator }).build()

      val result = chainedProber(components, last = last).probe(source)

      asked shouldBe listOf("decorator", "last")
      result.shouldBeInstanceOf<ProbeResult.Failure>().error.message shouldBe "last"
    }

  @Test
  fun `a success that read no track is unreadable`() =
    runTest {
      val result = chainedProber(ComponentRegistry.Builder().build(), last = answering(TRACKLESS)).probe(source)

      val error =
        result
          .shouldBeInstanceOf<ProbeResult.Failure>()
          .error
          .shouldBeInstanceOf<FilmstripError.SourceUnreadable>()
      error.source shouldBe source.describe()
      error.message shouldBe NO_READABLE_TRACK
    }

  @Test
  fun `a prober that read no track does not end the chain`() =
    runTest {
      val components =
        ComponentRegistry.Builder().addMediaProberFactory(MediaProberFactory { answering(TRACKLESS) }).build()

      val result = chainedProber(components, last = answering(VIDEO)).probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe VIDEO
    }

  @Test
  fun `one audio track is enough to read`() =
    runTest {
      val result = chainedProber(ComponentRegistry.Builder().build(), last = answering(AUDIO_ONLY)).probe(source)

      result.shouldBeInstanceOf<ProbeResult.Success>().info shouldBe AUDIO_ONLY
    }

  @Test
  fun `every platform prober is the same one to the chain`() {
    platformProber() shouldBe platformProber()
  }

  private fun refusing(
    name: String,
    asked: MutableList<String>,
  ) = MediaProber {
    asked += name
    ProbeResult.Failure(FilmstripError.InvalidComposition(name))
  }

  private fun answering(info: MediaInfo) = MediaProber { ProbeResult.Success(info) }

  private companion object {
    val TRACKLESS = MediaInfo(duration = 2.seconds, video = null, audioTracks = emptyList(), isExportable = true)

    val VIDEO =
      imageMediaInfoOf(
        codedSize = Size(64, 40),
        exifOrientation = EXIF_ORIENTATION_NORMAL,
        format = "png",
        duration = 2.seconds,
      )

    val AUDIO_ONLY =
      MediaInfo(
        duration = 2.seconds,
        video = null,
        audioTracks =
          listOf(
            AudioTrackInfo(
              id = AudioTrackId(1),
              codec = trackCodecOf("mp4a.40.2"),
              sampleRate = 48_000,
              channelCount = 2,
              bitrate = null,
              language = null,
              isDefault = true,
            ),
          ),
        isExportable = true,
      )
  }
}
