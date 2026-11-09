package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.FilmstripError
import dev.jordond.filmstrip.capability.DeviceCapabilities
import dev.jordond.filmstrip.capability.EffectParity
import dev.jordond.filmstrip.codec.VideoCodec
import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.TimeRange
import dev.jordond.filmstrip.effect.Attributes
import dev.jordond.filmstrip.effect.EffectResolution
import dev.jordond.filmstrip.effect.EffectResolver
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effect.RenderApi
import dev.jordond.filmstrip.effect.RenderCapabilities
import dev.jordond.filmstrip.export.CopyBlocker
import dev.jordond.filmstrip.export.ExportPath
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.ExportStatus
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.media.ColorSpace
import dev.jordond.filmstrip.media.MediaInfo
import dev.jordond.filmstrip.media.MediaProber
import dev.jordond.filmstrip.media.MediaSink
import dev.jordond.filmstrip.media.MediaSource
import dev.jordond.filmstrip.media.ProbeResult
import dev.jordond.filmstrip.media.describe
import dev.jordond.filmstrip.transform.internal.copy.CopyPacket
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketRead
import dev.jordond.filmstrip.transform.internal.copy.CopyPacketWindow
import dev.jordond.filmstrip.transform.internal.copy.CopyPrefix
import dev.jordond.filmstrip.transform.internal.copy.copyPrefixOf
import dev.jordond.filmstrip.transform.internal.probe.OpeningRead
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedComposition
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.nulls.shouldNotBeNull
import io.kotest.matchers.shouldBe
import io.kotest.matchers.types.shouldBeInstanceOf
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.flow.toList
import kotlinx.coroutines.test.runTest
import kotlin.test.Test
import kotlin.time.Duration
import kotlin.time.Duration.Companion.milliseconds
import kotlin.time.Duration.Companion.seconds

// What a driver answers is all PlannedExportEngine knows about the platform, so these pin how each
// answer reaches the plan and how often the engine has to ask.
class ExportDriverTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a driver that keeps the default names no sync sample and the trim costs the copy`() =
    runTest {
      val trimmed = composition(fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds)))
      val driver = DefaultSyncDriver(fixture.device())

      val source =
        trimmed.tracks
          .single()
          .clips
          .single()
          .source
      driver.syncSampleAtOrBefore(source, 1.seconds).shouldBeInstanceOf<OpeningRead.At>().opening shouldBe null

      val plan = engine(driver, trimmed).plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      plan.plan.path shouldBe ExportPath.Transcode
      plan.plan.copyBlockedBy shouldBe listOf(CopyBlocker.TrimNotOnSyncSample)
    }

  @Test
  fun `a sync sample the driver names lets a trimmed clip copy from there`() =
    runTest {
      val clip =
        fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 500.milliseconds)
      val trimmed = composition(clip)
      val driver = RecordingDriver(fixture.device(), opening = 750.milliseconds)
      val engine = engine(driver, trimmed)

      val plan = engine.plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      val resolved = engine.resolve(trimmed, SPEC).shouldBeInstanceOf<ResolveResult.Resolved>()

      plan.plan.path shouldBe ExportPath.Copy
      plan.plan.copyBlockedBy.shouldBeEmpty()
      firstResolvedClip(resolved.composition).start shouldBe 750.milliseconds
      driver.asked shouldBe listOf(clip.source to 1_000.milliseconds)
    }

  @Test
  fun `the engine reads the device once however often it negotiates`() =
    runTest {
      val edit = composition(fixture.clip())
      val driver = RecordingDriver(fixture.device(), opening = null)
      val engine = engine(driver, edit)

      engine.capabilities()
      val plan = engine.plan(edit, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      engine.export(plan.plan, SINK).toList()

      driver.capabilityReads shouldBe 1
    }

  // An untrimmed clip opens at zero, so asking the driver would cost a probe for an answer the
  // planner already has.
  @Test
  fun `an untrimmed clip never asks the driver for a sync sample`() =
    runTest {
      val edit = composition(fixture.clip())
      val driver = RecordingDriver(fixture.device(), opening = Duration.ZERO)

      engine(driver, edit).plan(edit, SPEC)

      driver.asked.shouldBeEmpty()
    }

  @Test
  fun `an export hands the driver the lowered edit and passes its statuses through`() =
    runTest {
      val edit = composition(fixture.clip())
      val driver = RecordingDriver(fixture.device(), opening = null)
      val engine = engine(driver, edit)
      val plan = engine.plan(edit, SPEC).shouldBeInstanceOf<Verdict.Capable>()

      val statuses = engine.export(plan.plan, SINK).toList()

      statuses shouldBe listOf(ExportStatus.Started)
      driver.exported.single().second shouldBe SINK
      driver.exported
        .single()
        .first.path shouldBe ExportPath.Copy
      driver.exported
        .single()
        .first.duration shouldBe plan.plan.duration
    }

  @Test
  fun `every resolver prepares for the edit before each negotiation of it`() =
    runTest {
      val edit = composition(fixture.clip())
      val preparing = PreparingResolver()
      val engine = engine(RecordingDriver(fixture.device(), opening = null), edit, preparing)

      val plan = engine.plan(edit, SPEC).shouldBeInstanceOf<Verdict.Capable>()
      engine.resolve(edit, SPEC)
      engine.export(plan.plan, SINK).toList()

      preparing.prepared shouldBe listOf(edit, edit, edit)
    }

  @Test
  fun `the engine reports the parity it was built with and nothing for an unknown id`() {
    val driver = RecordingDriver(fixture.device(), opening = null)
    val engine = engine(driver, composition(fixture.clip()))

    engine.parityOf(KNOWN_ID) shouldBe EffectParity.Exact
    engine.parityOf("dev.jordond.filmstrip.unknown") shouldBe null
  }

  // The packets are read before negotiating, so a plan, not the export after it, shows the copy is lost.
  @Test
  fun `packets the driver lists with no times move the plan off the copy`() =
    runTest {
      val trimmed = composition(packetClip())
      val untimed = packets().map { CopyPacket(Duration.INFINITE, it.key) }
      val driver = PacketDriver(fixture.device(), CopyPacketRead.Read(untimed))

      val plan = engine(driver, trimmed).plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Capable>()

      plan.plan.path shouldBe ExportPath.Transcode
      plan.plan.copyBlockedBy shouldBe listOf(CopyBlocker.SourceUntimed)
      driver.windows.single().from shouldBe 1_000.milliseconds
      driver.windows.single().to shouldBe 3_000.milliseconds
    }

  @Test
  fun `packets the driver cannot list refuse the plan as an unreadable source`() =
    runTest {
      val clip = packetClip()
      val trimmed = composition(clip)
      val driver = PacketDriver(fixture.device(), CopyPacketRead.Failed(UNLISTED))

      val verdict = engine(driver, trimmed).plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Incapable>()

      verdict.reasons.single() shouldBe FilmstripError.SourceUnreadable(clip.source.describe(), UNLISTED)
    }

  @Test
  fun `a packet read stopped at its deadline refuses the plan as timed out`() =
    runTest {
      val trimmed = composition(packetClip())
      val driver = PacketDriver(fixture.device(), CopyPacketRead.Expired(PACKET_READ, 42.seconds, EXPIRED))

      val verdict = engine(driver, trimmed).plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Incapable>()

      verdict.reasons.single() shouldBe FilmstripError.TimedOut(PACKET_READ, 42.seconds, EXPIRED)
    }

  @Test
  fun `the driver exports the count the plan settled on its packets`() =
    runTest {
      val trimmed = composition(packetClip())
      val packets = packets()
      val driver = PacketDriver(fixture.device(), CopyPacketRead.Read(packets))
      val engine = engine(driver, trimmed)
      val plan = engine.plan(trimmed, SPEC).shouldBeInstanceOf<Verdict.Capable>()

      engine.export(plan.plan, SINK).toList()

      val exported = driver.exported.single().first
      val expected = exported.copyPrefixOf(packets).shouldBeInstanceOf<CopyPrefix.Packets>()
      exported.copyPrefix.shouldNotBeNull().count shouldBe expected.count
    }

  @Test
  fun `the grade an export resolves to reads the packets too`() =
    runTest {
      val trimmed = composition(packetClip())
      val driver = PacketDriver(fixture.device(), CopyPacketRead.Failed(UNLISTED))

      val grade = engine(driver, trimmed).exportGrade(trimmed, SPEC).shouldBeInstanceOf<ExportGrade.Refused>()

      grade.error.shouldBeInstanceOf<FilmstripError.SourceUnreadable>().message shouldBe UNLISTED
    }

  private fun packetClip() =
    fixture.clip(trim = TimeRange.of(1_000.milliseconds, 3_000.milliseconds), snapWithin = 500.milliseconds)

  private fun packets(): List<CopyPacket> =
    (0 until 90).map { CopyPacket(1_000.milliseconds + it.seconds / 30, key = it == 0) }

  private fun engine(
    driver: ExportDriver,
    composition: Composition,
    vararg extra: EffectResolver,
  ) = PlannedExportEngine(
    backend = driver,
    prober = MapProber(fixture.infos(composition)),
    resolvers = listOf(FakeResolver()) + extra,
    parity = mapOf(KNOWN_ID to EffectParity.Exact),
    ladder = listOf(VideoCodec.H264, VideoCodec.Hevc),
    supportsCopy = true,
    muxerAccepts = { true },
  )
}

/**
 * A resolver that claims nothing and records every composition it was asked to prepare for.
 */
private class PreparingResolver : EffectResolver {
  val prepared = mutableListOf<Composition>()

  override fun resolve(
    spec: EffectSpec,
    capabilities: RenderCapabilities,
    attributes: Attributes,
  ): EffectResolution? = null

  override suspend fun prepare(composition: Composition) {
    prepared += composition
  }
}

/**
 * A driver that keeps every default it is allowed to, so a test can read what the defaults do.
 */
private open class DefaultSyncDriver(
  private val device: DeviceCapabilities,
) : ExportDriver {
  var capabilityReads: Int = 0
    private set

  val exported = mutableListOf<Pair<ResolvedComposition, MediaSink>>()

  override suspend fun capabilities(): DeviceCapabilities {
    capabilityReads++
    return device
  }

  override fun renderCapabilities(
    outputSize: Size,
    hdr: Boolean,
  ): RenderCapabilities =
    RenderCapabilities(
      api = RenderApi.OpenGlEs,
      supportsFragmentShader = true,
      supportsComputeShader = false,
      supportsHdr = hdr,
      colorSpaces = setOf(ColorSpace.Bt709),
      maxTextureSize = maxOf(outputSize.width, outputSize.height),
      features = emptySet(),
    )

  override fun unclaimed(specId: String): String = "nothing claimed $specId"

  override fun export(
    resolved: ResolvedComposition,
    to: MediaSink,
  ): Flow<ExportStatus> {
    exported += resolved to to
    return flowOf(ExportStatus.Started)
  }
}

/**
 * A driver that names [opening] as the sync sample before every cut and records each cut it was asked about.
 */
private class RecordingDriver(
  device: DeviceCapabilities,
  private val opening: Duration?,
) : DefaultSyncDriver(device) {
  val asked = mutableListOf<Pair<MediaSource, Duration>>()

  override suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): OpeningRead {
    asked += source to cut
    return OpeningRead.At(opening)
  }
}

/**
 * A driver that names every cut as its own sync sample and answers each packet read with [read], recording the
 * windows it was asked to read.
 */
private class PacketDriver(
  device: DeviceCapabilities,
  private val read: CopyPacketRead,
) : DefaultSyncDriver(device) {
  val windows = mutableListOf<CopyPacketWindow>()

  override suspend fun syncSampleAtOrBefore(
    source: MediaSource,
    cut: Duration,
  ): OpeningRead = OpeningRead.At(cut)

  override suspend fun copyPackets(window: CopyPacketWindow): CopyPacketRead {
    windows += window
    return read
  }
}

private class MapProber(
  private val infos: Map<MediaSource, MediaInfo>,
) : MediaProber {
  override suspend fun probe(source: MediaSource): ProbeResult = ProbeResult.Success(infos.getValue(source))
}

private fun firstResolvedClip(resolved: ResolvedComposition) =
  resolved.tracks
    .first()
    .clips
    .first()

private val SPEC = ExportSpec()

private const val UNLISTED = "the packets could not be listed"
private const val PACKET_READ = "copy packet read"
private const val EXPIRED = "the packet read was stopped"

private val SINK = MediaSink.of("/out/driver.mp4")

private const val KNOWN_ID = "dev.jordond.filmstrip.known"
