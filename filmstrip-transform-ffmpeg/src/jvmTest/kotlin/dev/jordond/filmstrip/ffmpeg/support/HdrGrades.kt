package dev.jordond.filmstrip.ffmpeg.support

import dev.jordond.filmstrip.color.PQ_PEAK_NITS
import dev.jordond.filmstrip.color.nitsFromPqSignal
import dev.jordond.filmstrip.color.pqSignalFromNits
import dev.jordond.filmstrip.color.signalFromTenBitCodes
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.color.Contrast
import dev.jordond.filmstrip.effects.color.colorMatrixOf
import dev.jordond.filmstrip.effects.color.transform
import dev.jordond.filmstrip.effects.color.transformNits
import dev.jordond.filmstrip.ffmpeg.support.ExportConstants.HALF
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import io.kotest.assertions.withClue
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.types.shouldBeInstanceOf
import java.io.File

/**
 * The patch at the centre of [source] as the R'G'B' signal an export decodes it to, read off the file's own planes
 * through the shared matrix and left unrounded.
 */
internal suspend fun FfmpegExportFixture.authoredSignal(source: File = hdr): FloatArray {
  val size = sourceSize(source)
  val codes = tenBitCodesAt(source, size.width / 2 to size.height / 2)

  return signalFromTenBitCodes(codes[0], codes[1], codes[2])
}

// The display size of the video in source, as this fixture's backend probes it.
internal suspend fun FfmpegExportFixture.sourceSize(source: File): Size {
  val info = filmstrip.probe(MediaSource.of(source.absolutePath)).shouldBeInstanceOf<ProbeResult.Success>().info
  return withClue("$source carries no video") { info.video.shouldNotBeNull() }.displaySize
}

// The grade spec names run straight on the HDR signal lit rather than on the light it decodes to.
internal fun signalGraded(
  lit: FloatArray,
  spec: EffectSpec,
): FloatArray = checkNotNull(colorMatrixOf(spec)).transform(lit[0], lit[1], lit[2])

// The PQ signal the shared reading says lit comes out as once spec grades the light it decodes to.
internal fun pqGraded(
  lit: FloatArray,
  spec: EffectSpec,
): FloatArray {
  val nits = lit.mapChannels { nitsFromPqSignal(it) }
  val graded = checkNotNull(colorMatrixOf(spec)).transformNits(nits[0], nits[1], nits[2], HdrTransfer.Pq)

  return graded.mapChannels { pqSignalFromNits(it) }
}

internal fun FloatArray.mapChannels(transform: (Float) -> Float): FloatArray = FloatArray(size) { transform(this[it]) }

// The planes tenBitCodesFromSignal and tenBitCodesAt hand back, in their order.
internal enum class Plane { Luma, Cb, Cr }

// The contrast the kept PQ grade case flattens the fixture's patch with.
internal val FLATTENING = Contrast(HALF)

// The wrong answers to FLATTENING on the patch's signal lit, keyed by the mistake each makes. Pivoting on the middle of
// the format's own range lifts every channel towards five thousand nits.
internal fun flatteningWrongs(lit: FloatArray): Map<String, FloatArray> =
  mapOf(
    "a pivot on the format's range" to
      lit.mapChannels { pqSignalFromNits(HALF * nitsFromPqSignal(it) + (1f - HALF) * PQ_PEAK_NITS / 2f) },
    ON_THE_SIGNAL to signalGraded(lit, FLATTENING),
  )

// The planes each of flatteningWrongs lands alike on the patch, too close to tell apart there. Flattening the patch's
// signal lands its luma a few codes from flattening its light, so luma cannot tell the two apart and the chroma planes
// do.
internal val FLATTENING_ALIKE: Map<String, Set<Plane>> = mapOf(ON_THE_SIGNAL to setOf(Plane.Luma))

private const val ON_THE_SIGNAL = "the signal"
