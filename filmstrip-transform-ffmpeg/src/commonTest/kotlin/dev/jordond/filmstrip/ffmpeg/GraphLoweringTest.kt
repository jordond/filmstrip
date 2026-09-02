package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.AudioCodec
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.export.VideoCodec
import dev.jordond.filmstrip.ffmpeg.internal.GraphLowering
import dev.jordond.filmstrip.ffmpeg.internal.Invocation
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.NegotiatedComposition
import dev.jordond.filmstrip.transform.internal.ResolvedClip
import dev.jordond.filmstrip.transform.internal.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.ResolvedTrack
import dev.jordond.filmstrip.transform.internal.backgroundGain
import dev.jordond.filmstrip.transform.internal.sigmaFor
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.test.assertTrue
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// GraphLowering is exercised directly, on a hand-built NegotiatedComposition, so a fit and a fill
// can be asserted on without a toolchain to gate them or a full composition to negotiate.
class GraphLoweringTest {
  private val source = MediaSource.of("/clips/source.mp4")

  @Test
  fun `a solid fill renders as a hex colour on the pad`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()))

    graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=0xff0000"
  }

  @Test
  fun `the same colour reaches the gap a late-starting track leaves`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()), trackStart = 500.milliseconds)

    graph shouldContain "tpad=start_duration=0.500000:color=0xff0000"
  }

  // A gap has no frame of its own to blur, so it falls back to black rather than reaching for a
  // frame that has not played yet.
  @Test
  fun `a blurred fill leaves the gap black`() {
    val graph = graphFor(fill = Fill.Blurred(), trackStart = 500.milliseconds)

    graph shouldContain "tpad=start_duration=0.500000:color=0x000000"
  }

  @Test
  fun `a blurred fill splits into a cropped blurred background and a contained foreground`() {
    val graph = graphFor(fill = Fill.Blurred(radius = 0.5f, dim = 0f), output = Size(100, 200))

    graph shouldContain "split=2"
    graph shouldContain "scale=w=100:h=200:force_original_aspect_ratio=increase,crop=w=100:h=200,gblur=sigma=50"
    graph shouldContain "scale=w=100:h=200:force_original_aspect_ratio=decrease"
    graph shouldContain "overlay=x=(W-w)/2:y=(H-h)/2"
  }

  // The report scenario: a 1920x1080 source into a 1080x1920 output. Asserted against sigmaFor
  // itself, not a hardcoded number, so a change to the shared contract fails this test rather
  // than silently drifting from what the other backends draw.
  @Test
  fun `sigma is the radius times the output's shorter side`() {
    val fill = Fill.Blurred(radius = 0.04f)
    val output = Size(1080, 1920)

    val graph = graphFor(fill = fill, output = output)

    graph shouldContain "gblur=sigma=${fill.sigmaFor(output).roundToInt()}"
  }

  // A radius between the default and the one gblur clamps, so the assertion fails under any
  // reading of the contract that happens to agree with it at the ends of the range.
  @Test
  fun `a mid-range radius lowers to the shared sigma uncapped`() {
    val fill = Fill.Blurred(radius = 0.5f)
    val output = Size(1080, 1920)
    val sigma = fill.sigmaFor(output).roundToInt()

    val graph = graphFor(fill = fill, output = output)

    assertTrue(sigma in 2..<GBLUR_SIGMA_CEILING, "$sigma is not inside the range this test claims to cover")
    graph shouldContain "gblur=sigma=$sigma"
  }

  // gblur itself refuses a sigma above its ceiling, so a wide-open radius on a large frame is
  // clamped rather than handed straight through to a value ffmpeg would reject at run time.
  @Test
  fun `sigma clamps to gblur's own ceiling`() {
    val fill = Fill.Blurred(radius = 1f)
    val output = Size(1080, 1920)
    val uncapped = fill.sigmaFor(output).roundToInt()

    val graph = graphFor(fill = fill, output = output)

    graph shouldContain "gblur=sigma=$GBLUR_SIGMA_CEILING"
    graph shouldNotContain "sigma=$uncapped"
  }

  @Test
  fun `a zero dim emits no gain node`() {
    val graph = graphFor(fill = Fill.Blurred(radius = 0.04f, dim = 0f))

    graph shouldNotContain "colorchannelmixer"
  }

  @Test
  fun `a non-zero dim darkens only the background`() {
    val fill = Fill.Blurred(radius = 0.04f, dim = 0.3f)
    val gain = fill.backgroundGain

    val graph = graphFor(fill = fill)

    graph shouldContain "colorchannelmixer=rr=$gain:gg=$gain:bb=$gain"
    Regex("colorchannelmixer").findAll(graph).count() shouldBe 1
  }

  // eq's brightness is an additive offset, not a gain, so lowering a dim through it would zero a
  // mid-grey background instead of halving it. A gain multiplies, so 1 - 0.5 lands on a round,
  // unambiguous 0.5 rather than something only a formula could confirm.
  @Test
  fun `a dim of one half multiplies the background rather than offsetting it`() {
    val graph = graphFor(fill = Fill.Blurred(radius = 0.04f, dim = 0.5f))

    graph shouldContain "colorchannelmixer=rr=0.5:gg=0.5:bb=0.5"
    graph shouldNotContain "eq=brightness"
  }

  // Crop leaves no bars, so there is nothing for a blurred background to show through and the
  // tail stays the same single chain a solid fill uses.
  @Test
  fun `a crop fit never splits whatever the fill is`() {
    val graph = graphFor(fill = Fill.Blurred(), fit = Fit.Crop)

    graph shouldNotContain "split="
  }

  // With no composition effect to reach the fill, the graph paints the bars straight in.
  @Test
  fun `with no composition effect the bars are painted straight in`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()))

    graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=0xff0000"
    graph shouldNotContain "yuva420p"
    graph shouldNotContain "overlay=shortest=1"
  }

  // A composition effect can now reach the fill, so the colour is withheld from every pad and
  // tpad, carried through as alpha, and only flattened onto the fill after the effect has run.
  @Test
  fun `a composition effect defers a solid fill's bars to a final overlay`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()), compositionEffects = listOf(compositionEffect()))

    graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=black@0"
    graph shouldContain "format=pix_fmts=yuva420p"
    graph shouldContain "color=c=0xff0000:s=1920x1080:r=30"
    graph shouldContain "overlay=shortest=1"

    val effectIndex = graph.indexOf("eq=brightness=0.1")
    val overlayIndex = graph.indexOf("overlay=shortest=1")
    assertTrue(
      effectIndex in 0 until overlayIndex,
      "the composition effect ran at $effectIndex, the overlay at $overlayIndex",
    )
  }

  // A gap has no frame to blur either way, so it defers the same colour a solid fill would, even
  // though the blurred bars themselves never do.
  @Test
  fun `a blurred fill still defers its gap's colour when a composition effect runs`() {
    val graph =
      graphFor(fill = Fill.Blurred(), trackStart = 500.milliseconds, compositionEffects = listOf(compositionEffect()))

    graph shouldContain "tpad=start_duration=0.500000:color=black@0"
    graph shouldContain "format=pix_fmts=yuva420p"
    graph shouldContain "color=c=0x000000:s=1920x1080:r=30"
    graph shouldContain "overlay=shortest=1"
  }

  // overlay writes 8-bit yuv420 unless it is asked not to, so any overlay left at that default takes
  // a kept 10-bit grade down and back up on its way to the encoder. Nothing about the exported file
  // says it happened, so every overlay the backend can emit is checked rather than just the one the
  // fill flatten adds.
  @Test
  fun `every overlay leaves the depth its inputs arrived at alone`() {
    val effects = listOf(compositionEffect())
    val tall = Size(1080, 1920)
    val graphs =
      listOf(
        graphFor(fill = Fill.Solid(0xFFFF0000.toInt()), compositionEffects = effects),
        graphFor(fill = Fill.Blurred(), output = tall),
        graphFor(fill = Fill.Blurred(), output = tall, compositionEffects = effects),
      )

    graphs.forEach { graph ->
      val overlays = Regex("""overlay=[^,;\[\]]*""").findAll(graph).map { it.value }.toList()
      assertTrue(overlays.isNotEmpty(), "no overlay in $graph")
      overlays.forEach { it shouldContain "format=auto" }
    }
  }

  // A node names its file by placeholder, and only the invocation carries the bytes, so a fragment
  // whose sidecar is dropped on the way through reaches ffmpeg naming a file nobody wrote. Both
  // scopes are checked, because they are gathered on two separate lines.
  @Test
  fun `gathers the files an effect's nodes name, from either scope`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")
    val composition = invocationFor(fill = Fill.Solid(0), compositionEffects = listOf(gradeEffect(sidecar)))
    val clip = invocationFor(fill = Fill.Solid(0), clipEffects = listOf(gradeEffect(sidecar)))

    composition.sidecars shouldBe listOf(sidecar)
    composition.filterGraph shouldContain "lut3d=file=${sidecar.placeholder}"
    clip.sidecars shouldBe listOf(sidecar)
    clip.filterGraph shouldContain "lut3d=file=${sidecar.placeholder}"
  }

  // Two clips carrying the same grade lower to the same file, and a placeholder names its contents,
  // so the one path serves both references.
  @Test
  fun `writes one file for a grade two effects share`() {
    val sidecar = Sidecar(CUBE.encodeToByteArray(), "cube")
    val invocation =
      invocationFor(
        fill = Fill.Solid(0),
        compositionEffects = listOf(gradeEffect(sidecar)),
        clipEffects = listOf(gradeEffect(sidecar)),
      )

    invocation.sidecars shouldBe listOf(sidecar)
  }

  private fun compositionEffect(): ResolvedEffect =
    ResolvedEffect(
      specId = "test.brightness",
      effect = PlatformEffect(FilterFragment(chain = listOf(FilterNode("eq", "brightness" to "0.1")))),
    )

  private fun gradeEffect(sidecar: Sidecar): ResolvedEffect =
    ResolvedEffect(
      specId = "test.grade",
      effect =
        PlatformEffect(
          FilterFragment(
            chain = listOf(FilterNode("lut3d", "file" to sidecar.placeholder)),
            sidecars = listOf(sidecar),
          ),
        ),
    )

  private fun graphFor(
    fill: Fill,
    fit: Fit = Fit.Contain,
    output: Size = Size(1920, 1080),
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
  ): String = invocationFor(fill, fit, output, trackStart, compositionEffects).filterGraph

  private fun invocationFor(
    fill: Fill,
    fit: Fit = Fit.Contain,
    output: Size = Size(1920, 1080),
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clipEffects: List<ResolvedEffect> = emptyList(),
  ): Invocation {
    val duration = 2.seconds
    val info =
      MediaInfo(
        duration = duration,
        video =
          VideoTrackInfo(
            codedSize = Size(1920, 1080),
            displaySize = Size(1920, 1080),
            rotationDegrees = 0,
            pixelAspectRatio = 1f,
            frameRate = 30f,
            codec = trackCodecOf("avc1"),
            bitDepth = 8,
            colorSpace = ColorSpace.Bt709,
            hdrTransfer = null,
            bitrate = null,
          ),
        audio = null,
        isExportable = true,
      )
    val clip =
      ResolvedClip(
        source = source,
        info = info,
        start = Duration.ZERO,
        end = duration,
        effects = clipEffects,
        gain = 1f,
        startsAtKeyFrame = false,
        span = TimeRange.of(trackStart, trackStart + duration),
      )
    val track =
      ResolvedTrack(content = TrackContent.Video, looping = false, start = trackStart, clips = listOf(clip))
    val negotiated =
      NegotiatedComposition(
        tracks = listOf(track),
        compositionGeometry = emptyList(),
        compositionInputSize = output,
        compositionEffects = compositionEffects,
        output =
          OutputFormat(
            size = output,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.None,
            bitrate = null,
            frameRate = 30,
            audioFormat = null,
          ),
        layoutSize = output,
        fit = fit,
        fill = fill,
        duration = track.duration,
        hdr = ResolvedHdr.Keep,
        hdrTransfer = null,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        encoderName = "libx264",
      )

    return GraphLowering(negotiated, toneMapRoute = null, hdrPixelFormat = null).build()
  }
}

// gblur's own ceiling on sigma, a literal here on purpose: it pins what ffmpeg accepts rather than
// anything the shared contract says.
private const val GBLUR_SIGMA_CEILING = 1024

// The header of the smallest table this backend writes, standing in for the file an effect brings.
private const val CUBE = "LUT_3D_SIZE 2"
