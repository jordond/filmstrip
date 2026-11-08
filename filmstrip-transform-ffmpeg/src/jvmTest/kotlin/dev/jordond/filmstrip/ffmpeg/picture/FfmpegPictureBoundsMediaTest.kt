package dev.jordond.filmstrip.ffmpeg.picture

import dev.jordond.filmstrip.effects.overlay.DecodedPicture
import dev.jordond.filmstrip.effects.overlay.OverlayPictureDecode
import dev.jordond.filmstrip.effects.overlay.OverlayPictureRead
import dev.jordond.filmstrip.effects.overlay.StoredSamples
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.internal.picture.pictureRuns
import dev.jordond.filmstrip.ffmpeg.support.FfmpegExportFixture
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.picture.heif.ALPHA_UNREADABLE_REASON
import dev.jordond.filmstrip.picture.heif.GRID_TOO_LARGE_REASON
import dev.jordond.filmstrip.picture.heif.HeifGridRead
import dev.jordond.filmstrip.picture.heif.HeifOverlayRead
import dev.jordond.filmstrip.picture.heif.OVERLAY_INPUT_OUT_OF_RANGE_REASON
import dev.jordond.filmstrip.picture.heif.OVERLAY_NESTED_INPUT_REASON
import dev.jordond.filmstrip.picture.heif.OVERLAY_TOO_LARGE_REASON
import dev.jordond.filmstrip.picture.heif.heifCodedImages
import dev.jordond.filmstrip.picture.heif.heifPrimaryGrid
import dev.jordond.filmstrip.picture.heif.heifPrimaryOverlay
import dev.jordond.filmstrip.test.AlphaPlanes
import dev.jordond.filmstrip.test.ColorPictures
import dev.jordond.filmstrip.test.HeifAlphaPlane
import dev.jordond.filmstrip.test.HeifOverlays
import dev.jordond.filmstrip.test.HeifStills
import dev.jordond.filmstrip.test.HeifThumbnail
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.shouldNotBe
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File
import java.util.Collections
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test

/**
 * The bounds an AVIF or HEIC built from other images is held to before ffmpeg reads it, core's ceiling on the bytes a
 * picture holds, and a grid past the coded images ffmpeg opens in one file, which is read without opening it.
 */
class FfmpegPictureBoundsMediaTest {
  private val fixture = FfmpegExportFixture()
  private val directory = createTempDirectory("filmstrip-bounds").toFile()
  private val launches = File(directory, "launches")
  private val tile = ColorPictures.NCLX_BT709_LIMITED.thumbnail()

  @Test
  fun `a picture core refuses is refused with its reason before any child starts`() {
    val recording = recording()
    val large = HeifThumbnail(tile.itemType, tile.configuration, tile.codestream, Size(16384, 16384))
    val refused =
      mapOf(
        OVERLAY_TOO_LARGE_REASON to
          HeifOverlays.overlay("avif", tile, listOf(0 to 0), Size(8, 8), images = listOf(large)),
        OVERLAY_NESTED_INPUT_REASON to
          HeifOverlays.overlay("avif", tile, listOf(0 to 0, 8 to 0), Size(16, 8), nested = setOf(1)),
        OVERLAY_INPUT_OUT_OF_RANGE_REASON to
          HeifOverlays.overlay("avif", tile, listOf(0 to 0, Int.MAX_VALUE - 4 to 0), Size(8, 8), wide = true),
        GRID_TOO_LARGE_REASON to grid(large.size, columns = 2, rows = 2),
      )
    for ((reason, bytes) in refused) {
      withClue("decode, $reason") {
        recording.decode(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
          reason
      }
      withClue("measure, $reason") {
        recording.measure(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
          reason
      }
    }
    withClue("children started") { launches.exists() shouldBe false }
  }

  @Test
  fun `an iovl alpha core refuses or cannot read under a readable picture is refused before any child starts`() {
    val plane = AlphaPlanes.avif()
    val large = HeifAlphaPlane(plane.itemType, plane.configuration, plane.codestream, Size(16384, 16384))
    val fill = listOf(0x1234, 0x5678, 0x9ABC, 0xDEF0)
    val unreadable = HeifOverlays.overlay("avif", tile, listOf(0 to 0), Size(8, 8), alpha = plane, alphaFill = fill)
    val layout = fill.flatMap { listOf((it ushr 8).toByte(), it.toByte()) }
    unreadable[Collections.indexOfSubList(unreadable.toList(), layout) - 2] = 1
    val refused =
      mapOf(
        OVERLAY_TOO_LARGE_REASON to HeifOverlays.overlay("avif", tile, listOf(0 to 0), Size(8, 8), alpha = large),
        ALPHA_UNREADABLE_REASON to unreadable,
      )
    for ((reason, bytes) in refused) {
      withClue("the picture itself, $reason") {
        heifPrimaryOverlay(bytes).shouldBeInstanceOf<HeifOverlayRead.Read>()
      }
      withClue("decode, $reason") {
        recording().decode(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<OverlayPictureRead.Refused>().reason shouldBe
          reason
      }
    }
    withClue("children started") { launches.exists() shouldBe false }
  }

  @Test
  fun `a one tile subsampled grid decodes at the odd size it declares`() {
    if (!fixture.available()) return
    val odd = Size(6, 5)
    val bytes =
      HeifStills.grid(
        "avif",
        tile.itemType,
        tile.configuration,
        tile.codestream,
        tile.size,
        columns = 1,
        rows = 1,
        size = odd,
        nclx = ColorPictures.NCLX_BT709_LIMITED.nclx,
      )
    decoded(bytes).size shouldBe odd
    decoder.measure(ImageSource.ofBytes(bytes)).shouldBeInstanceOf<OverlayPictureRead.Read<Size>>().value shouldBe odd
  }

  @Test
  fun `a grid of fifteen hundred tiles reads its middle and both sides of a run boundary`() {
    if (!fixture.available()) return
    val edge = ColorPictures.NCLX_BT709_FULL_EDGE_CHROMA.thumbnail()
    // A second av1C that differs only past the fields a decoder reads, so the tiles after it start a run of their own.
    val second = tile.configuration.copyOf().also { it[it.size - 1] = SECOND_RUN_DELAY }
    // One instance each, since the generator writes a configuration for every distinct image.
    val firstTile = HeifThumbnail(tile.itemType, tile.configuration, tile.codestream, tile.size)
    val firstEdge = HeifThumbnail(tile.itemType, tile.configuration, edge.codestream, tile.size)
    val laterTile = HeifThumbnail(tile.itemType, second, tile.codestream, tile.size)
    val laterEdge = HeifThumbnail(tile.itemType, second, edge.codestream, tile.size)
    val boundary = BIG_COLUMNS * BIG_ROWS / 2
    val images =
      List(BIG_COLUMNS * BIG_ROWS) { at ->
        when {
          at == boundary - 1 -> firstEdge
          at == boundary -> laterEdge
          at < boundary -> firstTile
          else -> laterTile
        }
      }
    val bytes = grid(tile.size, BIG_COLUMNS, BIG_ROWS, images)
    val tiles = heifPrimaryGrid(bytes).shouldBeInstanceOf<HeifGridRead.Read>().grid.tiles
    withClue("two runs") {
      pictureRuns(heifCodedImages(bytes, tiles).shouldNotBeNull()).shouldNotBeNull().size shouldBe
        2
    }
    val drawn = decoded(bytes)
    drawn.size shouldBe Size(BIG_COLUMNS * 8, BIG_ROWS * 8)
    val small = decoded(grid(tile.size, columns = 3, rows = 1, listOf(firstTile, firstEdge, laterEdge)))
    val centre = { picture: DecodedPicture, at: Int, columns: Int ->
      texel(picture, at % columns * 8 + 4, at / columns * 8 + 4)
    }
    withClue("the two tiles read apart") { centre(small, 0, 3) shouldNotBe centre(small, 1, 3) }
    withClue("the middle tile") { centre(drawn, boundary + BIG_COLUMNS, BIG_COLUMNS) shouldBe centre(small, 0, 3) }
    withClue("the last tile of the first run") { centre(drawn, boundary - 1, BIG_COLUMNS) shouldBe centre(small, 1, 3) }
    withClue("the first tile of the second run") { centre(drawn, boundary, BIG_COLUMNS) shouldBe centre(small, 2, 3) }
  }

  @Test
  fun `every child decodes on one thread`() {
    val arguments = File(directory, "arguments")
    val binary = File(directory, "arguments-ffmpeg")
    binary.writeText("#!/bin/sh\nfor a in \"$@\"; do echo \"\$a\" >> '${arguments.absolutePath}'; done\nexit 1\n")
    check(binary.setExecutable(true)) { "could not make $binary executable" }
    val recording = FfmpegPictureDecoder(binary.absolutePath, scratch = directory)
    recording
      .decode(
        ImageSource.ofBytes(grid(tile.size, columns = 2, rows = 2)),
      ).shouldBeInstanceOf<OverlayPictureRead.Refused>()
    val passed = arguments.readLines()
    withClue("arguments $passed") {
      passed.subList(0, passed.indexOf("-i")).windowed(2) shouldContain
        listOf("-threads", "1")
    }
  }

  @AfterTest
  fun cleanUp() {
    directory.deleteRecursively()
  }

  // A decoder whose ffmpeg only records that it was launched.
  private fun recording(): FfmpegPictureDecoder {
    val binary = File(directory, "recording-ffmpeg")
    binary.writeText("#!/bin/sh\necho launched >> '${launches.absolutePath}'\nexit 1\n")
    check(binary.setExecutable(true)) { "could not make $binary executable" }
    return FfmpegPictureDecoder(binary.absolutePath, scratch = directory)
  }

  private fun grid(
    tileSize: Size,
    columns: Int,
    rows: Int,
    images: List<HeifThumbnail>? = null,
  ): ByteArray =
    HeifStills.grid(
      "avif",
      tile.itemType,
      tile.configuration,
      tile.codestream,
      tileSize,
      columns,
      rows,
      nclx = ColorPictures.NCLX_BT709_LIMITED.nclx,
      images = images,
    )

  private fun decoded(bytes: ByteArray): DecodedPicture {
    val read = decoder.decode(ImageSource.ofBytes(bytes))
    val why = (read as? OverlayPictureRead.Refused)?.reason ?: (read as? OverlayPictureDecode.Undrawn)?.reason
    return withClue("this ffmpeg decoded nothing: $why") { (read as? OverlayPictureRead.Read)?.value.shouldNotBeNull() }
  }

  private fun texel(
    decoded: DecodedPicture,
    x: Int,
    y: Int,
  ): Int = decoded.samples.shouldBeInstanceOf<StoredSamples.Argb>().argb[y * decoded.size.width + x]

  private companion object {
    val decoder = FfmpegPictureDecoder(hostFfmpeg)

    // Fifty columns of thirty rows, fifteen hundred tiles, past the thousand streams ffmpeg opens in one file.
    const val BIG_COLUMNS = 50
    const val BIG_ROWS = 30
    const val SECOND_RUN_DELAY: Byte = 0x10
  }
}
