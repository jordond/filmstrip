package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.geometry.Size
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

/**
 * The values the end-to-end export suites share, from the fixture colors and audio tones to the frames and rates a
 * reading is taken at. A suite imports the ones it reads.
 */
internal object ExportConstants {
  val TIMEOUT = kotlin.time.Duration.parse("2m")

  // The indexed clips' colors. Blue names the clip, and red and green step by INDEX_STEP from INDEX_FLOOR, far
  // enough apart that a re-encode moving a flat color by a few values still reads the same index.
  val CLIP_BLUES = listOf(60, 140, 220)
  val FIRST_BLUE = CLIP_BLUES[0]
  val SECOND_BLUE = CLIP_BLUES[1]
  val THIRD_BLUE = CLIP_BLUES[2]
  const val INDEX_FLOOR = 20
  const val INDEX_STEP = 20
  val INDEXED_SIZE = Size(320, 180)

  // A quarter of each side, centred, which crop does when it is given no offset.
  const val MIDDLE_CROP = "crop=w=iw/4:h=ih/4"

  const val MICROS_PER_SECOND = 1_000_000.0

  // Long enough for a loaded runner to open the encoder, short of the runTest timeout.
  val ENCODE_DEADLINE = kotlin.time.Duration.parse("30s")
  const val RED = 0xFFFF0000.toInt()
  const val HALF = 0.5f
  const val BRIGHTER = 1.5f

  // ffmpeg's own measured noise on a ten-bit read of an HDR export, per channel. The graph lands every
  // fill, kept grade, opaque overlay and blended overlay on its exact code, and libx265 and
  // hevc_videotoolbox then move a flat block by up to this much. A kept grade is read plane by plane
  // in Y'CbCr codes and drifts by at most 1. Apple and media3 hold their HDR fills to the same 2.
  const val CODE_TOLERANCE = 2

  // The rate the readback decodes to, which is also the rate every fixture carries.
  const val AUDIO_SAMPLE_RATE = 48_000

  // Twenty milliseconds, more than eight cycles of the fixture's own 440 Hz tone. Long enough for
  // the root mean square to settle and short enough that the curve barely moves across it.
  const val ENVELOPE_WINDOW_SAMPLES = AUDIO_SAMPLE_RATE / 50

  // The bed's tone and the one every other fixture carries. Two frequencies rather than one is
  // what lets a reading of the mix say which source it belongs to.
  const val BED_HZ = 880
  const val PRIMARY_HZ = 440

  // Half, so a bed that reached the file untouched reads twice what the plan folded.
  const val BED_GAIN = 0.5f

  // Late enough to leave a stretch of the timeline with no bed on it, and early enough that the
  // three second bed is laid down again before the middle of the twelve second primary.
  val BED_START = 1_000.milliseconds

  // Before the bed starts, where the mix carries the primary alone.
  val QUIET_READING = 500.milliseconds

  // Where a fixture's own tone is read, clear of both its ends.
  val FIXTURE_READING = 1_000.milliseconds

  // A hundred milliseconds, eighty-eight cycles of the bed's tone. Long enough for the two
  // frequencies to separate cleanly and short enough to sit inside one loop pass.
  const val TONE_WINDOW_SAMPLES = AUDIO_SAMPLE_RATE / 10

  const val RGB_CHANNELS = 3

  // Just over one frame of the fixtures' thirty per second. A read lands on the first frame at or
  // after where it opens, so this reaches the last frame of a run rather than running off the end.
  val FRAME = 40.milliseconds

  // Half the smallest gap between the watermark's color and any bar of the fixture it is drawn
  // over, so a covered probe and an uncovered one cannot be confused for each other.
  const val DRAWN_DIFFERENCE = 50.0

  // The whole of a fixture clip, so a quarter and three quarters of the ramp are half a second
  // and a second and a half into it.
  val RAMP = 2.seconds

  // Below this the two references are too close together for the blend to be solved at all, which
  // is a probe point chosen badly rather than a lowering that went wrong.
  const val BLEND_RANGE_FLOOR = 1_000.0
  val INSIDE_WINDOW = 3.seconds
  val OUTSIDE_WINDOW = listOf(2_200.milliseconds, 3_800.milliseconds)

  // How far ahead of a frame's own time a read opens, so that it lands on that frame rather than
  // the one after it. Shorter than a frame period at either rate these tests export at.
  val FRAME_LEAD = 10.milliseconds

  // The rates the frame grid is measured at: the fixtures' own thirty, and twenty five, whose 40 ms slot puts a
  // half slot on a whole millisecond.
  const val SEAM_RATE = 30
  const val GRID_RATE = 25
}
