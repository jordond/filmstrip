package dev.jordond.filmstrip.ffmpeg

import dev.jordond.filmstrip.codec.AudioCodec
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.color.authoredHdrNits
import dev.jordond.filmstrip.color.signalFromNits
import dev.jordond.filmstrip.color.tenBitCodesFromSignal
import dev.jordond.filmstrip.edit.AudioSpec
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.edit.TrackContent
import dev.jordond.filmstrip.edit.outputSlotOf
import dev.jordond.filmstrip.edit.outputSlotsOf
import dev.jordond.filmstrip.effect.AuxInput
import dev.jordond.filmstrip.effect.EffectStage
import dev.jordond.filmstrip.effect.FilterCrop
import dev.jordond.filmstrip.effect.FilterFragment
import dev.jordond.filmstrip.effect.FilterNode
import dev.jordond.filmstrip.effect.PlatformEffect
import dev.jordond.filmstrip.effect.Sidecar
import dev.jordond.filmstrip.export.AudioFormat
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.OutputFormat
import dev.jordond.filmstrip.ffmpeg.internal.lowering.GraphLowering
import dev.jordond.filmstrip.ffmpeg.internal.lowering.InputSource
import dev.jordond.filmstrip.ffmpeg.internal.lowering.Invocation
import dev.jordond.filmstrip.ffmpeg.internal.lowering.arguments
import dev.jordond.filmstrip.ffmpeg.internal.lowering.ffmpegTag
import dev.jordond.filmstrip.ffmpeg.internal.lowering.formatSeconds
import dev.jordond.filmstrip.ffmpeg.internal.process.FfmpegVersion
import dev.jordond.filmstrip.ffmpeg.internal.process.Toolchain
import dev.jordond.filmstrip.geometry.Fill
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.AudioTrackInfo
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.HdrTransfer
import dev.jordond.filmstrip.media.ImageSource
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.VideoTrackInfo
import dev.jordond.filmstrip.media.trackCodecOf
import dev.jordond.filmstrip.transform.internal.geometry.backgroundGain
import dev.jordond.filmstrip.transform.internal.geometry.sigmaFor
import dev.jordond.filmstrip.transform.internal.passesCovering
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedClip
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedEffect
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedGain
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedTrack
import io.kotest.assertions.withClue
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import io.kotest.matchers.string.shouldNotContain
import kotlin.math.roundToInt
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds
import kotlin.time.DurationUnit

// GraphLowering is exercised directly, on a hand-built ResolvedComposition, so a fit and a fill
// can be asserted on without a toolchain to gate them or a full composition to negotiate.
class GraphLoweringTest {
  private val source = MediaSource.of("/clips/source.mp4")

  @Test
  fun `a solid fill renders as a hex color on the pad`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()))

    graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=0xff0000"
  }

  @Test
  fun `the same color reaches the gap a late-starting track leaves`() {
    val graph = graphFor(fill = Fill.Solid(0xFFFF0000.toInt()), trackStart = 500.milliseconds)

    graph shouldContain "tpad=start=${outputSlotOf(500.milliseconds, 30)}:color=0xff0000"
  }

  // A gap has no frame of its own to blur, so it falls back to black rather than reaching for a
  // frame that has not played yet.
  @Test
  fun `a blurred fill leaves the gap black`() {
    val graph = graphFor(fill = Fill.Blurred(), trackStart = 500.milliseconds)

    graph shouldContain "tpad=start=${outputSlotOf(500.milliseconds, 30)}:color=0x000000"
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

    withClue("$sigma is not inside the range this test claims to cover") {
      (sigma in 2..<GBLUR_SIGMA_CEILING) shouldBe true
    }
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

  // A composition effect can now reach the fill, so the color is withheld from every pad and
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
    withClue("the composition effect ran at $effectIndex, the overlay at $overlayIndex") {
      (effectIndex in 0 until overlayIndex) shouldBe true
    }
  }

  // A pad color is eight-bit sRGB drawn through the filter's own matrix, so on a kept grade the bars and the gap stay
  // empty and the flatten writes the codes the shared HDR light puts the color at, with no effect asking for it.
  @Test
  fun `a kept grade defers a solid fill onto the codes the shared HDR light puts it at`() {
    val color = 0xFF308020.toInt()
    for (transfer in HdrTransfer.entries) {
      val graph = graphFor(fill = Fill.Solid(color), trackStart = 500.milliseconds, hdrTransfer = transfer)
      val (luma, cb, cr) = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color)))

      graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=black@0"
      graph shouldContain "tpad=start=${outputSlotOf(500.milliseconds, 30)}:color=black@0"
      graph shouldContain "format=pix_fmts=yuv420p10le,lutyuv=y=$luma:u=$cb:v=$cr"
      graph shouldContain "overlay=shortest=1"
      graph shouldNotContain "0x308020"
    }
  }

  // The fill is flat, so its codes are written into one frame and that frame is looped, rather than converting and
  // re-reading a table on every frame the flatten pulls.
  @Test
  fun `a kept grade writes the fill's codes once and loops that frame`() {
    val color = 0xFF308020.toInt()
    for (transfer in HdrTransfer.entries) {
      val graph = graphFor(fill = Fill.Solid(color), trackStart = 500.milliseconds, hdrTransfer = transfer)
      val (luma, cb, cr) = tenBitCodesFromSignal(transfer.signalFromNits(authoredHdrNits(color)))
      val grade = "setparams=color_primaries=bt2020:color_trc=${transfer.ffmpegTag}:colorspace=bt2020nc"

      graph shouldContain
        "color=s=1920x1080:r=30,trim=end_frame=1,format=pix_fmts=yuv420p10le,lutyuv=y=$luma:u=$cb:v=$cr," +
        "$grade,loop=loop=-1:size=1[vbg]"
      graph.split("lutyuv").size shouldBe 2
    }
  }

  // concat writes microseconds, so a lead padded after it steps a rounded slot per frame. The deferred fill's flatten
  // stops on the picture's last frame, and one stamped early there loses the final slot.
  @Test
  fun `a deferred fill restamps joined clips onto the output frame grid ahead of the lead`() {
    for (transfer in HdrTransfer.entries) {
      val graph = graphFor(fill = Fill.Solid(0), trackStart = 500.milliseconds, hdrTransfer = transfer, passes = 2)

      graph shouldContain "[v0][v1]concat=n=2:v=1:a=0,settb=expr=1/30[vcat];[vcat]tpad=start="
    }
  }

  // Nothing drops a slot when the fill is painted straight in, but the drift would still reach the written timestamps.
  @Test
  fun `a fill painted straight in restamps joined clips onto the output frame grid ahead of the lead`() {
    val graph = graphFor(fill = Fill.Solid(0), trackStart = 500.milliseconds, passes = 2)

    graph shouldContain "[v0][v1]concat=n=2:v=1:a=0,settb=expr=1/30[vcat];[vcat]tpad=start="
    graph shouldNotContain "shortest=1"
  }

  // A gap has no frame to blur either way, so it defers the same color a solid fill would, even
  // though the blurred bars themselves never do.
  @Test
  fun `a blurred fill still defers its gap's color when a composition effect runs`() {
    val graph =
      graphFor(fill = Fill.Blurred(), trackStart = 500.milliseconds, compositionEffects = listOf(compositionEffect()))

    graph shouldContain "tpad=start=${outputSlotOf(500.milliseconds, 30)}:color=black@0"
    graph shouldContain "format=pix_fmts=yuva420p"
    graph shouldContain "color=c=0x000000:s=1920x1080:r=30"
    graph shouldContain "overlay=shortest=1"
  }

  // The fill goes in after the color effect and before the overlay, so the overlay draws on an opaque frame with
  // the bars already painted.
  @Test
  fun `flattens the fill between a color effect and a composition overlay`() {
    val graph =
      graphFor(fill = Fill.Solid(0xFFFF0000.toInt()), compositionEffects = listOf(compositionEffect(), logoOverlay()))

    val effectIndex = graph.indexOf("eq=brightness=0.1")
    val fillIndex = graph.indexOf("color=c=0xff0000:s=1920x1080:r=30")
    val flattenIndex = graph.indexOf("overlay=shortest=1")
    val overlayIndex = graph.indexOf("overlay=x=0:y=0")
    withClue("the color effect ran at $effectIndex, the fill at $fillIndex") {
      (effectIndex in 0 until fillIndex) shouldBe true
    }
    withClue("the fill came at $fillIndex, the flatten at $flattenIndex") {
      (fillIndex in 0 until flattenIndex) shouldBe true
    }
    withClue("the flatten ran at $flattenIndex, the overlay at $overlayIndex") {
      (flattenIndex in 0 until overlayIndex) shouldBe true
    }
    graph shouldContain "[vflat][vfx1a0]overlay=x=0:y=0[vfx1]"
  }

  // Nothing ahead of an overlay can reach the fill, so the bars and the gap take its color straight away and the
  // overlay draws on an opaque frame.
  @Test
  fun `a composition with only overlays paints the fill straight in`() {
    val graph =
      graphFor(
        fill = Fill.Solid(0xFFFF0000.toInt()),
        trackStart = 500.milliseconds,
        compositionEffects = listOf(logoOverlay()),
      )

    graph shouldContain "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=0xff0000"
    graph shouldContain "tpad=start=${outputSlotOf(500.milliseconds, 30)}:color=0xff0000"
    graph shouldContain "overlay=x=0:y=0"
    graph shouldNotContain "yuva420p"
    graph shouldNotContain "black@0"
    graph shouldNotContain "overlay=shortest=1"
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
      withClue("no overlay in $graph") { overlays.isNotEmpty() shouldBe true }
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

  // A copy runs no graph, so the only place a trim can land is the input's own bounds. Without
  // them ffmpeg would remux the whole source and the window would disappear silently.
  @Test
  fun `a windowed copy carries the clip's bounds onto its input`() {
    val clip = trimmedClip()

    val input = copyInvocationFor(clip).inputs.single()

    input.startSeconds shouldBe clip.start.toDouble(DurationUnit.SECONDS)
    input.durationSeconds shouldBe clip.duration.toDouble(DurationUnit.SECONDS)
  }

  // Nothing is windowed away, so bounds would only ask ffmpeg to trim the container to the length
  // it already has, and a rounded one at that.
  @Test
  fun `an untrimmed copy carries no bounds at all`() {
    val clip = trimmedClip(start = Duration.ZERO, end = SOURCE_LENGTH)

    val input = copyInvocationFor(clip).inputs.single()

    input.startSeconds shouldBe null
    input.durationSeconds shouldBe null
  }

  // Only the start moved, so the copy runs to the source's own end and asking for a length would
  // clip the tail by however far the trim start was rounded.
  @Test
  fun `a copy trimmed only at the front seeks without bounding its length`() {
    val clip = trimmedClip(end = SOURCE_LENGTH)

    val input = copyInvocationFor(clip).inputs.single()

    input.startSeconds shouldBe clip.start.toDouble(DurationUnit.SECONDS)
    input.durationSeconds shouldBe null
  }

  // A looping track names the same clip once per pass, and one decoder per pass would mean opening
  // the same file five times for a run of two.
  @Test
  fun `a looping track opens each of its clips once and splits the branch per pass`() {
    val laid = loopingTrack()
    val invocation = loopingInvocationFor(laid)

    invocation.inputs.count { it.source is InputSource.OfPath } shouldBe laid.clips.distinctBy { it.sourceIndex }.size
    laid.clips.distinctBy { it.sourceIndex }.forEachIndexed { input, clip ->
      val passes = laid.clips.count { it.sourceIndex == clip.sourceIndex }
      // Anchored on the pad, since "split=n" is a substring of "asplit=n" and either assertion
      // alone would then be carried by the other node.
      invocation.filterGraph shouldContain "[$input:v]split=$passes"
      invocation.filterGraph shouldContain "[$input:a]asplit=$passes"
    }
  }

  // -stream_loop carries each repeat at rising timestamps, which is what used to make an atrim
  // written for the clip's own window drop every pass after the first.
  @Test
  fun `a looping track asks ffmpeg for no stream loop`() {
    val laid = loopingTrack()
    val arguments =
      loopingInvocationFor(laid)
        .arguments(TOOLCHAIN, FfmpegConfig(), laid.clips.map { "/clips/source.mp4" }, emptyList(), "/out.mp4")

    arguments.shouldNotContain("-stream_loop")
  }

  // Every pass reads a branch of its own off the split and windows that, the cut last pass
  // included, so a pass stops where the plan said rather than where its source ends. Opening one
  // input per pass would window the input pads instead, which is what this pins against.
  @Test
  fun `every laid pass windows the branch it was split onto and the concat joins them all`() {
    val laid = loopingTrack()
    val graph = loopingInvocationFor(laid).filterGraph

    val passes = mutableMapOf<Int, Int>()
    laid.clips.forEach { clip ->
      // The track is the first, so its inputs open in the order its clips first appear, which for
      // this fixture is the source index itself.
      val pass = passes.getOrElse(clip.sourceIndex) { 0 }
      passes[clip.sourceIndex] = pass + 1
      val start = formatSeconds(clip.start.toDouble(DurationUnit.SECONDS))
      val end = formatSeconds(clip.end.toDouble(DurationUnit.SECONDS))
      graph shouldContain "[v${clip.sourceIndex}p$pass]trim=start=$start:end=$end"
      graph shouldContain "[a${clip.sourceIndex}p$pass]atrim=start=$start:end=$end"
    }
    graph shouldContain "]concat=n=${laid.clips.size}:v=1:a=0"
  }

  // amix below ffmpeg 7 throws away what it still holds of its first input once that input ends,
  // which cost a looping first track every pass after its first. Silence reads first instead, as long
  // as the longest track rather than the first one, so it neither cuts the mix short nor pads it.
  @Test
  fun `a mix reads silence as long as its longest track ahead of every track`() {
    val looping = loopingTrack()
    val underlay = underlayTrack()
    withClue("the underlay has to outlast the loop to tell them apart") {
      (underlay.duration > looping.duration) shouldBe true
    }

    val graph = loopingInvocationFor(looping, underlay).filterGraph

    val longest = formatSeconds(underlay.duration.toDouble(DurationUnit.SECONDS))
    graph shouldContain "anullsrc=r=48000:cl=stereo,atrim=end=$longest,"
    graph shouldContain "[alead][at0][ad1]amix=inputs=3:"
  }

  // A clip's frames are cut to the slots its span covers on the output grid, and its last frame is
  // cloned ahead of the cut so a span that runs past the source still has a frame for every slot.
  // A branch ending on its last frame's own time has that end moved a slot on ahead of fps.
  @Test
  fun `cuts a clip to the slots its span covers and holds its last frame into them`() {
    val laid = gridTrack(listOf(OFF_GRID_LENGTH))
    val slots = outputSlotsOf(laid.clips.single().span, GRID_RATE).count()

    val graph = gridInvocationFor(laid).filterGraph

    graph shouldContain
      "setsar=r=1,setpts=expr=PTS+eq(PTS\\,PREV_INPTS)/($GRID_RATE*TB),fps=fps=$GRID_RATE," +
      "tpad=stop_mode=clone:stop=2,trim=end_frame=$slots,setpts=expr=PTS-STARTPTS,format=pix_fmts=yuv420p[v0]"
  }

  // Three clips of 1.010 s meet on either side of a half slot, so the middle one covers a slot more
  // than the two either side of it. Rounding each clip's own length instead gives all three the same
  // count and loses a slot by the end of the third.
  @Test
  fun `cuts back to back clips to the slots between their snapped boundaries`() {
    val laid = gridTrack(List(3) { OFF_GRID_LENGTH })
    val counts = laid.clips.map { outputSlotsOf(it.span, GRID_RATE).count() }
    withClue("every clip covers ${counts.first()} slots, so no seam moved") {
      (counts.distinct().size > 1) shouldBe true
    }

    val graph = gridInvocationFor(laid).filterGraph

    counts.forEachIndexed { index, slots ->
      graph shouldContain "trim=end_frame=$slots,setpts=expr=PTS-STARTPTS,format=pix_fmts=yuv420p[v$index]"
    }
    graph shouldContain "[v0][v1][v2]concat=n=3:v=1:a=0"
  }

  // A clip between two others that reaches no point half way between two slots covers none of them,
  // so it lays no picture at all. Its sound is exact rather than snapped, so the audio still joins it.
  @Test
  fun `lays no picture for a clip that covers no slot and keeps its sound`() {
    val laid = gridTrack(listOf(1.seconds, UNDER_HALF_SLOT, 1.seconds))
    withClue("the middle clip covers a slot") { outputSlotsOf(laid.clips[1].span, GRID_RATE).isEmpty() shouldBe true }

    val graph = gridInvocationFor(laid).filterGraph

    graph shouldContain "[v0][v2]concat=n=2:v=1:a=0"
    graph shouldNotContain "[1:v]"
    graph shouldContain "[a0c0][a0c1][a0c2]concat=n=3:v=0:a=1"
  }

  // A looping pass the run cuts to under half a slot lays no picture, so the split its passes read
  // their pictures from has one outlet fewer than the one they read their sound from. An outlet
  // nothing reads is a graph ffmpeg refuses to run.
  @Test
  fun `splits a looping clip's picture only onto the passes that lay one`() {
    val passes =
      passesCovering(listOf(1.seconds), 2.seconds + UNDER_HALF_SLOT).map { pass ->
        loopedClip(start = Duration.ZERO, length = pass.length, offset = pass.offset, sourceIndex = pass.index)
      }
    val laid = ResolvedTrack(TrackContent.AudioAndVideo, looping = true, start = Duration.ZERO, clips = passes)
    val pictured = passes.count { !outputSlotsOf(it.span, GRID_RATE).isEmpty() }
    withClue("$pictured of ${passes.size} passes lay a picture") { (pictured in 2..<passes.size) shouldBe true }

    val graph = gridInvocationFor(laid).filterGraph

    graph shouldContain "[0:v]split=$pictured["
    graph shouldContain "[0:a]asplit=${passes.size}["
    graph shouldContain "concat=n=$pictured:v=1:a=0"
  }

  // The gap ahead of a late track is the slots its start covers, the count the browser encodes and
  // Apple inserts, rather than a duration ffmpeg rounds for itself. Half a slot rounds up onto one.
  @Test
  fun `leads a late track with the slots its start covers`() {
    val graph = gridInvocationFor(gridTrack(listOf(1.seconds), start = HALF_SLOT_START)).filterGraph

    graph shouldContain "tpad=start=${outputSlotOf(HALF_SLOT_START, GRID_RATE)}:color=0x000000"
  }

  // A start short of half a slot rounds down onto the first slot, so there is no gap to lay.
  @Test
  fun `lays no lead for a start short of half a slot`() {
    outputSlotOf(SHORT_START, GRID_RATE) shouldBe 0L

    val graph = gridInvocationFor(gridTrack(listOf(1.seconds), start = SHORT_START)).filterGraph

    graph shouldNotContain "tpad=start"
  }

  // The blurred letterbox closes each clip on the merge of its two copies, and that merge takes the
  // same cut the plain tail does.
  @Test
  fun `closes a blurred tail on the slots its span covers`() {
    val laid = gridTrack(listOf(OFF_GRID_LENGTH))
    val slots = outputSlotsOf(laid.clips.single().span, GRID_RATE).count()

    val graph = gridInvocationFor(laid, fill = Fill.Blurred()).filterGraph

    graph shouldContain
      "overlay=x=(W-w)/2:y=(H-h)/2:format=auto,setsar=r=1,setpts=expr=PTS+eq(PTS\\,PREV_INPTS)/($GRID_RATE*TB)," +
      "fps=fps=$GRID_RATE,tpad=stop_mode=clone:stop=2,trim=end_frame=$slots,"
  }

  /**
   * One clip per length, laid end to end from [start] the way the planner lays a track, each reading
   * the head of a source of its own.
   */
  private fun gridTrack(
    lengths: List<Duration>,
    start: Duration = Duration.ZERO,
  ): ResolvedTrack {
    var offset = start
    val clips =
      lengths.mapIndexed { index, length ->
        loopedClip(start = Duration.ZERO, length = length, offset = offset, sourceIndex = index).also {
          offset += length
        }
      }
    return ResolvedTrack(content = TrackContent.AudioAndVideo, looping = false, start = start, clips = clips)
  }

  private fun gridInvocationFor(
    track: ResolvedTrack,
    fill: Fill = Fill.Black,
  ): Invocation {
    val output = Size(1920, 1080)
    val negotiated =
      ResolvedComposition(
        tracks = listOf(track),
        compositionGeometry = emptyList(),
        compositionInputSize = output,
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = output,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = GRID_RATE,
            audioFormat = AudioFormat(sampleRate = 48_000, channelCount = 2),
          ),
        layoutSize = output,
        fit = Fit.Contain,
        fill = fill,
        duration = track.duration,
        hdr = ResolvedHdr.Keep,
        hdrTransfer = null,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        encoderName = "libx264",
      )

    return GraphLowering(negotiated, hdrPixelFormat = null).build()
  }

  /**
   * Two clips laid down two and a half times over, scheduled by the shared [passesCovering] rather
   * than written out here, so a change to how a run is laid reaches this backend's test too.
   */
  private fun loopingTrack(): ResolvedTrack {
    val clips =
      passesCovering(listOf(A_LENGTH, B_LENGTH), RUN).map { pass ->
        loopedClip(
          start = if (pass.index == 0) A_START else B_START,
          length = pass.length,
          offset = pass.offset,
          sourceIndex = pass.index,
        )
      }
    return ResolvedTrack(content = TrackContent.AudioAndVideo, looping = true, start = Duration.ZERO, clips = clips)
  }

  /**
   * An audio-only track that opens after the loop does and ends after it too.
   */
  private fun underlayTrack(): ResolvedTrack {
    val clip = loopedClip(start = Duration.ZERO, length = UNDERLAY_LENGTH, offset = UNDERLAY_START, sourceIndex = 0)
    return ResolvedTrack(content = TrackContent.Audio, looping = false, start = UNDERLAY_START, clips = listOf(clip))
  }

  private fun loopedClip(
    start: Duration,
    length: Duration,
    offset: Duration,
    sourceIndex: Int,
  ): ResolvedClip =
    ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = SOURCE_LENGTH,
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
          audio = AudioTrackInfo(trackCodecOf("mp4a"), 48_000, 2, null),
          isExportable = true,
        ),
      start = start,
      end = start + length,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, length),
      startsAtKeyFrame = false,
      span = TimeRange.of(offset, offset + length),
      sourceIndex = sourceIndex,
    )

  private fun loopingInvocationFor(vararg tracks: ResolvedTrack): Invocation {
    val output = Size(1920, 1080)
    val negotiated =
      ResolvedComposition(
        tracks = tracks.toList(),
        compositionGeometry = emptyList(),
        compositionInputSize = output,
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = output,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = 30,
            audioFormat = AudioFormat(sampleRate = 48_000, channelCount = 2),
          ),
        layoutSize = output,
        fit = Fit.Contain,
        fill = Fill.Black,
        duration = tracks.maxOf { it.duration },
        hdr = ResolvedHdr.Keep,
        hdrTransfer = null,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        encoderName = "libx264",
      )

    return GraphLowering(negotiated, hdrPixelFormat = null).build()
  }

  // A chain's input labels link to its first filter, so an effect bringing both a chain and a merge
  // cannot have the chain written between the labels and the merge. ffmpeg refuses that graph
  // outright, and nothing in the catalogue produces one today, so this builds the shape by hand.
  @Test
  fun `flushes a merging composition effect's chain onto its own pad`() {
    val merging =
      ResolvedEffect(
        specId = "test.merging",
        effect =
          PlatformEffect(
            FilterFragment(
              chain = listOf(FilterNode("eq", "brightness" to "0.1")),
              auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
              merge = FilterNode("overlay", "x" to "0", "y" to "0"),
            ),
          ),
        stage = EffectStage.Composite,
      )

    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(merging))

    graph shouldContain "eq=brightness=0.1[vfx0pre]"
    graph shouldContain "[vfx0pre][vfx0a0]overlay=x=0:y=0[vfx0]"
  }

  // The branch closes on a setpts back to its own first frame, and says so, so that a preview can
  // open the image at the scrub and have the move come back off before the merge lines the frames
  // up. An export reads the image from zero, where the node changes nothing.
  @Test
  fun `rebases an overlay's own branch onto its first frame`() {
    val overlay =
      ResolvedEffect(
        specId = "test.overlay",
        effect =
          PlatformEffect(
            FilterFragment(
              auxInputs =
                listOf(
                  AuxInput(ImageSource.of("/logo.png"), listOf(FilterNode("format", "pix_fmts" to "rgba"))),
                ),
              merge = FilterNode("overlay", "x" to "0", "y" to "0"),
            ),
          ),
        stage = EffectStage.Composite,
      )

    val invocation = invocationFor(fill = Fill.Solid(0), compositionEffects = listOf(overlay))

    invocation.filterGraph shouldContain "format=pix_fmts=rgba,setpts=expr=PTS-STARTPTS[vfx0a0]"
    invocation.inputs.single { it.source is InputSource.OfImage }.rebased shouldBe true
    invocation.inputs.first().rebased shouldBe false
  }

  // An effect contributing nothing ahead of its merge reads the pad it was handed, which is every
  // overlay in the catalogue and leaves the graph they have always written unchanged.
  @Test
  fun `leaves a merging effect with no chain reading the pad it was handed`() {
    val overlay =
      ResolvedEffect(
        specId = "test.overlay",
        effect =
          PlatformEffect(
            FilterFragment(
              auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
              merge = FilterNode("overlay", "x" to "0", "y" to "0"),
            ),
          ),
        stage = EffectStage.Composite,
      )

    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(overlay))

    graph shouldNotContain "vfx0pre"
    graph shouldContain "[vfx0a0]overlay=x=0:y=0[vfx0]"
  }

  // Pinned against the graph the lowering wrote before fragments could crop or run nodes after their
  // merge, at both places a fragment is lowered, with and without a merge.
  @Test
  fun `a fragment with no crop and nothing after its merge lowers as it always has`() {
    val effects = listOf(compositionEffect(), chainedOverlay())

    val graph = invocationFor(fill = Fill.Solid(0), compositionEffects = effects, clipEffects = effects).filterGraph

    graph shouldBe
      listOf(
        "[0:v]trim=start=0.000000:end=2.000000,setpts=expr=PTS-STARTPTS,eq=brightness=0.1,eq=contrast=1.2[v0m0pre]",
        "[1:v]setpts=expr=PTS-STARTPTS[v0m0a0]",
        "[v0m0pre][v0m0a0]overlay=x=0:y=0[v0m0]",
        "[v0m0]scale=w=1920:h=1080:force_original_aspect_ratio=decrease," +
          "pad=w=1920:h=1080:x=(ow-iw)/2:y=(oh-ih)/2:color=black@0,setsar=r=1," +
          "setpts=expr=PTS+eq(PTS\\,PREV_INPTS)/(30*TB),fps=fps=30,tpad=stop_mode=clone:stop=2," +
          "trim=end_frame=60,setpts=expr=PTS-STARTPTS,format=pix_fmts=yuva420p[v0]",
        "[v0]eq=brightness=0.1[vfx0]",
        "color=c=0x000000:s=1920x1080:r=30[vbg]",
        "[vbg][vfx0]overlay=shortest=1:format=auto,format=pix_fmts=yuv420p[vflat]",
        "[vflat]eq=contrast=1.2[vfx1pre]",
        "[2:v]setpts=expr=PTS-STARTPTS[vfx1a0]",
        "[vfx1pre][vfx1a0]overlay=x=0:y=0[vfx1]",
        "[vfx1]trim=end=2.000000,setpts=expr=PTS-STARTPTS[vout]",
      ).joinToString(";")
  }

  @Test
  fun `a composition fragment with a crop works on the region and lays it back where it was cut`() {
    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(croppedOverlay()))

    graph.shouldContainInOrder(
      "split=2[vfx0base][vfx0crop]",
      "[vfx0crop]crop=w=400:h=200:x=100:y=50:exact=1,format=pix_fmts=gbrpf32le[vfx0pre]",
      "[1:v]setpts=expr=PTS-STARTPTS[vfx0a0]",
      "[vfx0pre][vfx0a0]overlay=x=0:y=0,format=pix_fmts=yuv420p[vfx0fx]",
      "[vfx0base][vfx0fx]overlay=x=100:y=50:format=auto[vfx0]",
    )
  }

  @Test
  fun `a clip fragment with a crop flushes what is waiting onto the pad it splits`() {
    val graph = invocationFor(fill = Fill.Solid(0), clipEffects = listOf(croppedOverlay())).filterGraph

    graph.shouldContainInOrder(
      "[0:v]trim=start=0.000000:end=2.000000,setpts=expr=PTS-STARTPTS[v0m0in]",
      "[v0m0in]split=2[v0m0base][v0m0crop]",
      "[v0m0crop]crop=w=400:h=200:x=100:y=50:exact=1,format=pix_fmts=gbrpf32le[v0m0pre]",
      "[1:v]setpts=expr=PTS-STARTPTS[v0m0a0]",
      "[v0m0pre][v0m0a0]overlay=x=0:y=0,format=pix_fmts=yuv420p[v0m0fx]",
      "[v0m0base][v0m0fx]overlay=x=100:y=50:format=auto[v0m0]",
      "[v0m0]scale=",
    )
  }

  @Test
  fun `the region a fragment crops is smaller than the frame`() {
    val output = Size(1920, 1080)

    val graph = graphFor(fill = Fill.Solid(0), output = output, compositionEffects = listOf(croppedOverlay()))

    val (width, height) = checkNotNull(Regex("crop=w=(\\d+):h=(\\d+)").find(graph)).destructured
    withClue("crop ${width}x$height") { (width.toInt() < output.width && height.toInt() < output.height) shouldBe true }
  }

  @Test
  fun `a crop naming no position is laid back centred`() {
    val crop = FilterCrop(width = "64", height = "64")
    val centred = fragmentEffect(FilterFragment(chain = listOf(FilterNode("negate")), crop = crop))

    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(centred))

    graph shouldContain
      "[vfx0crop]crop=w=64:h=64:x=2*floor(((in_w-out_w)/2)/2):y=2*floor(((in_h-out_h)/2)/2):exact=1,negate[vfx0fx]"
    graph shouldContain
      "[vfx0base][vfx0fx]overlay=x=2*floor(((W-w)/2)/2):y=2*floor(((H-h)/2)/2):format=auto[vfx0]"
  }

  @Test
  fun `a crop is made exact so it cuts where the recombine lays the region back`() {
    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(croppedOverlay()))

    graph shouldContain "[vfx0crop]crop=w=400:h=200:x=100:y=50:exact=1,"
    graph shouldContain "[vfx0base][vfx0fx]overlay=x=100:y=50:format=auto[vfx0]"
  }

  @Test
  fun `a crop that already says whether it is exact is left as given`() {
    val crop = FilterCrop(width = "64", height = "64", x = "8", y = "4", exact = false)
    val given = fragmentEffect(FilterFragment(chain = listOf(FilterNode("negate")), crop = crop))

    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(given))

    graph shouldContain "[vfx0crop]crop=w=64:h=64:x=8:y=4:exact=0,negate[vfx0fx]"
    graph shouldNotContain "exact=1"
  }

  @Test
  fun `an odd crop position is moved onto the even pixel both filters agree on`() {
    val crop = FilterCrop(width = "20", height = "20", x = "11", y = "13")
    val odd = fragmentEffect(FilterFragment(chain = listOf(FilterNode("negate")), crop = crop))
    val expression = FilterCrop(width = "20", height = "20", x = "7+t", y = "5")
    val computed = fragmentEffect(FilterFragment(chain = listOf(FilterNode("negate")), crop = expression))

    val graph = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(odd, computed))

    graph shouldContain "[vfx0crop]crop=w=20:h=20:x=10:y=12:exact=1,negate[vfx0fx]"
    graph shouldContain "[vfx0base][vfx0fx]overlay=x=10:y=12:format=auto[vfx0]"
    graph shouldContain "[vfx1crop]crop=w=20:h=20:x=2*floor((7+t)/2):y=4:exact=1,negate[vfx1fx]"
    graph shouldContain "[vfx1base][vfx1fx]overlay=x=2*floor((7+t)/2):y=4:format=auto[vfx1]"
  }

  @Test
  fun `post runs after the merge, or after the chain when there is none`() {
    val merging =
      fragmentEffect(
        FilterFragment(
          auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
          merge = FilterNode("overlay", "x" to "0", "y" to "0"),
          post = listOf(FilterNode("format", "pix_fmts" to "yuv420p")),
        ),
      )
    val chained =
      fragmentEffect(FilterFragment(chain = listOf(FilterNode("negate")), post = listOf(FilterNode("hflip"))))

    val composition = graphFor(fill = Fill.Solid(0), compositionEffects = listOf(merging, chained))
    val clip = invocationFor(fill = Fill.Solid(0), clipEffects = listOf(chained, merging)).filterGraph

    composition shouldContain "[vfx0a0]overlay=x=0:y=0,format=pix_fmts=yuv420p[vfx0]"
    composition shouldContain "[vfx0]negate,hflip[vfx1]"
    clip shouldContain "setpts=expr=PTS-STARTPTS,negate,hflip[v0m0pre]"
    clip shouldContain "[v0m0pre][v0m0a0]overlay=x=0:y=0,format=pix_fmts=yuv420p[v0m0]"
  }

  private fun String.shouldContainInOrder(vararg parts: String) {
    var from = 0
    parts.forEach { part ->
      val at = indexOf(part, from)
      withClue("expected \"$part\" after index $from in $this") { (at >= 0) shouldBe true }
      from = at + part.length
    }
  }

  private fun croppedOverlay(): ResolvedEffect =
    fragmentEffect(
      FilterFragment(
        chain = listOf(FilterNode("format", "pix_fmts" to "gbrpf32le")),
        auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
        merge = FilterNode("overlay", "x" to "0", "y" to "0"),
        crop = FilterCrop(width = "400", height = "200", x = "100", y = "50"),
        post = listOf(FilterNode("format", "pix_fmts" to "yuv420p")),
      ),
    )

  private fun fragmentEffect(fragment: FilterFragment): ResolvedEffect =
    ResolvedEffect(specId = "test.fragment", effect = PlatformEffect(fragment), stage = EffectStage.Composite)

  private fun chainedOverlay(): ResolvedEffect =
    ResolvedEffect(
      specId = "test.chained",
      effect =
        PlatformEffect(
          FilterFragment(
            chain = listOf(FilterNode("eq", "contrast" to "1.2")),
            auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
            merge = FilterNode("overlay", "x" to "0", "y" to "0"),
          ),
        ),
      stage = EffectStage.Composite,
    )

  private fun compositionEffect(): ResolvedEffect =
    ResolvedEffect(
      specId = "test.brightness",
      effect = PlatformEffect(FilterFragment(chain = listOf(FilterNode("eq", "brightness" to "0.1")))),
      stage = EffectStage.Color,
    )

  private fun logoOverlay(): ResolvedEffect =
    ResolvedEffect(
      specId = "test.overlay",
      effect =
        PlatformEffect(
          FilterFragment(
            auxInputs = listOf(AuxInput(ImageSource.of("/logo.png"))),
            merge = FilterNode("overlay", "x" to "0", "y" to "0"),
          ),
        ),
      stage = EffectStage.Composite,
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
      stage = EffectStage.Color,
    )

  private fun graphFor(
    fill: Fill,
    fit: Fit = Fit.Contain,
    output: Size = Size(1920, 1080),
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    hdrTransfer: HdrTransfer? = null,
    passes: Int = 1,
  ): String =
    invocationFor(fill, fit, output, trackStart, compositionEffects, hdrTransfer = hdrTransfer, passes = passes)
      .filterGraph

  private fun invocationFor(
    fill: Fill,
    fit: Fit = Fit.Contain,
    output: Size = Size(1920, 1080),
    trackStart: Duration = Duration.ZERO,
    compositionEffects: List<ResolvedEffect> = emptyList(),
    clipEffects: List<ResolvedEffect> = emptyList(),
    hdrTransfer: HdrTransfer? = null,
    passes: Int = 1,
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
    val clips =
      List(passes) { pass ->
        val opens = trackStart + duration * pass
        ResolvedClip(
          source = source,
          info = info,
          start = Duration.ZERO,
          end = duration,
          effects = clipEffects,
          gain = ResolvedGain.constant(1f, Duration.ZERO, duration),
          startsAtKeyFrame = false,
          span = TimeRange.of(opens, opens + duration),
          sourceIndex = pass,
        )
      }
    val track = ResolvedTrack(content = TrackContent.Video, looping = false, start = trackStart, clips = clips)
    val negotiated =
      ResolvedComposition(
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
        hdrTransfer = hdrTransfer,
        path = ExportPath.Transcode,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        encoderName = "libx264",
      )

    return GraphLowering(negotiated, hdrPixelFormat = hdrTransfer?.let { "yuv420p10le" }).build()
  }

  /**
   * A clip windowed inside a longer source, so neither bound sits on an end the lowering could hit
   * by ignoring one of them.
   */
  private fun trimmedClip(
    start: Duration = TRIM_START,
    end: Duration = TRIM_END,
  ): ResolvedClip =
    ResolvedClip(
      source = source,
      info =
        MediaInfo(
          duration = SOURCE_LENGTH,
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
        ),
      start = start,
      end = end,
      effects = emptyList(),
      gain = ResolvedGain.constant(1f, Duration.ZERO, end - start),
      startsAtKeyFrame = true,
      span = TimeRange.of(Duration.ZERO, end - start),
      sourceIndex = 0,
    )

  private fun copyInvocationFor(clip: ResolvedClip): Invocation {
    val output = Size(1920, 1080)
    val negotiated =
      ResolvedComposition(
        tracks =
          listOf(
            ResolvedTrack(
              content = TrackContent.AudioAndVideo,
              looping = false,
              start = Duration.ZERO,
              clips = listOf(clip),
            ),
          ),
        compositionGeometry = emptyList(),
        compositionInputSize = output,
        compositionEffects = emptyList(),
        output =
          OutputFormat(
            size = output,
            videoCodec = VideoCodec.H264,
            audioCodec = AudioCodec.Aac,
            bitrate = null,
            frameRate = 30,
            audioFormat = null,
          ),
        layoutSize = output,
        fit = Fit.Contain,
        fill = Fill.Black,
        duration = clip.duration,
        hdr = ResolvedHdr.Keep,
        hdrTransfer = null,
        path = ExportPath.Copy,
        audio = AudioSpec.Keep,
        adjustments = emptyList(),
        encoderName = null,
      )

    return GraphLowering(negotiated, hdrPixelFormat = null).build()
  }
}

// A source long enough to hold a window clear of both its ends, and a window that sits there.
private val SOURCE_LENGTH = 12.seconds
private val TRIM_START = 4_500.milliseconds
private val TRIM_END = 7_250.milliseconds

// Two windows of a looping run and the run they cover. No two of them divide evenly, so the run
// lays A B A B and then cuts a fifth pass short, and a pass counted the wrong way round lands
// somewhere the assertions can see.
private val A_START = 200.milliseconds
private val A_LENGTH = 1_100.milliseconds
private val B_START = 1_400.milliseconds
private val B_LENGTH = 900.milliseconds
private val RUN = 4_800.milliseconds

// An audio-only track opening inside the loop's first pass and running past the loop's end.
private val UNDERLAY_START = 700.milliseconds
private val UNDERLAY_LENGTH = 4_600.milliseconds

// The output rate the grid tests lay clips on, and lengths and starts placed against its 40 ms slot:
// 1.010 s is a quarter of a slot past a whole number of them, 10 ms covers no half way point, 20 ms
// is exactly half a slot and 19 ms is just short of it.
private const val GRID_RATE = 25
private val OFF_GRID_LENGTH = 1_010.milliseconds
private val UNDER_HALF_SLOT = 10.milliseconds
private val HALF_SLOT_START = 20.milliseconds
private val SHORT_START = 19.milliseconds

private val TOOLCHAIN =
  Toolchain(
    ffmpeg = "ffmpeg",
    ffprobe = "ffprobe",
    version = FfmpegVersion(banner = "ffmpeg version 9.0.1", major = 9, minor = 0),
    filters = emptySet(),
    encoders = setOf("libx264"),
  )

// gblur's own ceiling on sigma, a literal here on purpose: it pins what ffmpeg accepts rather than
// anything the shared contract says.
private const val GBLUR_SIGMA_CEILING = 1024

// The header of the smallest table this backend writes, standing in for the file an effect brings.
private const val CUBE = "LUT_3D_SIZE 2"
