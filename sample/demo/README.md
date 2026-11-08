# demo

The demo app is the full filmstrip editor, written once in Compose Multiplatform and run on Android, desktop, iOS and
the browser. Pick a sample clip or a file of your own, trim it, grade it, add text, check what each backend can do,
then export the result. Every platform drives its own backend through the public API, so it's the quickest way to see
a change for real.

## Modules

- `shared` is the editor itself: the screens, the edit state, the diagnostics report and the bits each platform does
its own way, like playing back the export. It targets Android, the JVM, iOS and Wasm, and the other four modules only
start it.
- `androidApp` is the Android app, a single activity around the editor.
- `desktopApp` is the desktop app, a Compose window on the JVM.
- `iosApp` is the Xcode project. Its build phase runs `:sample:demo:shared:embedAndSignAppleFrameworkForXcode` and links
the framework that makes.
- `webApp` is the browser shell, built with Kotlin/Wasm.

The editor leans on `sample/common` for the sample clips (`SamplePreset`), the export delivery, the theme and the design
tokens it shares with the site. From the library it uses `filmstrip`, `filmstrip-effects-filters` for the looks,
`filmstrip-compose` and `filmstrip-compose-ui` for the player and editing UI, and `filmstrip-io-filekit` with
`filmstrip-io-filekit-compose` for picking a file. The sample clips are fetched from `filmstrip-media.jordond.dev`, and
`sample/media` has the scripts that make them.

## Running it

Android, with an emulator running or a device connected. Then open filmstrip from the launcher.

```sh
./gradlew :sample:demo:androidApp:installDebug
```

Desktop, with `ffmpeg` and `ffprobe` 8.0 or newer on the `PATH`.

```sh
./gradlew :sample:demo:desktopApp:run
```

Browser. Then open <http://localhost:8080> in Chrome. It needs WebCodecs and WebAssembly GC, and the site only tests
it on Chrome.

```sh
./gradlew :sample:demo:webApp:wasmJsBrowserDevelopmentRun
```

iOS, on a Mac with Xcode. Pick a simulator and press Run. A physical iPhone also needs your own team under Signing &
Capabilities.

```sh
open sample/demo/iosApp/iosApp.xcodeproj
```

## How the site uses it

The docs site runs the browser build at [/demo](https://filmstrip.jordond.dev/demo), inside the CRT on that page. The
docs workflow runs `./gradlew :sample:demo:webApp:wasmJsBrowserDistribution`, and `website/scripts/assemble.sh` copies
`webApp/build/dist/wasmJs/productionExecutable` into the site as `/demo/app/`. The page frames it from there with
`?embed=tv`, which draws the app inside the TV's screen. To see it on the site locally, build that and the Try it app,
then run `bun run assemble` and `bun run preview` in `website/`.

## Tests

```sh
./gradlew :sample:demo:shared:jvmTest
```

The editor's tests live in `shared/src/jvmTest` and run on the JVM, the Compose ones included, with
`./gradlew :sample:demo:shared:jvmTest`. CI runs them in the jvm lane, or in the sample job when only the sample
changed. The token test in `sample/common` also reads the Android theme and the webApp's `index.html`, so run
`./gradlew :sample:common:jvmTest` after changing their colours.
