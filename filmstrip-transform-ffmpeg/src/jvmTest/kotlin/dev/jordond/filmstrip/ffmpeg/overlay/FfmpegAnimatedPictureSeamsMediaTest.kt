package dev.jordond.filmstrip.ffmpeg.overlay

import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.effects.overlay.ANIMATED_NO_END
import dev.jordond.filmstrip.effects.overlay.ANIMATED_NO_GRID
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.ffmpeg.internal.picture.FfmpegPictureDecoder
import dev.jordond.filmstrip.ffmpeg.support.HdrRender.CAPABILITIES
import dev.jordond.filmstrip.ffmpeg.support.hostFfmpeg
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.picture.PictureOrientation
import dev.jordond.filmstrip.picture.animated.AnimatedCopies
import dev.jordond.filmstrip.picture.animated.atlasGrid
import dev.jordond.filmstrip.picture.orientedSamples
import dev.jordond.filmstrip.test.AnimatedApngs
import dev.jordond.filmstrip.test.AnimatedGifs
import dev.jordond.filmstrip.test.AnimatedPictures
import dev.jordond.filmstrip.test.AnimatedPictures.Blend
import dev.jordond.filmstrip.test.AnimatedPictures.Op
import dev.jordond.filmstrip.test.OrientedAnimations
import io.kotest.assertions.withClue
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import io.kotest.matchers.types.shouldBeSameInstanceAs
import java.io.ByteArrayInputStream
import java.io.File
import javax.imageio.ImageIO
import kotlin.io.path.createTempDirectory
import kotlin.test.AfterTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.seconds

/**
 * The seams an animated picture shares with the other backends, read off the atlas ffmpeg opens: the turn its Exif tag
 * asks for, the sentences it is refused with, one atlas for a picture named twice and the heap an HDR grade needs.
 */
class FfmpegAnimatedPictureSeamsMediaTest {
  private val root: File = createTempDirectory("filmstrip-animated-seams").toFile()
  private val counted = mutableMapOf<File, List<Long>>()

  @AfterTest
  fun cleanUp() {
    root.deleteRecursively()
  }

  @Test
  fun `every frame of a tagged apng and webp is in the atlas turned the way its tag says`() {
    val canvas = OrientedAnimations.CANVAS
    val truth = AnimatedPictures.canvases(canvas, OrientedAnimations.OPS)
    for (exif in 1..8) {
      val orientation = PictureOrientation.ofExif(exif)
      val shown = orientation.orientedSize(canvas)
      val grid = checkNotNull(atlasGrid(truth.size, shown, null))
      for ((container, bytes) in listOf(
        "apng" to OrientedAnimations.apng(exif),
        "webp" to OrientedAnimations.webp(exif),
      )) {
        val atlas = atlasOf(resolve(overlayOf(bytes), attributes()))

        withClue("$container exif $exif") {
          Size(atlas.width, atlas.height) shouldBe grid.size
          for ((index, cell) in grid.cells.withIndex()) {
            val read = atlas.getRGB(cell.left, cell.top, shown.width, shown.height, null, 0, shown.width)
            val expected = orientedSamples(orientation, truth[index], canvas).toList()
            withClue("frame $index") { read.toList() shouldBe expected }
          }
        }
      }
    }
  }

  @Test
  fun `an animated picture with no frame rate is refused with the shared sentence`() {
    val refused =
      resolve(
        overlayOf(gif()),
        attributes(frameRate = null),
      ).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldBe ANIMATED_NO_GRID
  }

  @Test
  fun `an animated picture over a span with no end is refused with the shared sentence`() {
    val open = attributes(span = TimeRange.from(Duration.ZERO))

    val refused = resolve(overlayOf(gif()), open).shouldBeInstanceOf<EffectResolution.Unsupported>()

    refused.message shouldBe ANIMATED_NO_END
  }

  @Test
  fun `one picture on two overlays is one atlas for the lowering on each grade`() {
    val image = ImageSource.ofBytes(gif())
    val a = ImageOverlay(image, Corner.TopStart)
    val b = ImageOverlay(image, Corner.BottomEnd)
    for (transfer in listOf(null, HdrTransfer.Pq)) {
      val resolver = BuiltInEffectResolver(FfmpegPictureDecoder(hostFfmpeg))
      kotlinx.coroutines.runBlocking {
        resolver.prepare(
          compositionOf {
            clip(MediaSource.of("clip.mp4"))
            effects {
              add(a)
              add(b)
            }
          },
        )
      }
      val first = checkNotNull(resolver.resolve(a, CAPABILITIES, attributes(transfer = transfer)))
      val second = checkNotNull(resolver.resolve(b, CAPABILITIES, attributes(transfer = transfer)))

      withClue("grade $transfer") { opened(first) shouldBeSameInstanceAs opened(second) }
    }
  }

  // Each case is resolved in a JVM whose heap is only what the shared peak count says the lowering holds, with the
  // young generation held small so the heap is not split off from the arrays. The pictures are noisy, so no PNG
  // shrinks, and a palette GIF, whose PNG does, only has to fit. The sixteen-bit tag is sRGB, so that atlas is written
  // as it is.
  @Test
  fun `an eight bit palette picture resolves inside the heap its count names on both grades`() {
    val gif = written("gif", AnimatedGifs.noisy(AtlasMemoryProbe.SIZE, AtlasMemoryProbe.FRAMES).bytes)
    assertResolvesWithinItsCount("sdr gif", gif, "sdr", AnimatedCopies.Ffmpeg)
    assertResolvesWithinItsCount("pq gif", gif, "pq", AnimatedCopies.FfmpegHdr)
  }

  @Test
  fun `an eight bit picture that does not compress resolves inside the heap its count names on every route`() {
    val untagged = noisyApng("apng", EIGHT_BIT_FRAMES, sixteenBit = false)
    val tagged = noisyApng("tagged apng", EIGHT_BIT_FRAMES, sixteenBit = false, cicp = byteArrayOf(12, 13, 0, 1))
    assertResolvesWithinItsCount("sdr apng", untagged, "sdr", AnimatedCopies.Ffmpeg)
    assertResolvesWithinItsCount("sdr tagged apng", tagged, "sdr", AnimatedCopies.FfmpegTagged)
    assertResolvesWithinItsCount("pq apng", untagged, "pq", AnimatedCopies.FfmpegHdr)
    assertResolvesWithinItsCount("pq tagged apng", tagged, "pq", AnimatedCopies.FfmpegHdr)
  }

  @Test
  fun `a sixteen bit picture resolves inside the heap its count names on every route`() {
    val untagged = noisyApng("deep apng", DEEP_FRAMES, sixteenBit = true)
    val tagged = noisyApng("deep tagged apng", DEEP_FRAMES, sixteenBit = true, cicp = byteArrayOf(1, 13, 0, 1))
    assertResolvesWithinItsCount("sdr deep apng", untagged, "sdr", AnimatedCopies.Ffmpeg)
    assertResolvesWithinItsCount("sdr deep tagged apng", tagged, "sdr", AnimatedCopies.FfmpegTagged)
    assertResolvesWithinItsCount("pq deep apng", untagged, "pq", AnimatedCopies.FfmpegHdr)
    assertResolvesWithinItsCount("pq deep tagged apng", tagged, "pq", AnimatedCopies.FfmpegHdr)
  }

  // A picture of a hundred deep frames is about 100 MB, so it is written to disk a chunk at a time and counted in a JVM
  // of its own, which leaves this one holding no more than a frame of it.
  private fun noisyApng(
    name: String,
    frames: Int,
    sixteenBit: Boolean,
    cicp: ByteArray? = null,
  ): File {
    val size = AtlasMemoryProbe.SIZE
    val ops =
      List(frames) { Op(0, 0, AnimatedPictures.Tiles.noisy(size.width, size.height, it), blend = Blend.Source) }
    val file = File(root, "$name.bin")
    file.outputStream().buffered().use { out ->
      AnimatedApngs.write(size, ops, sixteenBit = sixteenBit, defaultIsFrame = true, cicp = cicp) { out.write(it) }
    }
    return file
  }

  private fun written(
    name: String,
    bytes: ByteArray,
  ): File = File(root, "$name.bin").apply { writeBytes(bytes) }

  private fun countedPeak(
    file: File,
    held: AnimatedCopies,
  ): Long {
    val counts =
      counted.getOrPut(file) {
        val (exit, said) = probe(listOf(AtlasMemoryProbe.COUNT, file.path))
        val counts = said.lines().mapNotNull { it.trim().toLongOrNull() }
        withClue("counting ${file.name}: $said") {
          exit shouldBe 0
          counts.size shouldBe AtlasMemoryProbe.COUNTED.size
        }
        counts
      }
    return counts[AtlasMemoryProbe.COUNTED.indexOf(held)]
  }

  private fun probe(
    args: List<String>,
    vararg options: String,
  ): Pair<Int, String> {
    val java = File(System.getProperty("java.home"), "bin/java").path
    val command =
      listOf(java) + options + listOf("-cp", System.getProperty("java.class.path"), AtlasMemoryProbe::class.java.name) +
        args
    val child = ProcessBuilder(command).redirectErrorStream(true).start()
    val said = child.inputStream.readAllBytes().decodeToString()
    return child.waitFor() to said
  }

  private fun assertResolvesWithinItsCount(
    name: String,
    file: File,
    grade: String,
    held: AnimatedCopies,
  ) {
    val heapMegabytes = countedPeak(file, held) / MEGABYTE + HEAP_SLACK_MEGABYTES
    val (exit, said) = probe(listOf(file.path, grade), "-Xmx${heapMegabytes}m", "-XX:+UseSerialGC", "-Xmn8m")

    withClue("$name in a heap of ${heapMegabytes}m, the counted peak and ${HEAP_SLACK_MEGABYTES}m: $said") {
      exit shouldBe 0
    }
  }

  private fun gif(): ByteArray = AnimatedGifs.bytes(AnimatedPictures.CANVAS, AnimatedPictures.SHARED, loops = 0)

  private fun overlayOf(bytes: ByteArray): ImageOverlay = ImageOverlay(ImageSource.ofBytes(bytes), Corner.TopStart)

  private fun resolve(
    spec: ImageOverlay,
    attributes: Attributes,
  ): EffectResolution =
    checkNotNull(BuiltInEffectResolver(FfmpegPictureDecoder(hostFfmpeg)).resolve(spec, CAPABILITIES, attributes))

  // The atlas file the graph opens, which is the largest picture it is handed.
  private fun opened(resolution: EffectResolution): ByteArray =
    resolution
      .shouldBeInstanceOf<EffectResolution.Resolved>()
      .effect.fragment.auxInputs
      .mapNotNull { (it.image as? ImageSource.Bytes)?.bytes }
      .maxBy { it.size }

  private fun atlasOf(resolution: EffectResolution) = ImageIO.read(ByteArrayInputStream(opened(resolution)))

  private fun attributes(
    frameRate: Float? = 30f,
    span: TimeRange = TimeRange.of(Duration.ZERO, 3.seconds),
    transfer: HdrTransfer? = null,
  ): Attributes =
    Attributes(
      inputSize = FRAME,
      outputSize = FRAME,
      layoutSize = FRAME,
      colorSpace = ColorSpace.Bt709,
      hdrTransfer = transfer,
      frameRate = frameRate,
      span = span,
    )

  private companion object {
    val FRAME = Size(640, 360)
    const val MEGABYTE = 1L shl 20
    const val DEEP_FRAMES = 100
    const val EIGHT_BIT_FRAMES = 100

    // The young generation of 8 m, which holds no array of this size, and the 3 to 4 m the JVM holds beside them. With
    // 100 frames of 480 by 270, a count that is 3 bytes a texel short is 37 m short, which this slack does not hide.
    const val HEAP_SLACK_MEGABYTES = 20L
  }
}
