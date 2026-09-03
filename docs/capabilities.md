# Capabilities

filmstrip is pre-alpha, so here is what works today.

Legend:

| Marker  | Meaning                                                                                  |
|---------|------------------------------------------------------------------------------------------|
| yes     | Implemented.                                                                             |
| partial | Works, with a documented limit. See the footnote.                                        |
| no      | Not implemented. Fails or refuses by name, never silently.                               |
| pending | Declared in the API, and the backend returns a typed refusal explaining what is missing. |

Nothing in filmstrip fails silently. An unsupported effect comes back as
`EffectResolution.Unsupported` with a message, and an unsupported composition comes back as
`Verdict.Incapable` with a list of `ExportError`s.

## Targets

Every published module targets the same list, except the four engine artifacts, each of which only
runs where its engine does.

| Artifact                           | Android | iOS (arm64, sim arm64) | macOS (arm64) | JVM | Browser (js, wasmJs) |
|------------------------------------|---------|------------------------|---------------|-----|----------------------|
| `filmstrip`                        | yes     | yes                    | yes           | yes | yes                  |
| `filmstrip-core`                   | yes     | yes                    | yes           | yes | yes                  |
| `filmstrip-effects`                | yes     | yes                    | yes           | yes | yes                  |
| `filmstrip-transform`              | yes     | yes                    | yes           | yes | yes                  |
| `filmstrip-transform-media3`       | yes     | no                     | no            | no  | no                   |
| `filmstrip-transform-avfoundation` | no      | yes                    | yes           | no  | no                   |
| `filmstrip-transform-webcodecs`    | no      | no                     | no            | no  | yes                  |
| `filmstrip-transform-ffmpeg`       | no      | no                     | no            | yes | no                   |
| `filmstrip-player`                 | yes     | yes                    | yes           | yes | yes                  |
| `filmstrip-compose`                | yes     | yes                    | no            | yes | yes                  |
| `filmstrip-test`                   | yes     | yes                    | yes           | yes | yes                  |

Notes:

- `filmstrip-transform-media3`, `-avfoundation` and `-webcodecs` are each named after the engine they
  wrap, and each is single-target for the same reason `-ffmpeg` is: media3-transformer only runs on
  Android, AVFoundation and VideoToolbox only run on Apple, and WebCodecs only runs in a browser.
- `filmstrip-transform-ffmpeg` is JVM-only by design. It shells out to an `ffmpeg` and an `ffprobe`
  that are already on the machine, so it has nothing to offer a target that cannot spawn a process.

## Backends

A backend is registered on the `Filmstrip` builder.

| Backend          | Artifact                           | Registration            | Runs on    | Built on                                                 |
|------------------|------------------------------------|-------------------------|------------|----------------------------------------------------------|
| media3           | `filmstrip-transform-media3`       | `media3Backend()`       | Android    | media3-transformer                                       |
| AVFoundation     | `filmstrip-transform-avfoundation` | `avFoundationBackend()` | Apple      | AVFoundation and VideoToolbox                            |
| WebCodecs        | `filmstrip-transform-webcodecs`    | `webCodecsBackend()`    | browser    | WebCodecs, mediabunny                                    |
| ffmpeg           | `filmstrip-transform-ffmpeg`       | `ffmpegBackend()`       | JVM        | An `ffmpeg` and `ffprobe` already on the machine         |
| Built-in effects | `filmstrip-effects`                | `builtInEffects()`      | everywhere | media3-effect, Core Image, WebGL 2, ffmpeg filter graphs |

`filmstrip` registers the right one of these for the current target through `transformBackend()`.
Registrations go in at the front, so registering a second engine after the first makes the second
one win.

`capabilities()`, `plan()` and `export()` all run for real on every one of them. Per-effect gaps
are listed in the Effects table below.

## Effects

Which built-in effects each render backend can lower. A backend only sees effects whose
`RenderApi` it recognises, and declines the rest so the next resolver gets a look.

| Effect          | Android               | Apple                 | Browser               | ffmpeg                |
|-----------------|-----------------------|-----------------------|-----------------------|-----------------------|
| `Rotate`        | yes                   | yes                   | pending [^web-resize] | yes                   |
| `Flip`          | yes                   | yes                   | yes                   | yes                   |
| `Crop` (aspect) | yes                   | yes                   | yes                   | yes                   |
| `CropRect`      | yes                   | yes                   | yes                   | yes                   |
| `Scale`         | yes [^android-fit]    | yes                   | pending [^web-resize] | yes [^ffmpeg-scale]   |
| `Brightness`    | yes [^hdr-brightness] | yes [^hdr-brightness] | yes [^hdr-brightness] | yes [^hdr-brightness] |
| `RgbAdjustment` | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `Contrast`      | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `Saturation`    | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `HueRotate`     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `Sepia`         | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `Invert`        | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `ColorMatrix`   | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     | yes [^sdr-matrix]     |
| `ImageOverlay`  | pending [^overlays]   | pending [^overlays]   | pending [^overlays]   | yes                   |
| `TextOverlay`   | pending [^overlays]   | pending [^overlays]   | pending [^overlays]   | no [^ffmpeg-text]     |

[^android-fit]: The effect itself only sets a height, and the pipeline applies `Fit` once for the
whole plan, not once per scale: the plan pins one `Presentation` at the resolved output frame, which
is also what puts clips of differing sizes on the same frame so they concatenate.

[^web-resize]: `Rotate` and `Scale` change the size of the render target rather than adding a pass,
which makes them pipeline setup. No browser pipeline has landed to set up.

[^ffmpeg-scale]: Claimed but contributes no filter node. The size stage is the tail the backend pins
to the resolved output frame, so the effect that decides that frame emits nothing of its own.
Claimed rather than declined, because an unclaimed spec is refused by name at plan time.

[^overlays]: Overlays are declared and refused with a message. On Android they have to share one
`OverlayEffect` so N overlays cost one GL pass instead of N. On Apple they have to composite inside
the image chain, because a composition carrying a layer tool is not valid for AVPlayer playback. In
the browser they need a canvas the resolver does not have.

[^hdr-brightness]: The factor is a multiply on an encoded SDR signal. On an export that keeps an HDR
grade it is read as the display light ratio an SDR display would have produced, so a frame looks the
same graded or not. Each backend applies that in the domain it holds the frame in: ffmpeg and Core
Image scale display light, and media3 scales scene light on HLG because it runs only the inverse
OETF there. A factor above `1f` matches an SDR export through the midtones and diverges in the
highlights, where SDR clips at white and HDR keeps going until the transfer function's own peak.

[^sdr-matrix]: Every colour effect other than `Brightness` is a 3x3 matrix and an offset on the
encoded SDR signal, and a run of them is folded into one matrix in shared code before any backend
clamps, so a channel pushed past white and pulled back again comes out the same everywhere. A clip's
run and the composition's are two runs, not one, and every backend clamps between them, the way it
would if the clip had been written out and read back. On an export that keeps an HDR grade the frame
is linear light, so each backend reads that light as the signal an SDR display at reference white
(203 nits) would have been fed, runs the matrix there, and takes the result back to light, floored
at black and clamped where the transfer function runs out rather than at white. A matrix with no
offset reads the same however bright the picture is, so only `Contrast`, `Invert` and a hand-written
offset are anchored to that white: a contrast pivots on about 44 nits, and a full inversion turns
anything brighter than 203 nits black. Android lowers the SDR matrix onto media3's `RgbMatrix` and a
kept grade onto a GL pass of its own, Apple onto `CIColorMatrix` inside the same tone curve pair
`Brightness` uses on SDR and between a pair of `CIGammaAdjust` filters in linear BT.2020 on a grade,
since Core Image's working space carries sRGB primaries and would floor a saturated BT.2020 channel,
the browser onto one `mat4` uniform, wrapped on a grade in the same GLSL body media3's pass runs,
and ffmpeg onto `lutrgb` when the matrix is per-channel and
onto a two-point `lut3d` cube otherwise, which reproduces an affine map exactly and clamps once at
the end. On a grade the cube runs at sixteen bits between two `lutrgb` tables that carry the
transfer function, which costs two format conversions per frame. The per-channel table rounds to the
nearest code value in its own expression. The cube cannot, and `lut3d` keeps the code value below
the one it interpolates, so a matrix that mixes channels can land one code value under the other
three backends on ffmpeg. PQ agrees across backends to the code value. HLG does not quite: Core
Image's opto-optical transfer keeps chroma but is not the per-channel one media3 and ffmpeg apply,
so an offset or a mix on a saturated HLG colour lands a few percent apart between Apple and the
other two, and grey agrees everywhere.

[^web-hdr]: The browser reads a kept grade as linear display light with reference white at one, and
it runs HLG's opto-optical transfer per channel on the way in, which is the reading media3 and
ffmpeg apply and which puts the three of them on the same code value. It holds the frame at half
float and packs the result straight into the bytes of a ten-bit frame, so nothing goes through an
eight-bit surface between the decoder and the encoder. A preview still draws an eight-bit picture:
the composited light is clipped at reference white and put through the SDR display curve, which is
what the browser's own upload of an HDR frame shows today.

[^ffmpeg-text]: Refused for one of two reasons, and the message says which. Either the ffmpeg build
has no `drawtext` filter, which needs `--enable-libfreetype` and `--enable-libharfbuzz` and the
common prebuilt packages leave out, or it has one and `drawtext` breaks lines only on a literal
newline, so `TextStyle.maxWidth` cannot be honoured. Text layout has to be exact, so it is refused
rather than rendered differently.

### Parity

`parityOf(specId)` says how closely a preview matches its export. Every built-in effect is `Exact`
except `TextOverlay`, which is `Approximate`. The approximation is glyph antialiasing only: line breaks,
metrics and measured extent are exact on both platforms.

## Codecs

| Codec | Android | Apple | Browser        | ffmpeg        |
|-------|---------|-------|----------------|---------------|
| H.264 | yes     | yes   | yes            | yes (libx264) |
| HEVC  | yes     | yes   | yes            | yes (libx265) |
| VP9   | no      | no    | yes            | no            |
| AAC   | yes     | yes   | yes [^web-aac] | yes           |
| ALAC  | no      | no    | no             | yes           |

HDR: Android reports HEVC Main 10 support, Apple infers it from HEVC availability, and the browser
reports VP9 Profile 2 [^web-hdr]. `HdrMode` is resolved once up front, and both preview and export
use that decision.

An export that keeps an HDR grade is pinned to HEVC on Android and Apple, because Main 10 is the
only profile either platform measured, and to VP9 Profile 2 in the browser, which is the only HDR
profile any browser encoder there was measured to take. Asking for H.264 as well reports a
`CodecFallback`. An SDR source resolves to "keep" whatever `HdrMode` asked for: there is no grade to
map, and claiming otherwise would cost the stream copy, since a platform told to tone-map has to
decode every frame to do it.

The browser's HDR encode is software only, so it runs on the CPU however capable the machine is, and
so does the decode: the only decoder that hands out a readable ten-bit frame there is the software
one. Whether a source's grade can be kept is therefore a question about the source rather than about
the device, asked once per clip at plan time. An HEVC Main 10 source, which is what an iPhone
writes, has no software decoder in any browser measured, so its grade survives only through a stream
copy of an untouched clip. Anything that has to be re-encoded is refused by name.

[^web-aac]: Chrome hands AAC encoding to the platform rather than carrying an encoder of its own,
so it is there on macOS and Windows and absent on Linux. `capabilities()` probes for it instead of
assuming it, and a browser that lists no audio encoder resolves the plan's audio codec to `None`,
so the export writes video on its own. Decoding is built in everywhere, which is why a stream copy
carries a source's AAC across on a browser that can write none.

## Compositions

What each export backend accepts. Every backend renders video from the primary track alone, so a
second track has to be audio-only, and a second video track is refused by name.

| Feature                                 | Android             | Apple             | Browser           | ffmpeg                 |
|-----------------------------------------|---------------------|-------------------|-------------------|------------------------|
| One video track, clips end to end       | yes                 | yes               | yes               | yes                    |
| Clip trim                               | yes [^android-trim] | yes               | yes [^trim]       | yes [^trim]            |
| Per-clip and per-track effects          | yes                 | yes               | yes               | yes                    |
| Composition-level effects               | yes                 | yes               | yes               | yes                    |
| Second audio-only track                 | yes                 | yes               | yes               | yes                    |
| Second video track (picture in picture) | no                  | no                | no                | no                     |
| Looping track                           | yes [^media3-loop]  | yes               | partial [^web-loop] | partial [^ffmpeg-loop] |
| `AudioSpec.Keep`, `Mute`, `Volume`      | yes                 | yes               | yes               | yes                    |
| `AudioSpec.Remove`, `AudioCodec.None`   | yes                 | yes               | yes               | yes                    |
| `AudioSpec.AudioOnly`                   | yes [^audio-only]   | yes [^audio-only] | yes [^audio-only] | yes [^audio-only]      |
| `AudioLevel` per clip and per track     | yes                 | yes               | yes               | yes                    |
| `AudioLevel.Envelope` and fades         | yes                 | yes               | yes               | yes [^ffmpeg-volume]   |

[^trim]: `TrimStrategy.Fast` and `Auto` both resolve to `Precise` on these backends, and the plan
reports the adjustment. The browser decodes frame by frame, so every trim lands exactly where it was
asked to. ffmpeg has no single-pass form that stream-copies around a trim.

[^android-trim]: All three strategies do something different here. `Precise` lands exactly, `Fast`
snaps to the keyframe at or before the trim point, and `Auto` resolves to `Precise` and reports the
adjustment, because choosing between them needs to know where the keyframes are and that costs a
decode.

[^audio-only]: The output has no video track, and `OutputFormat` has no way to say that: it reports
the video codec the plan resolved, which then goes unwritten. Over a `TrackContent.Video` track it
is refused, because nothing would be left to write.

[^ffmpeg-loop]: A looping audio track repeats for the whole run when it holds one clip and that clip
is untrimmed. A trimmed clip, or a track carrying more than one, still gets an `atrim` written for
the clip's own window, and `-stream_loop` carries every later pass at timestamps past that window,
so only the first pass reaches the mix. The video half takes no such exemption: a looping track
carrying video writes a `trim` unconditionally, so its picture stops after one pass while the audio
runs the full length. Loop a track that carries audio alone, and leave it untrimmed.

[^media3-loop]: A looping track repeats correctly, trimmed or not and however many clips it holds,
but its `start` is carried differently from the other three. media3 takes the offset as the first
item of the sequence it repeats, so a pass opens every `start + clipLength`, where the others lay
the offset down once and open a pass every `clipLength`. The same edit therefore places a bed's
later passes at different times on Android than elsewhere. Give a looping track a zero `start` to
stay clear of it.

[^web-loop]: A looping track holding one clip repeats correctly. A track carrying more than one
plays all of its clips at once rather than in turn, and a looping track's video runs for one pass
and then stops. Loop a track that carries audio alone and holds a single clip.

[^ffmpeg-volume]: `volume` reads its expression through ffmpeg's own parser, which refuses a nest of
about a hundred `if`s, and a curve folded from an envelope and a fade runs to hundreds of segments.
A long curve is split across a chain of `volume` nodes carrying 48 segments each, reading one
everywhere outside their own run. The nodes multiply, so the chain lands on the gain a single node
would have rather than on an approximation of it.

### Audio levels

`AudioLevel` is set on a clip or a track and `AudioSpec` on the composition. Every scope's level is
folded into one curve per clip before a backend sees it, so a mute at any scope silences everything
below it, and a level on two scopes multiplies rather than one replacing the other.

`AudioLevel.Envelope` is a piecewise-linear gain curve. Each `EnvelopePoint` carries an `at`, a
`gain` and a `from`, where `from` is `EnvelopeAnchor.Start` or `EnvelopeAnchor.End`. An end-anchored
point is placed once the plan settles how long the scope runs, so a fade out can be written before
the clip's length is known. The gain ramps linearly between neighbouring points and holds flat
before the first and after the last. A point reaching past the end of its scope is refused, and so
is a negative gain.

`fadeIn(duration)` and `fadeOut(duration)` on the clip and the track builder write those points, and
both are `@ExperimentalFilmstripApi`. A fade rises to whatever `audio(...)` set rather than to one,
and where the two are written makes no difference. A looping track drops its fade out, since it has
no end to measure the ramp back from.

Every backend ramps the gain rather than stepping it. media3 runs a `GainProcessor` over a
`GainProvider` that reads the curve at each frame. AVFoundation writes one
`setVolumeRampFromStartVolume:toEndVolume:timeRange:` per segment onto the audio mix. The browser
writes `linearRampToValueAtTime` automation onto the gain node each clip plays through. ffmpeg reads
a `volume` expression once per frame, with `asetnsamples=n=64` in front of it so the frame the
expression steps at is 1.3 ms at 48 kHz rather than the 21 ms a default 1024-sample frame gives.

## Sources and sinks

|                       | Browser                          | ffmpeg             | Android            | Apple              |
|-----------------------|----------------------------------|--------------------|--------------------|--------------------|
| `MediaSource.Path`    | no                               | yes                | yes                | yes                |
| `MediaSource.Uri`     | yes                              | `file://` only     | yes                | yes                |
| `MediaSource.Bytes`   | yes                              | no [^ffmpeg-bytes] | no [^bytes-mobile] | no [^bytes-mobile] |
| `MediaSink.Path`      | downloads a file                 | yes                | yes                | yes                |
| `MediaSink.Uri`       | hands back a `blob:` URL [^blob] | yes                | yes [^android-uri] | yes                |
| `MediaSink.Temporary` | downloads under a generated name | yes                | yes                | yes                |

[^ffmpeg-bytes]: In-memory bytes have to be written somewhere first. The backend reads files.

[^bytes-mobile]: In-memory sources need writing to a temporary file first, which is not implemented
on either platform. A `content://` URI on Android resolves through the app's `ContentResolver`, so
it needs the `Context` that App Startup captures.

[^blob]: The URL belongs to the caller, who has to pass it to `URL.revokeObjectURL` when they are
done. filmstrip never revokes it.

[^android-uri]: media3 writes to a path and nothing else, so a `content://` destination is written
to the cache and then streamed through the app's `ContentResolver`. A `file://` one is written
straight to its path.
