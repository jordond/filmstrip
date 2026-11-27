# stories

A story editor, written once in Compose Multiplatform and run on Android, desktop, iOS and the browser. One clip fills
an upright 9:16 frame, and text, stickers and a drawing go over it. Everything is dragged, pinched and turned live on
the picture and is burned into the video only when it is exported.

It works the way a phone's story editor does. The theme, the names and the art are filmstrip's own.

## What it does

- **Elements.** One finger drags, two pinch and twist. The element last touched comes to the top. Dashed guides show
  and the element snaps as it nears the centre lines or a quarter turn. A bin shows while an element is held, and
  letting go over it deletes the element.
- **Text.** `Aa`, or a tap on the bare picture, opens text mode: five faces, eight colours, three plates, three
  alignments, an entrance (fade, rise or slide) and a size slider. A tap on a selected caption edits it again.
- **Stickers.** A tray with a search field. A tap on a selected sticker steps through its styles.
- **Draw.** Pen, marker, glow and an eraser, with a colour row, a width slider and undo.
- **Timing.** With an element selected, a strip of the clip shows a window with two handles that set when it shows.
- **Looks.** A sideways swipe on the bare picture steps through six colour looks.
- **Undo and redo** for every edit. One drag is one step.

On a phone the picture fills the screen and the tools float over it. On a desktop, and in a browser with a mouse, the
picture sits between a tool rail and an inspector, the selected element shows size and turn handles, and a legend
lists every key and mouse binding. Both layouts drive the same editor, so neither can do something the other cannot.

Left out on purpose: music, polls, links, mentions, location, GIF search, AR effects and posting.

## How it uses filmstrip

- `ComposedOverlay` draws each element on a `VideoStage`, and `OverlayCapturer` bakes the same composable for the
  export. `StoryElementContent` is the only thing that draws an element, so the stage and the file match.
- The preview composition never holds the elements, so moving one never re-plans the preview. An export plans once
  for the output frame, bakes every element on it, and plans again with the overlays (`planStory`).
- A caption's entrance is one `OverlayAnimation`. The export is handed it and the stage samples the same one at the
  playhead (`liveFrame`).
- The mouse and keyboard handling of an element is the library's: the handles, the wheel and
  `Modifier.composedOverlayKeys`. The app adds only its own shortcuts (`StoryShortcut`).
- A look is a short chain of colour effects on the composition, so it sits under the elements.

Limits: an element's turn and size are fixed in its baked picture, so an entrance can fade and move it but not spin
it. A browser without WebCodecs can preview but not export, and the share sheet says so.

## Modules

- `shared` is the editor: the story model, the stage, both layouts and the export. It targets Android, the JVM, iOS
  and Wasm.
- `androidApp`, `desktopApp` and `webApp` each start it and nothing else. `iosApp` is the Xcode project, whose build
  phase runs `:sample:stories:shared:embedAndSignAppleFrameworkForXcode`.

It leans on `sample/common` for the sample clips, the export delivery and the theme.

## Run it

```sh
./gradlew :sample:stories:desktopApp:run
./gradlew :sample:stories:webApp:wasmJsBrowserDevelopmentRun
./gradlew :sample:stories:androidApp:installDebug
```

## Tests

```sh
./gradlew :sample:stories:shared:jvmTest
```

## Fonts

Captions can use Pacifico and DM Serif Display beside the theme's three faces. Both are under the SIL Open Font
License, and the licence texts sit beside the fonts in `shared/src/commonMain/composeResources/font`.
