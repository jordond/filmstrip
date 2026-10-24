package dev.jordond.filmstrip.avfoundation

import dev.jordond.filmstrip.InternalFilmstripApi
import dev.jordond.filmstrip.edit.compositionOf
import dev.jordond.filmstrip.effects.geometry.crop
import dev.jordond.filmstrip.effects.overlay.ImageOverlay
import dev.jordond.filmstrip.effects.overlay.placedOn
import dev.jordond.filmstrip.effects.overlay.rectOn
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.pictureLight
import dev.jordond.filmstrip.test.AppleStillSamples
import dev.jordond.filmstrip.test.HdrOverlayCells
import dev.jordond.filmstrip.test.encodesHdr
import kotlinx.cinterop.ExperimentalForeignApi
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlinx.coroutines.withContext
import platform.Foundation.NSFileManager
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertIs
import kotlin.test.assertNotNull
import kotlin.test.assertTrue
import kotlin.time.Duration

/**
 * Colour described overlay pictures drawn onto a kept HDR grade and read back out of the written file.
 *
 * A described badge is held to the codes [pictureLight] of its texel blends to over the fill, and the untagged badge to
 * [HdrOverlayCells.Cell.codes]. The case prints why and returns with nothing asserted where the platform advertises no
 * HDR encoder. The light a described picture enters an HDR frame at is asserted with no encoder in the effects
 * module's Apple tests.
 */
@OptIn(ExperimentalForeignApi::class, InternalFilmstripApi::class)
class AppleOverlayIccHdrTest : AppleOverlayIccCase() {
  @Test
  fun `a described picture on a kept grade lands at its description's light`() =
    runTest(timeout = TIMEOUT) {
      if (!withContext(Dispatchers.Default) { filmstrip.encodesHdr() }) {
        println("skipping the hdr case, this platform advertises no HDR encoder")
        return@runTest
      }
      val misses = mutableListOf<String>()

      HdrTransfer.entries.forEach { transfer ->
        val source =
          fixture(clipOf(transfer)) ?: run {
            println("skipping the $transfer case, $FIXTURES is unset")
            return@forEach
          }
        PICTURES.chunked(CORNERS.size).forEach { pictures ->
          val badges = pictures.zip(CORNERS) { picture, corner -> picture.badgeAt(corner) }
          val (path, size) = exportedHdr(source, transfer, badges)
          pictures.forEach { it.removeFile() }
          try {
            val frame = assertNotNull(hdrFrameOf(path), "could not decode a frame of the $transfer export")
            pictures.zip(badges).forEach { (picture, badge) ->
              val rect = badge.placedOn(size, picture.size).rectOn(size)
              val read = frame.codesAt((rect.left + rect.right) / 2f, (rect.top + rect.bottom) / 2f)
              AppleStillSamples
                .missOrUnmeasured {
                  val codes = picture.hdrCodes(transfer)
                  if (codes.none { it.within(read, AppleHdrFillTest.CODE_TOLERANCE) }) {
                    "the $transfer ${picture.name} badge carried codes $read, where its light asks for one of $codes"
                  } else {
                    null
                  }
                }?.let(misses::add)
            }
          } finally {
            NSFileManager.defaultManager.removeItemAtPath(path, error = null)
          }
        }
      }

      assertTrue(misses.isEmpty(), misses.joinToString("\n"))
    }

  /**
   * Exports a clip of [source] cropped square over a fill of [BAR] with [badges] on the composition, keeping its
   * [transfer] grade, and returns the written file and its size.
   */
  private suspend fun exportedHdr(
    source: String,
    transfer: HdrTransfer,
    badges: List<ImageOverlay>,
  ): Pair<String, Size> {
    val composition =
      compositionOf {
        clip(MediaSource.of(source)) { trim(Duration.ZERO, CLIP) }
        fill(Fill.Solid(BAR))
        effects {
          crop(AspectRatio.Square, Fit.Contain)
          badges.forEach { add(it) }
        }
      }
    val plan =
      when (val verdict = filmstrip.plan(composition, HDR)) {
        is Verdict.Capable -> verdict.plan
        is Verdict.Degraded -> verdict.plan
        is Verdict.Incapable -> error("the $transfer export was refused: ${verdict.reasons.map { it.message }}")
      }

    val finished = withContext(Dispatchers.Default) { filmstrip.export(plan, MediaSink.Temporary).toList() }.last()
    if (finished is ExportStatus.Failure) error("the $transfer export failed: ${finished.error.message}")
    val path = assertIs<MediaSink.Path>(assertIs<ExportStatus.Success>(finished).output).path
    val written = assertIs<ProbeResult.Success>(filmstrip.probe(MediaSource.of(path))).info.video
    assertEquals(transfer, written?.hdrTransfer, "a $transfer grade was written as ${written?.hdrTransfer}")
    return path to assertNotNull(written).displaySize
  }
}
