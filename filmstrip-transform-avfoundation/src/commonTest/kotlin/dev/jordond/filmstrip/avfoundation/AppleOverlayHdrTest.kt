package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.Filmstrip
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.OverlayAnimation
import dev.jordond.filmstrip.effects.overlay.OverlayEffect
import dev.jordond.filmstrip.effects.overlay.OverlayFrame
import dev.jordond.filmstrip.effects.overlay.TextOverlay
import dev.jordond.filmstrip.effects.overlay.fadeIn
import dev.jordond.filmstrip.effects.overlay.frameAt
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.HdrMode
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Anchor
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Corner
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.authoredHdrNits
import dev.jordond.filmstrip.media.signalFromNits
import dev.jordond.filmstrip.style.TextStyle
import dev.jordond.filmstrip.test.AuthoredColours
import dev.jordond.filmstrip.test.OverlayPixels
import dev.jordond.filmstrip.test.encodesHdr
import dev.jordond.filmstrip.transform.internal.tenBitCodesFromSignal
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSProcessInfo
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.minutes
import kotlin.time.Duration.Companion.seconds

/**
 * Composition overlays drawn over the letterbox fill of an export that keeps its grade, read back out of the written
 * file.
 *
 * A square crop of the 16:9 HDR fixtures leaves a bar above and below the picture that only a mid grey fill paints, so
 * the light under every overlay is [authoredHdrNits] of that grey. Each overlay is held to
 * [OverlayPixels.hdrSourceOver] of its colour over the bar, encoded through [signalFromNits]. An opaque overlay lands
 * where a fill of its colour does and a partly opaque one blends with the bar in linear light.
 *
 * Skipped when the fixtures are absent, as in [AppleHdrFillTest].
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayHdrTest {
  private val fixtures = NSProcessInfo.processInfo.environment[FIXTURES] as? String

  private val filmstrip = Filmstrip { avFoundationBackend() }

  @Test
  fun `an image overlay on a kept grade lands where the shared blend puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) return@runTest
      val misses = mutableListOf<String>()

      HdrTransfer.entries.forEach { transfer ->
        OPACITIES.forEach { opacity ->
          val badges =
            COLOURS.mapIndexed { index, colour ->
              ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(RASTER, colour)), CORNERS[index], opacity = opacity)
            }
          exported(transfer, "image", badges) { path, size ->
            val frame = assertNotNull(hdrFrameOf(path), "could not decode a frame of the $transfer export")
            badges.zip(COLOURS).forEach { (badge, colour) ->
              val rect = badge.placedOn(size, RASTER).rectOn(size)
              val x = (rect.left + rect.right) / 2f
              val y = (rect.top + rect.bottom) / 2f
              misses += missed(frame, transfer, colour, opacity, x, y, "image")
            }
          }
        }
      }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  // The plate and the glyphs share one colour, so every pixel of the block is that colour whatever the font lays out,
  // and a point one cap height in from the anchored corner is inside it.
  @Test
  fun `text on a kept grade lands where the shared blend puts it`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) return@runTest
      val misses = mutableListOf<String>()

      HdrTransfer.entries.forEach { transfer ->
        OPACITIES.forEach { opacity ->
          val held = if (opacity < 1f) OverlayAnimation { OverlayFrame(opacity = opacity) } else null
          val captions =
            COLOURS.mapIndexed { index, colour ->
              val style = TextStyle(fontSize = FONT_SIZE, color = colour, backgroundColor = colour)
              TextOverlay(CAPTION, style, ANCHORS[index], animation = held)
            }
          exported(transfer, "text", captions) { path, size ->
            val frame = assertNotNull(hdrFrameOf(path), "could not decode a frame of the $transfer export")
            captions.zip(COLOURS).forEach { (caption, colour) ->
              val anchor = caption.anchor
              val x = anchor.x + (1f - 2f * anchor.x) * FONT_SIZE * size.height / size.width
              val y = anchor.y + (1f - 2f * anchor.y) * FONT_SIZE
              misses += missed(frame, transfer, colour, opacity, x, y, "text")
            }
          }
        }
      }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  // Read inside the written file at a quarter and three quarters of the ramp. Both ends of a ramp agree whether the
  // opacity is added or multiplied in, so only the middle says which one ran, and the expectation comes from the
  // shared sampler rather than from a number copied out of it.
  @Test
  fun `a fade on a kept grade blends by the shared sampler in the middle of its ramp`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) return@runTest
      val misses = mutableListOf<String>()
      val transfer = HdrTransfer.Pq
      val badge = ImageOverlay(ImageSource.ofBytes(OverlayPixels.pngOf(RASTER, WHITE)), Corner.TopStart)
      val faded = ImageOverlay(badge.image, badge.corner, animation = fadeIn(RAMP))

      exported(transfer, "fade", listOf(faded)) { path, size ->
        val rect = faded.placedOn(size, RASTER).rectOn(size)
        RAMP_POINTS.forEach { time ->
          val opacity = assertNotNull(faded.frameAt(time, SPAN), "the badge was not drawn at $time").opacity
          val frame = assertNotNull(hdrFrameOf(path, time), "could not decode the frame at $time")
          val x = (rect.left + rect.right) / 2f
          val y = (rect.top + rect.bottom) / 2f
          misses += missed(frame, transfer, WHITE, opacity, x, y, "fade at $time")
        }
      }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  /**
   * Where [colour] drawn at [opacity] over the bar was read at ([x], [y]), when it is off the shared blend by more than
   * the fill's own code tolerance.
   */
  private fun missed(
    frame: HdrFrameProbe,
    transfer: HdrTransfer,
    colour: Int,
    opacity: Float,
    x: Float,
    y: Float,
    what: String,
  ): List<String> {
    val light = OverlayPixels.hdrSourceOver(authoredHdrNits(BAR), colour, opacity)
    val expected = tenBitCodesFromSignal(transfer.signalFromNits(light)).toList()
    val read = frame.codesAt(x, y)
    if (expected.indices.all { abs(read[it] - expected[it]) <= AppleHdrFillTest.CODE_TOLERANCE }) return emptyList()

    return listOf(
      "$transfer $what ${colour.toUInt().toString(HEX)} at $opacity carried codes $read and " +
        "${frame.nitsAt(x, y)} nits, where the shared blend asks for $expected and ${light.toList()} nits",
    )
  }

  /**
   * Exports a clip of the [transfer] fixture cropped square over a mid grey fill with [overlays] on the composition,
   * and hands [read] the written file and its size.
   */
  private suspend fun exported(
    transfer: HdrTransfer,
    name: String,
    overlays: List<OverlayEffect>,
    read: (String, Size) -> Unit,
  ) {
    val source = fixture(transfer) ?: return
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
        fill(Fill.Solid(BAR))
        effects {
          crop(AspectRatio.Square, Fit.Contain)
          overlays.forEach { add(it) }
        }
      }
    val spec = ExportSpec(targetHeight = 720, hdr = HdrMode.KeepHdr)
    val plan =
      when (val verdict = filmstrip.plan(composition, spec)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("the $transfer $name export was refused: ${verdict.reasons.map { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("export failed: ${finished.error.message}")
    val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path

    try {
      val written = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info.video
      assertEquals(transfer, written?.hdrTransfer, "a $transfer grade was written as ${written?.hdrTransfer}")
      read(path, assertNotNull(written).displaySize)
    } finally {
      NSFileManager.defaultManager.removeItemAtPath(path, error = null)
    }
  }

  private fun fixture(transfer: HdrTransfer): String? {
    val directory = fixtures ?: return null
    val clip =
      when (transfer) {
        HdrTransfer.Pq -> CLIP_NAME
        HdrTransfer.Hlg -> HLG_CLIP_NAME
      }
    val path = "$directory/$clip"
    return path.takeIf { NSFileManager.defaultManager.fileExistsAtPath(it) }
  }

  private companion object {
    val TIMEOUT = 5.minutes

    const val FIXTURES = "FILMSTRIP_FIXTURES"
    const val CLIP_NAME = "apple_export_hdr.mp4"
    const val HLG_CLIP_NAME = "apple_export_hdr_hlg.mp4"

    val CLIP = 1.seconds
    val SPAN = TimeRange.of(Duration.ZERO, CLIP)

    // Six and eighteen frames in at thirty frames a second, so both land on a frame rather than between two.
    val RAMP = 800.milliseconds
    val RAMP_POINTS = listOf(RAMP / 4, RAMP * 3 / 4)

    const val BAR = AuthoredColours.MID_GREY
    const val WHITE = 0xFFFFFFFF.toInt()
    val COLOURS = listOf(WHITE, AuthoredColours.MID_GREY, AuthoredColours.MID_GREEN)
    val OPACITIES = listOf(1f, 0.5f, 0.25f)

    // A badge at the default scale and margin sits inside the bar at each of these corners, one colour to each.
    val CORNERS = listOf(Corner.TopStart, Corner.TopEnd, Corner.BottomStart)
    val RASTER = Size(4, 2)

    // Small enough that a plated caption stands inside the bar and two fit side by side along the top.
    val ANCHORS = listOf(Anchor.TopStart, Anchor.TopEnd, Anchor.BottomStart)
    const val CAPTION = "filmstrip"
    const val FONT_SIZE = 0.06f

    const val HEX = 16
  }
}
