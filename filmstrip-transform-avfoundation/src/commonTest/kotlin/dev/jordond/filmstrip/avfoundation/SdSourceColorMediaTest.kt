package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.ComponentRegistry
import dev.jordond.filmstrip.avfoundation.internal.AssetCache
import dev.jordond.filmstrip.avfoundation.internal.composition.AvComposition
import dev.jordond.filmstrip.avfoundation.internal.composition.SdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.sdSourceColor
import dev.jordond.filmstrip.avfoundation.internal.composition.toAvComposition
import dev.jordond.filmstrip.color.EBU_PRIMARIES
import dev.jordond.filmstrip.color.SMPTE_C_PRIMARIES
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.BuiltInEffectResolver
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.internal.copyFormatDescription
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.transform.internal.ResolveResult
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.AVFoundation.AVAssetTrack
import platform.AVFoundation.AVMediaTypeVideo
import platform.AVFoundation.AVMutableComposition
import platform.AVFoundation.AVURLAsset
import platform.AVFoundation.AVVideoColorPrimaries_ITU_R_709_2
import platform.AVFoundation.AVVideoColorPrimaries_SMPTE_C
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_601_4
import platform.AVFoundation.AVVideoYCbCrMatrix_ITU_R_709_2
import platform.AVFoundation.tracksWithMediaType
import platform.CoreFoundation.CFRelease
import platform.CoreMedia.CMFormatDescriptionGetExtension
import platform.CoreVideo.kCVImageBufferColorPrimariesKey
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import platform.Foundation.NSURL
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes

/**
 * Reads a clip's SD colour at plan time, off real files, from its tags or from what its first frame says.
 */
@OptIn(ExperimentalForeignApi::class)
class SdSourceColorMediaTest {
  @Test
  fun `a clip tagged smpte 170m reads as sd`() =
    withClip("smpte-c", { path ->
      writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty())
    }) { path ->
      colorOf(path, CLIP_SIDE) shouldBe SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
    }

  @Test
  fun `a clip tagged ebu reads as sd`() =
    withClip("ebu", { path -> writeEbuBars(path, FRAMES) }) { path ->
      colorOf(path, CLIP_SIDE) shouldBe SdSourceColor(EBU_PRIMARIES, BT709, BT601)
    }

  @Test
  fun `a clip tagged bt709 has no sd colour`() =
    withClip("bt709", { path ->
      writeTaggedBars(
        path,
        FRAMES,
        AVVideoColorPrimaries_ITU_R_709_2.orEmpty(),
        AVVideoYCbCrMatrix_ITU_R_709_2.orEmpty(),
      )
    }) { path ->
      colorOf(path, CLIP_SIDE).shouldBeNull()
    }

  @Test
  fun `an untagged clip in videotoolbox's sd range reads what its first frame says`() =
    withUntagged(640, 360) { path ->
      colorOf(path, 640) shouldBe SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
    }

  @Test
  fun `an untagged clip videotoolbox reads as bt709 has no sd colour`() =
    withUntagged(720, 480) { path ->
      colorOf(path, 720).shouldBeNull()
    }

  @Test
  fun `an untagged hd clip has no sd colour`() =
    withUntagged(1280, 720) { path ->
      colorOf(path, 1280).shouldBeNull()
    }

  @Test
  fun `an untagged clip past the bound decodes no frame`() =
    withUntagged(640, 360) { path ->
      colorOf(path, 1025).shouldBeNull()
    }

  @Test
  fun `a clip with no video has no sd colour`() {
    AVMutableComposition().sdSourceColor(CLIP_SIDE).shouldBeNull()
  }

  @Test
  fun `the span holds what the source reads and a still holds nothing`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("span")
      withContext(Dispatchers.Default) {
        writeTaggedBars(path, FRAMES, AVVideoColorPrimaries_SMPTE_C.orEmpty(), AVVideoYCbCrMatrix_ITU_R_601_4.orEmpty())
      }
      val still = ImageSource.of(photoFixture("filmstrip-sd-source-still", Size(640, 360), Triple(170, 120, 90)))
      val composition =
        compositionOf {
          clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
          image(still, CLIP)
        }
      val chain = graphOf(composition).chain.shouldNotBeNull()
      val expected = SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
      chain.spans[0].sdColor shouldBe expected
      chain.spans[1].sdColor.shouldBeNull()

      chain.updateParameters(resolved(composition))
      chain.spans[0].sdColor shouldBe expected
      remove(path)
    }

  @Test
  fun `a source with no sd colour reads once however many clips lay it`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("read-once-709")
      withContext(Dispatchers.Default) { writeUntaggedBars(path, FRAMES, width = 720, height = 480) }
      assertReadsOnce(path).shouldBeNull()
      remove(path)
    }

  @Test
  fun `a source with an sd colour reads once however many clips lay it`() =
    runTest(timeout = TIMEOUT) {
      val path = temporaryPath("read-once-sd")
      withContext(Dispatchers.Default) { writeUntaggedBars(path, FRAMES, width = 640, height = 360) }
      assertReadsOnce(path) shouldBe SdSourceColor(SMPTE_C_PRIMARIES, BT709, BT601)
      remove(path)
    }

  private suspend fun assertReadsOnce(path: String): SdSourceColor? {
    val composition =
      compositionOf {
        clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
        clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
        clip(MediaSource.of(path)) { trim(Duration.ZERO, CLIP) }
      }
    var reads = 0
    val assets =
      AssetCache(
        kernelLimit = { null },
        readSdColor = { asset, longestSide ->
          reads++
          asset.sdSourceColor(longestSide)
        },
      )
    val colors =
      resolved(composition)
        .tracks
        .single()
        .clips
        .map(assets::sdColorOf)
    colors.size shouldBe 3
    reads shouldBe 1
    return colors.distinct().single()
  }

  private fun colorOf(
    path: String,
    longestSide: Int,
  ): SdSourceColor? = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null).sdSourceColor(longestSide)

  private fun withClip(
    name: String,
    write: (String) -> Unit,
    check: (String) -> Unit,
  ) {
    val path = temporaryPath(name)
    write(path)
    try {
      check(path)
    } finally {
      remove(path)
    }
  }

  private fun withUntagged(
    width: Int,
    height: Int,
    check: (String) -> Unit,
  ) = withClip("untagged-$width", { writeUntaggedBars(it, FRAMES, width, height) }) { path ->
    tagsOf(path).shouldBeNull()
    check(path)
  }

  // What the writer left on the file, so a test naming a clip untagged holds one.
  private fun tagsOf(path: String): Any? {
    val asset = AVURLAsset(uRL = NSURL.fileURLWithPath(path), options = null)
    val track = asset.tracksWithMediaType(AVMediaTypeVideo).first() as AVAssetTrack
    val format = track.copyFormatDescription().shouldNotBeNull()
    try {
      return CMFormatDescriptionGetExtension(format, kCVImageBufferColorPrimariesKey)
    } finally {
      CFRelease(format)
    }
  }

  private suspend fun graphOf(composition: EditComposition): AvComposition =
    withContext(Dispatchers.Default) { resolved(composition).toAvComposition() }

  private suspend fun resolved(composition: EditComposition) =
    when (val result = avFoundationExportEngine(COMPONENTS).resolve(composition, SPEC)) {
      is ResolveResult.Refused -> error(result.error.message)
      is ResolveResult.Resolved -> result.composition
    }

  private fun temporaryPath(name: String): String = NSTemporaryDirectory() + "filmstrip-sd-source-$name.mp4"

  private fun remove(path: String) {
    NSFileManager.defaultManager.removeItemAtPath(path, error = null)
  }

  private companion object {
    const val FRAMES = 5
    const val CLIP_SIDE = 640
    const val BT709 = 1
    const val BT601 = 6
    val CLIP = 100.milliseconds
    val TIMEOUT = 2.minutes
    val SPEC = ExportSpec(heightPx = 360)
    val COMPONENTS = ComponentRegistry.Builder().addEffectResolver(BuiltInEffectResolver()).build()
  }
}
