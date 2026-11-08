# webApp

The full filmstrip editor, built for the browser with Kotlin/Wasm and Compose. Pick a sample clip or a file of your
own, trim it, grade it, add text, check what each backend can do, then export and download the result. It's what the
docs site runs at [/demo](https://filmstrip.jordond.dev/demo), inside the CRT on that page. The page frames it from
`/demo/app/` with `?embed=tv`, which draws the app inside the TV's screen.

## What it uses

- `sample/common` for the sample clips (`SamplePreset`), the export download, the theme and the design tokens it
shares with the site.
- `filmstrip` for the engine, plus `filmstrip-effects-filters` for the looks.
- `filmstrip-compose` and `filmstrip-compose-ui` for the player and editing UI.
- `filmstrip-io-filekit` and `filmstrip-io-filekit-compose` for picking a file.

The sample clips are fetched from `filmstrip-media.jordond.dev`. `sample/media` has the scripts that make them.

## Running it

```sh
./gradlew :sample:webApp:wasmJsBrowserDevelopmentRun
```

Then open <http://localhost:8080> in a Chromium-based browser. It needs WebCodecs and WebAssembly GC, and the site only
tests it on Chrome.

## How the site uses it

The docs workflow runs `./gradlew :sample:webApp:wasmJsBrowserDistribution`, and `website/scripts/assemble.sh` copies
`build/dist/wasmJs/productionExecutable` into the site as `/demo/app`. To see it on the site locally, build that and
the Try it app, then run `bun run assemble` and `bun run preview` in `website/`.

## Tests

```sh
./gradlew :sample:webApp:wasmJsBrowserTest
```

They run in headless Chrome through Karma. The token test in `sample/common` also reads this module's `index.html`,
so run `./gradlew :sample:common:jvmTest` after changing its colours.
