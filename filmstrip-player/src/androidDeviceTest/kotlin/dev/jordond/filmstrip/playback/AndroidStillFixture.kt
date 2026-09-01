package dev.jordond.filmstrip.playback

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import dev.jordond.filmstrip.ExperimentalFilmstripApi
import dev.jordond.filmstrip.edit.Clip
import dev.jordond.filmstrip.edit.EditComposition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.test.TestFrame
import java.io.File
import kotlin.math.abs
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds

/**
 * The fixture clip followed by a flat photo held for [PHOTO_LENGTH].
 *
 * A still takes its slot on the timeline from a clip of its own rather than from a track, so a
 * frame read back inside that slot is the one worth pinning to the export.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun androidPhotoComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(MediaSource.Image(ImageSource.of(androidPhotoFile().path), PHOTO_LENGTH)),
          ),
        ),
      ),
    effects = effects,
  )

/**
 * A photo between two runs of video, which is the layout that tells a reader picking one path per
 * span apart from one picking a path per composition.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun androidSandwichComposition(): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(MediaSource.Image(ImageSource.of(androidPhotoFile().path), PHOTO_LENGTH)),
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
          ),
        ),
      ),
  )

/**
 * The fixture clip followed by a patterned photo that a pan travels across.
 *
 * The photo is red on one side of [PHOTO_BOUNDARY] and blue on the other, so two readings inside
 * the span are two different pictures rather than the same flat sheet twice.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun androidPannedPhotoComposition(): EditComposition =
  EditComposition(
    tracks =
      listOf(
        Track(
          listOf(
            Clip(androidFixtureClip(), TimeRange.of(Duration.ZERO, CLIP_LENGTH)),
            Clip(
              MediaSource.Image(ImageSource.of(androidSplitPhotoFile().path), PHOTO_LENGTH),
              effects = listOf(FIXTURE_PAN),
            ),
          ),
        ),
      ),
  )

/**
 * A photo split into a red half and a blue half, written into the cache once.
 */
internal fun androidSplitPhotoFile(): File {
  val file = File(contractContext().cacheDir, SPLIT_PHOTO_NAME)
  if (file.exists()) return file

  val bitmap = Bitmap.createBitmap(FIXTURE_FRAME.width, FIXTURE_FRAME.height, Bitmap.Config.ARGB_8888)
  val canvas = Canvas(bitmap)
  val paint = Paint()
  val edge = FIXTURE_FRAME.width * PHOTO_BOUNDARY
  paint.color = Color.RED
  canvas.drawRect(0f, 0f, edge, FIXTURE_FRAME.height.toFloat(), paint)
  paint.color = Color.BLUE
  canvas.drawRect(edge, 0f, FIXTURE_FRAME.width.toFloat(), FIXTURE_FRAME.height.toFloat(), paint)
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
  bitmap.recycle()
  return file
}

/**
 * Where the split photo's red half gives way to its blue one.
 */
internal const val PHOTO_BOUNDARY: Float = 0.5f

/**
 * A composition with nothing on it but the photo, which has no video clip to fall back on.
 */
@OptIn(ExperimentalFilmstripApi::class)
internal fun androidPhotoOnlyComposition(effects: List<EffectSpec> = emptyList()): EditComposition =
  EditComposition(
    tracks = listOf(Track(listOf(Clip(MediaSource.Image(ImageSource.of(androidPhotoFile().path), PHOTO_LENGTH))))),
    effects = effects,
  )

/**
 * A flat photo the shape of the fixture's own frame, written into the cache once.
 */
internal fun androidPhotoFile(): File {
  val file = File(contractContext().cacheDir, PHOTO_NAME)
  if (file.exists()) return file

  val bitmap = Bitmap.createBitmap(FIXTURE_FRAME.width, FIXTURE_FRAME.height, Bitmap.Config.ARGB_8888)
  bitmap.eraseColor(Color.rgb(PHOTO_COLOR.first, PHOTO_COLOR.second, PHOTO_COLOR.third))
  file.outputStream().use { bitmap.compress(Bitmap.CompressFormat.PNG, PNG_QUALITY, it) }
  bitmap.recycle()
  return file
}

/**
 * How long the photo is held, and a whole number of frames at the fixture's rate.
 */
internal val PHOTO_LENGTH: Duration = 1000.milliseconds

/**
 * Where inside the photo's span a frame is asked for.
 *
 * Neither end of it, and not its halfway point either, which a span laid an interval out could
 * still land on.
 */
internal const val PHOTO_FRACTION: Double = 0.4

/**
 * Where the photo's span starts, which is the end of the clip that runs before it.
 */
internal val PHOTO_START: Duration = CLIP_LENGTH

/**
 * A composition time inside the photo's span, at neither end of it and off its halfway point.
 */
internal val PHOTO_PROBE: Duration = PHOTO_START + PHOTO_LENGTH * PHOTO_FRACTION

/**
 * The colour the photo is filled with, which nothing the fixture's own pattern draws comes near.
 */
internal val PHOTO_COLOR: Triple<Int, Int, Int> = Triple(0x11, 0xC2, 0xAA)

/**
 * The colour at the middle of this frame.
 */
internal fun TestFrame.centre(): Triple<Int, Int, Int> {
  val offset = ((size.height / 2) * size.width + size.width / 2) * CHANNELS
  return Triple(
    pixels[offset].toInt() and BYTE_MASK,
    pixels[offset + 1].toInt() and BYTE_MASK,
    pixels[offset + 2].toInt() and BYTE_MASK,
  )
}

/**
 * Asserts two colours are within the slack a real render leaves even on a flat patch.
 */
internal infix fun Triple<Int, Int, Int>.shouldBeCloseTo(expected: Triple<Int, Int, Int>) {
  val distance = abs(first - expected.first) + abs(second - expected.second) + abs(third - expected.third)
  assertTrue(distance <= COLOR_TOLERANCE, "expected a colour near $expected, got $this")
}

/**
 * Asserts two colours are far enough apart that no render could have drawn one for the other.
 */
internal infix fun Triple<Int, Int, Int>.shouldBeNothingLike(other: Triple<Int, Int, Int>) {
  val distance = abs(first - other.first) + abs(second - other.second) + abs(third - other.third)
  assertTrue(distance > COLOR_SEPARATION, "expected a colour well away from $other, got $this")
}

private const val PHOTO_NAME = "filmstrip-player-photo.png"
private const val SPLIT_PHOTO_NAME = "filmstrip-player-split-photo.png"
private const val PNG_QUALITY = 100
private const val CHANNELS = 4
private const val BYTE_MASK = 0xFF
private const val COLOR_TOLERANCE = 24

// Wider than the tolerance by enough that a frame landing between the two reads as neither.
private const val COLOR_SEPARATION = 96
