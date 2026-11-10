package dev.jordond.filmstrip.convention.testmedia

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.file.RegularFileProperty
import org.gradle.api.provider.ListProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.OutputFile
import org.gradle.api.tasks.TaskAction
import org.gradle.process.ExecOperations
import java.io.ByteArrayOutputStream
import java.io.File
import javax.inject.Inject

/**
 * Encodes the test fixtures with host ffmpeg, then verifies each one with ffprobe.
 *
 * Each row in [manifest] ends with the SHA-256 of the file it describes, taken after the file passed verification. The
 * publish task reads it to tell a freshly generated fixture from one that changed since.
 *
 * Every ffmpeg pass runs on one thread and on libav's C code paths, with no copied metadata, see [DETERMINISTIC_ARGS]
 * and [FFMPEG]. libx264 runs without its assembly and libx265 leaves its host details out of the stream. So the same
 * spec on the same ffmpeg, libx264 and libx265 for one architecture gives the same bytes at any core count and CPU
 * tier. That build is the [build] input, so a different one runs the task again instead of leaving old bytes under a
 * new header. The list opens with it, and the publish task compares it against the pinned one.
 *
 * ffmpeg is host tooling only. Nothing it produces is linked into the library. The fixtures are
 * input bytes, the same as a checked-in sample clip would be.
 *
 * The verification pass is the point of the task doing more than shelling out: ffmpeg falls back
 * silently when a codec cannot honour a request, and a fixture whose sample rate is not the one
 * under test would invalidate the audio-boundary results without ever failing a build.
 */
abstract class GenerateTestMediaTask
  @Inject
  constructor(
    private val exec: ExecOperations,
  ) : DefaultTask() {
    @get:Input
    abstract val specs: ListProperty<FixtureSpec>

    /**
     * The encoder build the host would use now, normally read by [FixtureBuildSource]. Gradle reads it again on every
     * up-to-date check.
     */
    @get:Input
    abstract val build: Property<String>

    @get:OutputDirectory
    abstract val outputDirectory: DirectoryProperty

    @get:OutputFile
    abstract val manifest: RegularFileProperty

    @TaskAction
    fun generate() {
      val outputDir = outputDirectory.get().asFile
      outputDir.mkdirs()

      val rows = mutableListOf<String>()

      specs.get().forEach { spec ->
        val target = outputDir.resolve(spec.fileName)
        target.delete()
        rows += "${encode(spec, target)} ${TestMediaManifest.digestOf(target)}"
      }

      val text =
        buildString {
          appendLine("# filmstrip test fixtures")
          appendLine(TestMediaManifest.buildHeader(build.get()))
          appendLine("# name width height fps frames duration bitrateBps sampleRate channels bytes sha256")
          rows.forEach { appendLine(it) }
        }

      manifest
        .get()
        .asFile
        .also { it.parentFile.mkdirs() }
        .writeText(text)
      outputDir.resolve("fixtures.txt").writeText(text)
    }

    private fun encode(
      spec: FixtureSpec,
      target: File,
    ): String {
      exec.exec {
        commandLine(ffmpegArgs(spec, target.absolutePath))
      }
      if (spec.rotationDegrees != 0) rotate(spec, target)
      return verify(spec, target.absolutePath)
    }

    internal fun ffmpegArgs(
      spec: FixtureSpec,
      target: String,
    ): List<String> =
      buildList {
        addAll(FFMPEG)
        addAll(
          listOf(
            "-f",
            "lavfi",
            "-i",
            "testsrc=size=${spec.width}x${spec.height}:rate=${spec.frameRate}:duration=${spec.durationSeconds}",
          ),
        )
        spec.audioTracks.forEach { track ->
          addAll(
            listOf(
              "-f",
              "lavfi",
              "-i",
              spec.clickSeconds?.let { clickSource(spec, it) }
                ?: "sine=frequency=${track.toneHz}:sample_rate=${spec.sampleRate}:duration=${spec.durationSeconds}",
            ),
          )
        }
        // Seeded noise, so the clips carry camera-like entropy instead of testsrc's ~0.5 Mbps.
        // Without it the decoder feeding the effect pipeline does almost no work and the realtime
        // benchmark would understate the total per-frame cost. The seed keeps it reproducible.
        val filters =
          buildList {
            if (spec.hue != 0) add("hue=h=${spec.hue}")
            add("noise=alls=14:allf=t+u:all_seed=20260819")
            // After the noise, so the patch is flat. That is the whole point of it: a test that
            // predicts what an operation did to one pixel cannot read a pixel whose neighbours
            // disagree with it.
            spec.patch?.let { patch ->
              add(
                "drawbox=x=(iw-${patch.size})/2:y=(ih-${patch.size})/2:" +
                  "w=${patch.size}:h=${patch.size}:color=${patch.hex}:t=fill",
              )
            }
          }
        addAll(listOf("-filter:v", filters.joinToString(",")))
        addAll(spec.transfer?.let { hdrVideoArgs(it) } ?: sdrVideoArgs())
        addAll(
          listOf(
            "-preset",
            "medium",
            "-b:v",
            "${spec.bitrateKbps}k",
            "-maxrate",
            "${(spec.bitrateKbps * 3) / 2}k",
            "-bufsize",
            "${spec.bitrateKbps * 2}k",
            "-g",
            spec.frameRate.toString(),
            "-keyint_min",
            spec.frameRate.toString(),
            "-c:a",
            "aac",
            "-b:a",
            "128k",
            "-ar",
            spec.sampleRate.toString(),
            "-ac",
            spec.channelCount.toString(),
            "-shortest",
          ),
        )
        if (spec.container == FixtureContainer.Mp4) addAll(listOf("-movflags", "+faststart"))
        addAll(audioTrackArgs(spec))
        addAll(DETERMINISTIC_ARGS)
        add(target)
      }

    /**
     * Maps every audio input of a fixture with more than one audio track to its own stream, with its language and
     * whether it is the default.
     *
     * A single track is left to ffmpeg's own stream selection, so a clip that always had one encodes as it always did.
     */
    internal fun audioTrackArgs(spec: FixtureSpec): List<String> =
      buildList {
        if (spec.audioTracks.size == 1 && spec.audioTracks.single().language == null) return@buildList
        addAll(listOf("-map", "0:v"))
        spec.audioTracks.indices.forEach { addAll(listOf("-map", "${it + 1}:a")) }
        spec.audioTracks.forEachIndexed { index, track ->
          track.language?.let { addAll(listOf("-metadata:s:a:$index", "language=$it")) }
          addAll(listOf("-disposition:a:$index", if (track.isDefault) "default" else "0"))
        }
      }

    /**
     * Silence with one click at [seconds], on the nearest sample to it, at [CLICK_LEVEL].
     *
     * The click stays under full scale so the AAC encoder has room for the ringing it adds around it.
     */
    internal fun clickSource(
      spec: FixtureSpec,
      seconds: Double,
    ): String {
      val sample = Math.round(seconds * spec.sampleRate)
      return "aevalsrc=exprs=if(eq(n\\,$sample)\\,$CLICK_LEVEL\\,0):sample_rate=${spec.sampleRate}:" +
        "duration=${spec.durationSeconds}"
    }

    // -cpuflags does not reach libx264, which detects the CPU itself and encodes other frames on an AVX2 host.
    private fun sdrVideoArgs(): List<String> =
      listOf("-c:v", "libx264", "-pix_fmt", "yuv420p", "-x264-params", "threads=1:lookahead-threads=1:asm=0")

    /**
     * Rewrites the container with a display matrix, leaving the encoded frames alone.
     *
     * Rotation cannot ride on the encode pass, since it is a container field rather than anything
     * the codec sees. The stream copy is what puts it there.
     */
    private fun rotate(
      spec: FixtureSpec,
      target: File,
    ) {
      val rotated = target.resolveSibling("${spec.name}-rotating.mp4")
      rotated.delete()
      exec.exec {
        commandLine(rotateArgs(spec, target.absolutePath, rotated.absolutePath))
      }
      if (!target.delete() || !rotated.renameTo(target)) {
        throw GradleException("could not replace ${spec.fileName} with its rotated copy")
      }
    }

    internal fun rotateArgs(
      spec: FixtureSpec,
      source: String,
      target: String,
    ): List<String> =
      FFMPEG +
        listOf(
          "-display_rotation",
          spec.rotationDegrees.toString(),
          "-i",
          source,
          "-c",
          "copy",
          "-movflags",
          "+faststart",
        ) +
        DETERMINISTIC_ARGS +
        target

    /**
     * Ten-bit HEVC in BT.2020, tagged in the container and in the bitstream.
     *
     * The x265 parameters tag the bitstream and the ffmpeg flags tag the container. MediaExtractor
     * reads one and media3's decoder reads the other, so both have to say the same thing.
     *
     * `hdr10` is set only for PQ, since the static metadata it writes is defined against an
     * absolute signal and HLG carries none. `info=0` drops x265's SEI, which holds the host's SIMD
     * mask.
     */
    private fun hdrVideoArgs(transfer: FixtureTransfer): List<String> {
      val x265 =
        buildList {
          add("colorprim=bt2020")
          add("transfer=${transfer.tag}")
          add("colormatrix=bt2020nc")
          if (transfer == FixtureTransfer.Pq) add("hdr10=1")
          add("repeat-headers=1")
          add("pools=1")
          add("frame-threads=1")
          add("info=0")
        }

      return listOf(
        "-c:v",
        "libx265",
        "-pix_fmt",
        "yuv420p10le",
        "-color_primaries",
        "bt2020",
        "-color_trc",
        transfer.tag,
        "-colorspace",
        "bt2020nc",
        "-x265-params",
        x265.joinToString(":"),
        "-tag:v",
        "hvc1",
      )
    }

    /**
     * Reads back what was actually written and fails on any drift from [spec].
     */
    private fun verify(
      spec: FixtureSpec,
      target: String,
    ): String {
      val probe =
        exec.capture(
          listOf(
            "ffprobe",
            "-v",
            "error",
            "-count_frames",
            "-show_entries",
            "stream=codec_type,width,height,avg_frame_rate,nb_read_frames,bit_rate,sample_rate,channels," +
              "pix_fmt,color_transfer,color_primaries:stream_tags=language:stream_disposition=default",
            "-show_entries",
            "stream_side_data=rotation",
            "-show_entries",
            "format=duration,size",
            "-of",
            "default=noprint_wrappers=1",
            target,
          ),
        )

      val fields =
        probe
          .lineSequence()
          .mapNotNull { line -> line.split("=", limit = 2).takeIf { it.size == 2 } }
          .groupBy({ it[0].trim() }, { it[1].trim() })

      fun single(
        key: String,
        index: Int = 0,
      ): String = fields[key]?.getOrNull(index) ?: error("ffprobe reported no $key for ${spec.fileName}")

      val width = single("width").toInt()
      val height = single("height").toInt()
      val frameRate = single("avg_frame_rate").split("/").let { it[0].toDouble() / it[1].toDouble() }
      val sampleRate = single("sample_rate").toInt()
      val channels = single("channels").toInt()
      val duration = single("duration").toDouble()
      val bytes = single("size").toLong()
      val frames = single("nb_read_frames").toInt()
      // Matroska keeps no bitrate per stream, so a clip written in it records none.
      val videoBitrate = fields["bit_rate"]?.firstOrNull()?.toLongOrNull() ?: 0L

      val drift =
        buildList {
          if (width != spec.width || height != spec.height) add("size ${width}x$height != ${spec.width}x${spec.height}")
          if (kotlin.math.abs(frameRate - spec.frameRate) > 0.01) add("fps $frameRate != ${spec.frameRate}")
          if (sampleRate != spec.sampleRate) add("sampleRate $sampleRate != ${spec.sampleRate}")
          if (channels != spec.channelCount) add("channels $channels != ${spec.channelCount}")
          if (kotlin.math.abs(duration - spec.durationSeconds) >
            0.25
          ) {
            add("duration $duration != ${spec.durationSeconds}")
          }
          // A transport stream lists its streams under its program as well, ahead of the streams themselves, and only
          // the streams carry a disposition, so the count is read off the last of them.
          val streams = fields["DISPOSITION:default"].orEmpty().size
          val audioCount = fields["codec_type"].orEmpty().takeLast(streams).count { it == "audio" }
          if (audioCount != spec.audioTracks.size) add("audio tracks $audioCount != ${spec.audioTracks.size}")
          if (spec.audioTracks.size > 1) {
            // The video stream comes first and writes its own default and language, so the audio ones follow it.
            val languages = fields["TAG:language"].orEmpty().takeLast(spec.audioTracks.size)
            val defaults = fields["DISPOSITION:default"].orEmpty().takeLast(spec.audioTracks.size)
            val askedLanguages = spec.audioTracks.map { it.language ?: "und" }
            val askedDefaults = spec.audioTracks.map { if (it.isDefault) "1" else "0" }
            if (languages != askedLanguages) add("languages $languages != $askedLanguages")
            if (defaults != askedDefaults) add("defaults $defaults != $askedDefaults")
          }
          if (spec.rotationDegrees != 0) {
            val rotation = fields["rotation"]?.firstOrNull()?.toIntOrNull()
            if (rotation != spec.rotationDegrees) add("rotation $rotation != ${spec.rotationDegrees}")
          }
          spec.transfer?.let { asked ->
            val pixelFormat = single("pix_fmt")
            val transfer = single("color_transfer")
            val primaries = single("color_primaries")
            if (pixelFormat != "yuv420p10le") add("pix_fmt $pixelFormat is not ten bit")
            if (transfer != asked.tag) add("color_trc $transfer is not ${asked.tag}")
            if (primaries != "bt2020") add("color_primaries $primaries is not BT.2020")
          }
        }

      if (drift.isNotEmpty()) {
        throw GradleException("${spec.fileName} does not match its spec: ${drift.joinToString("; ")}")
      }

      return listOf(
        spec.name,
        width,
        height,
        frameRate.toInt(),
        frames,
        duration,
        videoBitrate,
        sampleRate,
        channels,
        bytes,
      ).joinToString(" ")
    }
  }

internal fun ExecOperations.capture(command: List<String>): String {
  val stdout = ByteArrayOutputStream()
  exec {
    commandLine(command)
    standardOutput = stdout
  }
  return stdout.toString(Charsets.UTF_8)
}

/**
 * One frame from [encoder] written to stdout as a raw [format] stream, whose SEI names that encoder's build.
 *
 * [executable] is the ffmpeg that runs it, bare `ffmpeg` from the PATH unless a caller names one.
 */
internal fun encoderProbeArgs(
  encoder: String,
  pixelFormat: String,
  format: String,
  executable: String = "ffmpeg",
): List<String> =
  listOf(executable) +
    FFMPEG.drop(1) +
    listOf(
      "-f",
      "lavfi",
      "-i",
      "color=black:size=64x64:rate=1",
      "-frames:v",
      "1",
      "-c:v",
      encoder,
      "-pix_fmt",
      pixelFormat,
      "-f",
      format,
      "-",
    )

/**
 * What every ffmpeg pass opens with. `-cpuflags 0` keeps libav on its C code paths, since its SIMD tiers encode other
 * AAC and build other 8-bit frames.
 */
private val FFMPEG =
  listOf("ffmpeg", "-y", "-hide_banner", "-loglevel", "error", "-nostdin", "-cpuflags", "0", "-filter_threads", "1")

/**
 * Output options every ffmpeg pass ends with, so a fixture's bytes do not depend on the core count.
 *
 * One encoder thread keeps x264's rate control from depending on the core count, and the x264 and x265 parameters pin
 * their lookahead and pool threads the same way. Bitexact drops libav's own version strings from the container and the
 * audio stream, and no metadata is copied from the inputs. x264's SEI still names its core version, which is why the
 * build record names libx264.
 */
internal val DETERMINISTIC_ARGS: List<String> =
  listOf(
    "-threads",
    "1",
    "-fflags",
    "+bitexact",
    "-flags:v",
    "+bitexact",
    "-flags:a",
    "+bitexact",
    "-map_metadata",
    "-1",
  )

// Where a fixture's click peaks, as a fraction of full scale.
private const val CLICK_LEVEL = 0.5
