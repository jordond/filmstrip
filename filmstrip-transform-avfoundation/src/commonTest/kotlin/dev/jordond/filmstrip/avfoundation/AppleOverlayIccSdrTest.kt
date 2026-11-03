package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.color.pictureSrgbCodes
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.AuthoredColors
import dev.jordond.filmstrip.test.HdrOverlayCells
import io.kotest.assertions.withClue
import io.kotest.matchers.booleans.shouldBeTrue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import platform.Foundation.NSTemporaryDirectory
import kotlin.math.abs
import kotlin.test.Test
import kotlin.time.Duration

/**
 * Color described overlay pictures drawn onto an SDR export and read back out of the written file.
 *
 * A described badge is held to [pictureSrgbCodes] of its texel, and the untagged badge to its stored codes. The bound
 * every badge is held to is checked against the colors a wrong description would draw, and ImageIO is held to writing
 * a Display P3 JPEG.
 */
@OptIn(ExperimentalForeignApi::class)
class AppleOverlayIccSdrTest : AppleOverlayIccCase() {
  @Test
  fun `a described picture on an sdr export lands at the shared srgb codes`() =
    runTest(timeout = TIMEOUT) {
      val portrait = fixture(PORTRAIT)
      val misses = mutableListOf<String>()

      PICTURES.chunked(CORNERS.size).forEachIndexed { index, pictures ->
        val badges = pictures.zip(CORNERS) { picture, corner -> picture.badgeAt(corner) }
        val output = NSTemporaryDirectory() + "filmstrip-apple-icc-$index.mp4"
        val composition =
          compositionOf {
            clip(MediaSource.of(portrait)) { trim(Duration.ZERO, CLIP) }
            fill(Fill.Solid(AuthoredColors.MID_GREY))
            effects {
              crop(AspectRatio.Landscape, Fit.Contain)
              badges.forEach { add(it) }
            }
          }
        val statuses = withContext(Dispatchers.Default) { filmstrip.export(composition, SDR, MediaSink.of(output)) }
        val finished = statuses.toList().last()
        pictures.forEach { it.removeFile() }
        if (finished is ExportStatus.Failure) error("the sdr export failed: ${finished.error.message}")
        val size =
          finished
            .shouldBeInstanceOf<ExportStatus.Success>()
            .info.video
            .shouldNotBeNull()
            .displaySize
        val drawn = frameOf(output, CLIP / 2)
        NSFileManager.defaultManager.removeItemAtPath(output, error = null)

        pictures.zip(badges).mapNotNullTo(misses) { (picture, badge) ->
          AppleStillSamples.missOrUnmeasured {
            val rect = badge.placedOn(size, picture.size).rectOn(size)
            val read =
              drawn.average((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f).inBufferCodes().map {
                it * FULL_SCALE
              }
            val colors = picture.srgbCodes()
            if (colors.none { color -> read.indices.all { abs(read[it] - color[it]) <= CHANNEL_DRIFT } }) {
              "the ${picture.name} badge read $read in the buffer's codes, where its color is one of $colors"
            } else {
              null
            }
          }
        }
      }

      withClue(misses.joinToString("\n")) { misses.isEmpty().shouldBeTrue() }
    }

  @Test
  fun `imageio writes a display p3 jpeg on every target`() {
    withClue("ImageIO wrote no Display P3 JPEG") { imageIoWrites("public.jpeg").shouldBeTrue() }
  }

  // Each badge is held to one exact answer per decode, so the bound around it is the tolerance alone.
  @Test
  fun `the badges are held to a bound a wrong color falls outside`() {
    withClue("badges held to ${FILL_CODE_TOLERANCE} codes are looser than ${HdrOverlayCells.MAX_BLEND_TOLERANCE}") {
      (FILL_CODE_TOLERANCE <= HdrOverlayCells.MAX_BLEND_TOLERANCE).shouldBeTrue()
    }

    // An ICC tagged picture has to move its color past the drift, or the sdr case cannot tell its profile was read.
    // sRGB and its lookup table form are sRGB by design, so they draw their stored codes. Display P3 red clips to the
    // sRGB red it is stored as, and is held below on PQ instead. The effects module's Apple tests hold every other
    // description to a tighter bound.
    ICC_TAGGED.filter { it.name != P3_RED_NAME }.forEach { picture ->
      val stored = AuthoredColors.channelsOf(checkNotNull(picture.texel))
      val drawn = picture.srgbCodes().single()
      if (picture.name in SRGB_BY_DESIGN) {
        withClue("the $picture picture draws $drawn from its stored $stored, off the sRGB its profile describes") {
          stored.indices.all { abs(stored[it] - drawn[it]) <= 1 }.shouldBeTrue()
        }
      } else {
        withClue("the $picture picture draws $drawn from its stored $stored, inside the drift") {
          stored.indices.any { abs(stored[it] - drawn[it]) > CHANNEL_DRIFT }.shouldBeTrue()
        }
      }
    }

    // Display P3 red is outside BT.709, so on PQ it lands away from the sRGB red its stored codes would draw.
    val p3Red = PICTURES.single { it.name == P3_RED_NAME }.hdrCodes(HdrTransfer.Pq).single()
    val srgbRed = HdrOverlayCells.Cell(RED, BAR, 1f).codes(HdrTransfer.Pq)
    withClue("Display P3 red asks for $p3Red on PQ, too close to sRGB red's ${srgbRed.toList()}") {
      p3Red.indices.any { abs(p3Red[it] - srgbRed[it]) > 2 * FILL_CODE_TOLERANCE }.shouldBeTrue()
    }
  }
}
