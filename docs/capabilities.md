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

`capabilities()`, `plan()` and `export()` all run for real on media3, AVFoundation and WebCodecs.
Per-effect gaps are listed in the Effects table below.

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
| `Brightness`    | yes [^hdr-brightness] | yes [^hdr-brightness] | yes [^web-sdr-only]   | yes [^hdr-brightness] |
| `Watermark`     | pending [^overlays]   | pending [^overlays]   | pending [^overlays]   | yes                   |
| `Text`          | pending [^overlays]   | pending [^overlays]   | pending [^overlays]   | no [^ffmpeg-text]     |

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

[^web-sdr-only]: The browser compositor renders into an eight-bit canvas, so this backend never
writes an HDR grade and the multiply always lands on an SDR signal. It has no lowering for a kept
grade, and a test pins the compositor's depth, so the day that changes the test fails instead of the
multiply landing in the wrong domain.

[^ffmpeg-text]: Refused for one of two reasons, and the message says which. Either the ffmpeg build
has no `drawtext` filter, which needs `--enable-libfreetype` and `--enable-libharfbuzz` and the
common prebuilt packages leave out, or it has one and `drawtext` breaks lines only on a literal
newline, so `TextStyle.maxWidth` cannot be honoured. Text layout has to be exact, so it is refused
rather than rendered differently.

### Parity

`parityOf(specId)` says how closely a preview matches its export. Every built-in effect is `Exact`
except `Text`, which is `Approximate`. The approximation is glyph antialiasing only: line breaks,
metrics and measured extent are exact on both platforms.

## Codecs

| Codec | Android | Apple | Browser              | ffmpeg        |
|-------|---------|-------|----------------------|---------------|
| H.264 | yes     | yes   | yes                  | yes (libx264) |
| HEVC  | yes     | yes   | yes                  | yes (libx265) |
| VP9   | no      | no    | yes                  | no            |
| AAC   | yes     | yes   | pending [^web-audio] | yes           |
| ALAC  | no      | no    | no                   | yes           |

HDR: Android reports HEVC Main 10 support, Apple infers it from HEVC availability. `HdrMode` is
resolved once up front, and both preview and export use that decision.

An export that keeps an HDR grade is pinned to HEVC, because Main 10 is the only profile either
platform measured. Asking for H.264 as well reports a `CodecFallback`. An SDR source resolves to
"keep" whatever `HdrMode` asked for: there is no grade to map, and claiming otherwise would cost the
stream copy, since a platform told to tone-map has to decode every frame to do it.

## Compositions

What each working export backend accepts. The Apple export pipeline has not landed, so there is no
column for it: `plan()` and `export()` both refuse by name there and `capabilities()` is real.

| Feature                                 | Android             | Browser          | ffmpeg            |
|-----------------------------------------|---------------------|------------------|-------------------|
| One video track, clips end to end       | yes                 | yes              | yes               |
| Clip trim                               | yes [^android-trim] | yes [^trim]      | yes [^trim]       |
| Per-clip and per-track effects          | yes                 | yes              | yes               |
| Composition-level effects               | yes                 | yes              | yes               |
| Second audio-only track                 | yes                 | no [^web-audio]  | yes               |
| Second video track (picture in picture) | no [^compositor]    | no [^compositor] | no [^compositor]  |
| Looping track                           | yes                 | no               | yes               |
| `AudioSpec.Keep`, `Mute`, `Volume`      | yes                 | no [^web-audio]  | yes               |
| `AudioSpec.Remove`, `AudioCodec.None`   | yes                 | yes              | yes               |
| `AudioSpec.AudioOnly`                   | yes [^audio-only]   | no [^web-audio]  | yes [^audio-only] |
| `AudioLevel` per clip and per track     | yes                 | no [^web-audio]  | yes               |

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

[^web-audio]: The browser audio pipeline has not landed. Remove the audio with `AudioSpec.Remove` or
`AudioCodec.None` to export video on its own.

[^compositor]: Every backend renders video from the primary track only. A second video track needs a
compositor, which none of them has.

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
