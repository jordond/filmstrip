package dev.jordond.filmstrip.media

import dev.jordond.filmstrip.Cancellable
import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.export.ExportError
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.thumbnail.ThumbnailCallback
import dev.jordond.filmstrip.thumbnail.ThumbnailRequest
import dev.jordond.filmstrip.thumbnail.ThumbnailResult
import dev.jordond.filmstrip.thumbnail.ThumbnailSource
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.test.runTest
import java.awt.image.BufferedImage
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.Test
import kotlin.test.assertIs
import kotlin.test.assertNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The one-call form, over a thumbnail source that hands back a frame of a known colour.
 *
 * `still` composes `frame`, the encoder and the write, so what these pin is that the height reaches
 * the source, the frame is released whatever happened, and a temporary sink comes back resolved.
 */
class StillFacadeTest {
  @Test
  fun `a still is rendered, encoded and written in one call`() =
    runTest {
      val source = SolidThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }
      val target = File(scratch(), "frame.png")

      val result =
        filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Path(target.path), StillSpec(format = StillFormat.Png))

      val success = assertIs<StillResult.Success>(result)
      success.output shouldBe MediaSink.Path(target.path)
      success.format shouldBe StillFormat.Png
      success.size shouldBe Size(SolidThumbnailSource.WIDTH, SolidThumbnailSource.HEIGHT)
      decode(target.readBytes()).getRGB(0, 0) shouldBe SolidThumbnailSource.COLOUR
    }

  @Test
  fun `the spec's height is what the frame is asked for`() =
    runTest {
      val source = SolidThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Temporary, StillSpec(heightPx = SCALED_HEIGHT))

      source.askedFor shouldBe SCALED_HEIGHT
    }

  @Test
  fun `a source that ignores the height still produces a still of that height`() =
    runTest {
      val source = SolidThumbnailSource(honoursHeight = false)
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }
      val wanted = stillSizeOf(Size(SolidThumbnailSource.WIDTH, SolidThumbnailSource.HEIGHT), SCALED_HEIGHT)

      val result =
        filmstrip.still(
          COMPOSITION,
          Duration.ZERO,
          MediaSink.Temporary,
          StillSpec(format = StillFormat.Png, heightPx = SCALED_HEIGHT),
        )

      val success = assertIs<StillResult.Success>(result)
      success.size shouldBe wanted
      File((success.output as MediaSink.Path).path).delete()
    }

  @Test
  fun `a temporary sink comes back as the path it resolved to`() =
    runTest {
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> SolidThumbnailSource() } }

      val result = filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Temporary, StillSpec())

      val path = assertIs<MediaSink.Path>(assertIs<StillResult.Success>(result).output)
      val file = File(path.path)
      try {
        assertTrue(file.exists(), "nothing was written to ${path.path}")
      } finally {
        file.delete()
      }
    }

  @Test
  fun `the rendered frame is closed whether or not the encode worked`() =
    runTest {
      val source = SolidThumbnailSource()
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> source } }

      filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Temporary, StillSpec(format = StillFormat.Webp))

      assertNull(source.rendered?.asBufferedImage(), "still() left the frame it rendered open")
    }

  @Test
  fun `a format this target cannot write fails before anything is written`() =
    runTest {
      val filmstrip = Filmstrip { addThumbnailSourceFactory { _, _ -> SolidThumbnailSource() } }
      val target = File(scratch(), "frame.webp")

      val result =
        filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Path(target.path), StillSpec(format = StillFormat.Webp))

      assertIs<ExportError.UnsupportedStillFormat>(assertIs<StillResult.Failure>(result).error)
      assertTrue(!target.exists(), "a refused encode still wrote ${target.path}")
    }

  @Test
  fun `no thumbnail source is a failure carrying the frame's own reason`() =
    runTest {
      val filmstrip = Filmstrip()

      val result = filmstrip.still(COMPOSITION, Duration.ZERO, MediaSink.Temporary, StillSpec())

      assertIs<ExportError.BackendMissing>(assertIs<StillResult.Failure>(result).error)
    }

  private fun scratch(): File = createTempDirectory("filmstrip-still").toFile().apply { deleteOnExit() }

  private fun decode(bytes: ByteArray): BufferedImage =
    checkNotNull(ImageIO.read(ByteArrayInputStream(bytes))) { "the encoded still did not decode back" }

  private companion object {
    const val SCALED_HEIGHT = 60

    val COMPOSITION: EditComposition =
      EditComposition(
        tracks = listOf(Track(listOf(Clip(MediaSource.of("still.mp4"), TimeRange.of(Duration.ZERO, 4.seconds))))),
      )
  }
}

/**
 * A source that answers with one flat colour, recording the height it was asked for and the frame
 * it handed over.
 *
 * [honoursHeight] off is the backend that answers at its own resolution whatever the request said,
 * which is what makes the encoder rather than the source responsible for the still's size.
 */
internal class SolidThumbnailSource(
  private val honoursHeight: Boolean = true,
) : ThumbnailSource {
  var askedFor: Int = -1
    private set

  var rendered: PlatformImage? = null
    private set

  override fun requestThumbnail(
    request: ThumbnailRequest,
    callback: ThumbnailCallback,
  ): Cancellable {
    askedFor = request.heightPx

    val height = if (honoursHeight && request.heightPx > 0) request.heightPx else HEIGHT
    val width = if (height == HEIGHT) WIDTH else WIDTH * height / HEIGHT
    val image = BufferedImage(width, height, BufferedImage.TYPE_INT_ARGB)
    for (y in 0 until height) {
      for (x in 0 until width) {
        image.setRGB(x, y, COLOUR)
      }
    }

    val frame = PlatformImage(image)
    rendered = frame
    callback.onThumbnail(ThumbnailResult.Success(frame, request.position))
    return Cancellable {}
  }

  companion object {
    const val WIDTH = 320
    const val HEIGHT = 180
    const val COLOUR = 0xFF20A080.toInt()
  }
}
