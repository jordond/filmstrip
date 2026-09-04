# Capabilities

filmstrip is pre-alpha, so here is what works today.

Legend:

| Marker | Meaning                                                              |
| ------ | -------------------------------------------------------------------- |
| ✅     | Implemented.                                                         |
| ⚠️     | Works, with a documented limit. See the footnote.                    |
| ❌     | Not implemented. Refused by name at plan time, never silently wrong. |

Nothing in filmstrip fails silently. An unsupported effect comes back as
`EffectResolution.Unsupported` with a message, and an unsupported composition comes back as
`Verdict.Incapable` with a list of `ExportError`s.

## Targets

Every published module targets the same list, except the four engine artifacts, each of which only
runs where its engine does, and the two Compose ones, which have no desktop Apple surface in v1.

| Artifact                           | Android | iOS (arm64, sim arm64) | macOS (arm64) | JVM | Browser (js, wasmJs) |
| ---------------------------------- | ------- | ---------------------- | ------------- | --- | -------------------- |
| `filmstrip`                        | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-core`                   | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-effects`                | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-transform`              | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-transform-media3`       | ✅      | ❌                     | ❌            | ❌  | ❌                   |
| `filmstrip-transform-avfoundation` | ❌      | ✅                     | ✅            | ❌  | ❌                   |
| `filmstrip-transform-webcodecs`    | ❌      | ❌                     | ❌            | ❌  | ✅                   |
| `filmstrip-transform-ffmpeg`       | ❌      | ❌                     | ❌            | ✅  | ❌                   |
| `filmstrip-player`                 | ✅      | ✅                     | ✅            | ✅  | ✅                   |
| `filmstrip-compose`                | ✅      | ✅                     | ❌            | ✅  | ✅                   |
| `filmstrip-compose-ui`             | ✅      | ✅                     | ❌            | ✅  | ✅                   |
| `filmstrip-test`                   | ✅      | ✅                     | ✅            | ✅  | ✅                   |

Notes:

- `filmstrip-transform-media3`, `-avfoundation` and `-webcodecs` are each named after the engine they
  wrap, and each is single-target for the same reason `-ffmpeg` is: media3-transformer only runs on
  Android, AVFoundation and VideoToolbox only run on Apple, and WebCodecs only runs in a browser.
- `filmstrip-transform-ffmpeg` is JVM-only by design. It shells out to an `ffmpeg` and an `ffprobe`
  that are already on the machine, so it has nothing to offer a target that cannot spawn a process.

## Backends

A backend is registered on the `Filmstrip` builder.

| Backend      | Artifact                           | Registration            | Runs on | Built on                                         |
| ------------ | ---------------------------------- | ----------------------- | ------- | ------------------------------------------------ |
| media3       | `filmstrip-transform-media3`       | `media3Backend()`       | Android | media3-transformer                               |
| AVFoundation | `filmstrip-transform-avfoundation` | `avFoundationBackend()` | Apple   | AVFoundation and VideoToolbox                    |
| WebCodecs    | `filmstrip-transform-webcodecs`    | `webCodecsBackend()`    | browser | WebCodecs, mediabunny                            |
| ffmpeg       | `filmstrip-transform-ffmpeg`       | `ffmpegBackend()`       | JVM     | An `ffmpeg` and `ffprobe` already on the machine |

`filmstrip` registers the right one of these for the current target through `transformBackend()`, and
`playerBackend()` from `filmstrip-player` registers preview and thumbnails. `Filmstrip.create()` does
both. Every one of those calls also registers the built-in effect catalogue, so `builtInEffects()` is
only needed when registering none of them. Registrations go in at the front, so registering a second
engine after the first makes the second one win.

`capabilities()`, `plan()` and `export()` all run for real on every one of them. Per-effect gaps are
listed in the Effects table below.

## Effects

Which built-in effects each render backend can lower. A backend only sees effects whose `RenderApi`
it recognises, and declines the rest so the next resolver gets a look.

| Effect                                  | Android               | Apple             | Browser            | ffmpeg             |
| --------------------------------------- | --------------------- | ----------------- | ------------------ | ------------------ |
| `Rotate`                                | ✅                    | ✅                | ❌ [^web-resize]   | ✅                 |
| `Flip`                                  | ✅                    | ✅                | ✅                 | ✅                 |
| `Crop` (aspect)                         | ✅                    | ✅                | ✅                 | ✅                 |
| `CropRect`                              | ✅                    | ✅                | ✅                 | ✅                 |
| `Scale`                                 | ✅ [^android-fit]     | ✅                | ❌ [^web-resize]   | ✅ [^ffmpeg-scale] |
| `KenBurns`                              | ✅ [^clip-only]       | ✅ [^clip-only]   | ❌ [^pan-pending]  | ❌ [^pan-pending]  |
| `Brightness` [^hdr-brightness]          | ✅                    | ✅                | ✅                 | ✅                 |
| Colour matrices [^matrix] [^sdr-matrix] | ✅                    | ✅                | ✅                 | ✅                 |
| `ImageOverlay`                          | ✅ [^android-overlay] | ✅                | ❌ [^web-overlays] | ✅                 |
| `TextOverlay`                           | ✅ [^text-raster]     | ✅ [^text-raster] | ❌ [^web-overlays] | ❌ [^ffmpeg-text]  |

[^matrix]:
    `RgbAdjustment`, `Contrast`, `Saturation`, `HueRotate`, `Sepia`, `Invert` and
    `ColorMatrix`. They lower the same way, so they are one row.

[^clip-only]:
    `KenBurns` is `EffectScope.ClipOnly` and `@ExperimentalFilmstripApi`. The region it
    shows depends on where a frame sits in the clip's span, so a plan refuses it on a track, on the
    composition, and on a clip of a looping track. A `from` or `to` outside the frame, or with no area,
    is refused by name.

[^android-fit]:
    The effect itself only sets a height, and the pipeline applies `Fit` once for the
    whole plan, not once per scale: the plan pins one `Presentation` at the resolved output frame, which
    is also what puts clips of differing sizes on the same frame so they concatenate.

[^web-resize]:
    `Rotate` and `Scale` change the size of the render target rather than adding a pass,
    which makes them pipeline setup. No browser pipeline has landed to set up.

[^ffmpeg-scale]:
    Claimed but contributes no filter node. The size stage is the tail the backend pins
    to the resolved output frame, so the effect that decides that frame emits nothing of its own.
    Claimed rather than declined, because an unclaimed spec is refused by name at plan time.

[^pan-pending]:
    A pan moves the region it shows on every frame. The browser carries one texture
    matrix settled at resolve, and ffmpeg resolves a crop to whole pixels once at plan time so that the
    plan and the export cannot disagree about the frame. Neither has a per-frame form yet.

[^android-overlay]:
    On an HDR grade the overlay keeps its sRGB values, which media3 reads as BT.2020
    without converting the primaries, so the resolution comes back `Degraded` with
    `DegradationReason.ColorSpaceConverted`. Neutral tones are unaffected and saturated ones lose some
    saturation. A run of overlays shares one `OverlayEffect` here, so N overlays cost one GL pass.

[^text-raster]:
    Text is rasterised at resolve against the frame an export writes and only resampled
    after that, so a preview and its export break lines on the same words. A device that reports no
    `RenderFeature.TextRendering` refuses by name, and so does text and a style that leave nothing to
    draw.

[^web-overlays]:
    Overlay effects rasterise text and images into the frame, which needs a canvas the
    resolver does not have.

[^hdr-brightness]:
    The factor is a multiply on an encoded SDR signal. On an export that keeps an HDR
    grade it is read as the display light ratio an SDR display would have produced, so a frame looks the
    same graded or not. Each backend applies that in the domain it holds the frame in: ffmpeg and Core
    Image scale display light, and media3 scales scene light on HLG because it runs only the inverse
    OETF there. A factor above `1f` matches an SDR export through the midtones and diverges in the
    highlights, where SDR clips at white and HDR keeps going until the transfer function's own peak.

[^sdr-matrix]:
    Every colour effect other than `Brightness` is a 3x3 matrix and an offset on the
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
    and ffmpeg onto `lutrgb` when the matrix is per-channel and onto a two-point `lut3d` cube otherwise,
    which reproduces an affine map exactly and clamps once at the end. On a grade the cube runs at
    sixteen bits between two `lutrgb` tables that carry the transfer function, which costs two format
    conversions per frame. The per-channel table rounds to the nearest code value in its own expression.
    The cube cannot, and `lut3d` keeps the code value below the one it interpolates, so a matrix that
    mixes channels can land one code value under the other three backends on ffmpeg. PQ agrees across
    backends to the code value. HLG does not quite: Core Image's opto-optical transfer keeps chroma but
    is not the per-channel one media3 and ffmpeg apply, so an offset or a mix on a saturated HLG colour
    lands a few percent apart between Apple and the other two, and grey agrees everywhere.

[^ffmpeg-text]:
    Refused for one of two reasons, and the message says which. Either the ffmpeg build
    has no `drawtext` filter, which needs `--enable-libfreetype` and `--enable-libharfbuzz` and the
    common prebuilt packages leave out, or it has one and `drawtext` breaks lines only on a literal
    newline, so `TextStyle.maxWidth` cannot be honoured. Text layout has to be exact, so it is refused
    rather than rendered differently.

### Parity

`parityOf(specId)` says how closely a preview matches its export, and every backend keeps its own
table.

| Backend | Exact                                             | Approximate   |
| ------- | ------------------------------------------------- | ------------- |
| Android | everything it lowers except `TextOverlay`         | `TextOverlay` |
| Apple   | everything it lowers except `TextOverlay`         | `TextOverlay` |
| ffmpeg  | everything it lowers except `Scale`               | `Scale`       |
| Browser | `Crop`, `CropRect`, `Flip` and the colour effects | none          |

`TextOverlay`'s approximation is glyph antialiasing only: line breaks, metrics and measured extent
are exact on both platforms. `Scale` on ffmpeg goes through swscale's bicubic kernel, which is not
the one a preview resamples with. Anything with no entry answers null from `parityOf`, and the plan
reads a null as `Exact`.

## Codecs

What each backend encodes.

| Codec                   | Android | Apple | Browser       | ffmpeg                        |
| ----------------------- | ------- | ----- | ------------- | ----------------------------- |
| H.264                   | ✅      | ✅    | ✅            | ✅ libx264, h264_videotoolbox |
| HEVC                    | ✅      | ✅    | ✅            | ✅ hevc_videotoolbox, libx265 |
| VP9                     | ❌      | ❌    | ✅            | ✅ libvpx-vp9 [^ffmpeg-vp9]   |
| AV1, VP8                | ❌      | ❌    | ❌            | ❌                            |
| AAC                     | ✅      | ✅    | ⚠️ [^web-aac] | ✅                            |
| ALAC                    | ❌      | ❌    | ❌            | ✅                            |
| Opus, MP3, FLAC, Vorbis | ❌      | ❌    | ❌            | ❌                            |

`AudioCodec` and `VideoCodec` declare more than any backend writes. A codec the device has no
encoder for falls back to the next rung and reports a `CodecFallback`, or refuses under
`ExportSpec.strict`. `VideoCodec.Auto` walks H.264 then HEVC on Android, Apple and ffmpeg, and
H.264, VP9 then HEVC in the browser.

[^ffmpeg-vp9]:
    Reported when the build carries libvpx, and reachable only by naming
    `VideoCodec.Vp9`. ffmpeg's `Auto` ladder leaves VP9 out on purpose.

[^web-aac]:
    Chrome hands AAC encoding to the platform rather than carrying an encoder of its own,
    so it is there on macOS and Windows and absent on Linux. `capabilities()` probes for it instead of
    assuming it, and a browser that lists no audio encoder resolves the plan's audio codec to `None`,
    so the export writes video on its own. Decoding is built in everywhere, which is why a stream copy
    carries a source's AAC across on a browser that can write none.

### HDR

`HdrMode` is resolved once up front, and both preview and export use that decision. An SDR source
resolves to "keep" whatever `HdrMode` asked for: there is no grade to map, and claiming otherwise
would cost the stream copy, since a platform told to tone-map has to decode every frame to do it.

Keeping a grade needs a 10-bit profile, so the export is pinned to HEVC Main 10 on Android, Apple and
ffmpeg, and to VP9 Profile 2 in the browser, which is the only HDR profile any browser encoder there
was measured to take. Asking for another codec as well reports a `CodecFallback`. Android reports
Main 10 support from the encoder list, Apple opens a probe session, ffmpeg encodes one frame at
`main10`, and the browser asks `VideoEncoder.isConfigSupported` and also checks it can render float.

Tone mapping down to SDR is always available on Android and Apple. ffmpeg needs `zscale`
(`--enable-libzimg`) or `libplacebo` and says which is missing. The browser cannot tone map at all,
because its single WebGL pass has no tone-map stage, so an HDR source whose grade it cannot keep is
refused rather than written into an SDR file.

The browser's HDR encode is software only, so it runs on the CPU however capable the machine is, and
so does the decode: the only decoder that hands out a readable ten-bit frame there is the software
one. Whether a source's grade can be kept is therefore a question about the source rather than about
the device, asked once per clip at plan time. An HEVC Main 10 source, which is what an iPhone
writes, has no software decoder in any browser measured, so its grade survives only through a stream
copy of an untouched clip. Anything that has to be re-encoded is refused by name. [^web-hdr]

[^web-hdr]:
    The browser reads a kept grade as linear display light with reference white at one, and
    it runs HLG's opto-optical transfer per channel on the way in, which is the reading media3 and
    ffmpeg apply and which puts the three of them on the same code value. It holds the frame at half
    float and packs the result straight into the bytes of a ten-bit frame, so nothing goes through an
    eight-bit surface between the decoder and the encoder. A preview still draws an eight-bit picture:
    the composited light is clipped at reference white and put through the SDR display curve, which is
    what the browser's own upload of an HDR frame shows today.

## Compositions

What each export backend accepts. Every backend renders video from the primary track alone, so a
second track has to be audio-only, and a second video track is refused by name.

| Feature                                 | Android          | Apple            | Browser          | ffmpeg              |
| --------------------------------------- | ---------------- | ---------------- | ---------------- | ------------------- |
| One video track, clips end to end       | ✅               | ✅               | ✅               | ✅                  |
| Clip trim                               | ✅               | ✅               | ✅               | ✅                  |
| Per-clip and per-track effects          | ✅               | ✅               | ✅               | ✅                  |
| Composition-level effects               | ✅               | ✅               | ✅               | ✅                  |
| `Fill.Solid` behind bars and gaps       | ✅               | ✅               | ✅               | ✅                  |
| `Fill.Blurred`                          | ✅               | ✅               | ✅               | ⚠️ [^ffmpeg-blur]   |
| Second audio-only track                 | ✅               | ✅               | ✅               | ✅                  |
| Second video track (picture in picture) | ❌               | ❌               | ❌               | ❌                  |
| Looping track                           | ✅               | ✅               | ⚠️ [^web-loop]   | ⚠️ [^ffmpeg-loop]   |
| `AudioSpec.Keep`, `Mute`, `Volume`      | ✅               | ✅               | ✅               | ✅                  |
| `AudioSpec.Remove`, `AudioCodec.None`   | ✅               | ✅               | ✅               | ✅                  |
| `AudioSpec.AudioOnly`                   | ✅ [^audio-only] | ✅ [^audio-only] | ✅ [^audio-only] | ✅ [^audio-only]    |
| `AudioLevel` per clip and per track     | ✅               | ✅               | ✅               | ✅                  |
| `AudioLevel.Envelope` and fades         | ✅               | ✅               | ✅               | ✅ [^ffmpeg-volume] |

More than one track, `fadeIn` and `fadeOut` are `@ExperimentalFilmstripApi`.

A composition where every track loops has nothing to bound it and is refused. `ExportPath` declares
`TrimOptimized`, but no backend resolves to it today: a plan is `Transmux` when the whole thing can
be stream-copied and `Transcode` otherwise, and a snapped trim is a `Transmux` like any other copy.

A copy needs more than a reachable cut. `ExportSpec.targetHeight`, `bitrate`, a named codec, any
effect, a second clip and a non-unity gain each take an export off the copy path on their own, and
`ExportSpec.Upload` trips three of them at once. `ExportPlan.copyBlockedBy` lists every term that
applied, so a caller can see which field to drop rather than guess why a three second export took
forty.

[^ffmpeg-blur]:
    Needs `gblur`, and needs `colorchannelmixer` as well when `Fill.Blurred.dim`
    actually darkens the background. A build missing either refuses by name and says which filter is
    absent.

[^ffmpeg-loop]:
    A looping audio track repeats for the whole run when it holds one clip and that clip
    is untrimmed. A trimmed clip, or a track carrying more than one, still gets an `atrim` written for
    the clip's own window, and `-stream_loop` carries every later pass at timestamps past that window,
    so only the first pass reaches the mix. The video half takes no such exemption: a looping track
    carrying video writes a `trim` unconditionally, so its picture stops after one pass while the audio
    runs the full length. Loop a track that carries audio alone, and leave it untrimmed.

[^web-loop]:
    A looping track holding one clip repeats correctly. On a track carrying more than one,
    each clip repeats on its own period from its own offset, so later passes overlap instead of
    following one another, and a looping track's video runs for one pass and then stops. Loop a track
    that carries audio alone and holds a single clip.

[^audio-only]:
    The output has no video track, and `OutputFormat` has no way to say that: it reports
    the video codec the plan resolved, which then goes unwritten. Over a `TrackContent.Video` track it
    is refused, because nothing would be left to write.

[^ffmpeg-volume]:
    `volume` reads its expression through ffmpeg's own parser, which refuses a nest of
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

`fadeIn(duration)` and `fadeOut(duration)` on the clip and the track builder write those points. A
fade rises to whatever `audio(...)` set rather than to one, and where the two are written makes no
difference. A looping track drops its fade out, since it has no end to measure the ramp back from.

Every backend ramps the gain rather than stepping it. media3 runs a `GainProcessor` over a
`GainProvider` that reads the curve at each frame. AVFoundation writes one
`setVolumeRampFromStartVolume:toEndVolume:timeRange:` per segment onto the audio mix. The browser
writes `linearRampToValueAtTime` automation onto the gain node each clip plays through. ffmpeg reads
a `volume` expression once per frame, with `asetnsamples=n=64` in front of it so the frame the
expression steps at is 1.3 ms at 48 kHz rather than the 21 ms a default 1024-sample frame gives.

## Stills

A photo goes on the timeline as `MediaSource.Image(image, duration)`, or through the `image(...)`
builder on a composition or a track. Both are `@ExperimentalFilmstripApi`. The image itself is an
`ImageSource`, which is a path, a URI or bytes.

| Feature                       | Android | Apple             | Browser         | ffmpeg          |
| ----------------------------- | ------- | ----------------- | --------------- | --------------- |
| A still clip on the timeline  | ✅      | ✅ [^apple-still] | ❌ [^no-stills] | ❌ [^no-stills] |
| Encode a frame to PNG or JPEG | ✅      | ✅                | ✅              | ✅              |
| Encode a frame to WebP        | ✅      | ❌                | ⚠️ [^web-webp]  | ❌              |

A still has no cadence of its own, so a plan that settles on no frame rate refuses it by name. Where
the composition's first frame is a still and no `targetHeight` was asked for, the output frame is
held to 3840x2160, since a photo's own bounds are a sensor's rather than an encoder's.

Decoding a still is the platform's own decoder in every case: `BitmapFactory` on Android, ImageIO on
Apple, the JDK's `ImageIO` on the JVM, `createImageBitmap` in a browser. Whatever those read,
filmstrip reads. A browser cannot open an `ImageSource.Path`, because a path names a filesystem no
browser has.

[^apple-still]:
    AVFoundation discards a trailing empty range and gives a track holding nothing but
    empty ranges no duration, so each still is cut from a small generated movie of black frames and the
    picture is drawn over it by the Core Image chain. The seed pixels are never seen.

[^no-stills]:
    Both backends open a clip by decoding a video track, and a photo has none. Refused by
    name wherever it appears, an audio track included.

[^web-webp]:
    A browser that will not encode WebP falls back to PNG rather than failing, so filmstrip
    compares the blob's own media type against the one it asked for and refuses by name when they
    disagree. The JDK ships no WebP writer, and Apple's ImageIO reads WebP on systems where it will not
    write it, so both refuse by name too.

## Preview and thumbnails

`filmstrip-player` carries a real engine on all four platforms, and each one lowers the composition
through the same engine its exports go through, so the compositing is the same graph rather than a
second implementation. Without the artifact, `preview()` returns a player that reports
`PlaybackError.BackendMissing` instead of throwing.

| Feature                  | Android           | Apple | Browser         | ffmpeg             |
| ------------------------ | ----------------- | ----- | --------------- | ------------------ |
| Live composition preview | ✅                | ✅    | ⚠️ [^web-ramp]  | ✅                 |
| `FrameReadback`          | ✅                | ✅    | ⚠️ [^web-gl]    | ✅                 |
| `FrameStepping`          | ✅                | ✅    | ⚠️ [^web-gl]    | ✅                 |
| `LiveParameterRedraw`    | ✅                | ✅    | ⚠️ [^web-gl]    | ❌ [^ffmpeg-live]  |
| `AudioMonitoring`        | ⚠️ [^media3-vol]  | ❌    | ⚠️ [^web-audio] | ❌ [^ffmpeg-audio] |
| `HdrPreview`             | ⚠️ [^hdr-display] | ❌    | ❌              | ❌                 |
| `PlaybackSpeed`          | ❌                | ❌    | ❌              | ❌                 |
| `SeekAccuracy.Nearest`   | ❌ [^media3-seek] | ✅    | ✅              | ❌ [^ffmpeg-seek]  |

`PreviewFidelity` reports what a preview cannot show whatever the backend. `EncoderArtifacts` is
`NotPreviewable` everywhere, because the preview never opens an encoder. `Smoothness` is
`Approximate` everywhere, because a late frame is dropped. `HdrAppearance` is `NotPreviewable`
everywhere: on Android and Apple the display decides it, the browser composites in standard range,
and the ffmpeg pump converts a grade straight to RGB.

Thumbnails go through `FrameRenderer.frame` and `frames`, or `ThumbnailRequest` directly.
`ThumbnailRequest.precise` chooses an exact seek over the nearest sync sample. Android honours it
with media3's `SeekParameters`, Apple with a zero seek tolerance, and the browser and ffmpeg decode
forward from a sync sample either way, so every frame there is the exact one. A photo on Android is
drawn rather than extracted, and is always exact.

[^web-ramp]:
    The picture is the compositor the encoder takes its frames from, so it is exact. The
    audio is not: the preview samples a clip's gain curve where the slice opens and holds it flat
    across, so a fade cannot be auditioned yet.

[^web-gl]:
    Reported only when the page can obtain a WebGL2 context. Readback is the whole display
    path in a browser, so a page that cannot get one has none of the three rather than some of them.

[^media3-vol]:
    Reported when `CompositionPlayer` makes `COMMAND_SET_VOLUME` available, which is
    asked of the player rather than assumed.

[^web-audio]:
    Reported wherever the Web Audio API is there. A browser refusing to start the graph
    without a user gesture is a transport occasion rather than a missing capability, and the engine puts
    `playWhenReady` back to false when it happens.

[^ffmpeg-audio]:
    Nothing here opens an audio device. The preview pump is video only, and
    `setVolume` does nothing, so a host reads the feature rather than wondering why the slider is silent.

[^ffmpeg-live]:
    A parameter is filter graph text, so changing one is a new graph and a new process
    rather than a value swapped under a running render.

[^hdr-display]: Asked of the display, which is what decides whether a grade survives the last step.

[^media3-seek]:
    `CompositionPlayer` takes no seek tolerance of any kind, so a relaxed seek is
    clamped to `Exact` and costs what an exact one costs.

[^ffmpeg-seek]:
    A relaxed seek debounces before it spawns a process rather than landing on a
    different frame. That is what bounds the cost of a scrub here.

## Sources and sinks

|                       | Android             | Apple             | Browser                          | ffmpeg             |
| --------------------- | ------------------- | ----------------- | -------------------------------- | ------------------ |
| `MediaSource.Path`    | ✅                  | ✅                | ❌                               | ✅                 |
| `MediaSource.Uri`     | ✅                  | ✅                | ✅                               | ⚠️ `file://` only  |
| `MediaSource.Bytes`   | ✅ [^android-bytes] | ❌ [^apple-bytes] | ✅                               | ❌ [^ffmpeg-bytes] |
| `MediaSink.Path`      | ✅                  | ✅                | downloads a file                 | ✅                 |
| `MediaSink.Uri`       | ✅ [^android-uri]   | ⚠️ `file://` only | hands back a `blob:` URL [^blob] | ⚠️ `file://` only  |
| `MediaSink.Temporary` | ✅                  | ✅                | downloads under a generated name | ✅                 |

[^android-bytes]:
    Written to the app's cache under a name taken from the bytes themselves, so the
    probe and the export that follows it read one file rather than writing two. Needs a
    `FilmstripContext`, and is refused by name without one.

[^apple-bytes]:
    In-memory sources need writing to a temporary file first, which neither `probe` nor
    the lowering does here.

[^ffmpeg-bytes]: In-memory bytes have to be written somewhere first. The backend reads files.

[^blob]:
    The URL belongs to the caller, who has to pass it to `URL.revokeObjectURL` when they are
    done. filmstrip never revokes it.

[^android-uri]:
    media3 writes to a path and nothing else, so a `content://` destination is written
    to the cache and then streamed through the app's `ContentResolver`. A `file://` one is written
    straight to its path.

`probe` reads metadata with `MediaMetadataRetriever` on Android and `AVURLAsset` on Apple, both from
`filmstrip-core` alone. The browser and the JVM have no read-only framework to open a container
with, so `probe` there answers only for a still until `webCodecsBackend()` or `ffmpegBackend()` is
registered, and refuses by name until it is.
