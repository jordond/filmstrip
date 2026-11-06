# Companion checklist after a landing

Run the rows the diff hits, compare, route a fix.

| Diff touched | Check | How |
|---|---|---|
| a public signature in core, effects, player, compose, compose-ui or test | its `api/` dump moved with it | `./gradlew updateKotlinAbi`, then `./gradlew checkKotlinAbi` in a second call. Mac only. No dump for transform or the backends |
| an `@InternalFilmstripApi` declaration made public or changed | not caught by the ABI check | read it, it needs the annotation dropped on purpose or kept |
| a number more than one backend reads | computed once in the lowest module that sees it, backends call it | grep the backends for the literal or a local re-derivation |
| a backend clamping or diverging | a comment naming whose limit, and a test pinning it | read the clamp site |
| a preview, readback, thumbnail or export pixel path | `connectedAndroidDeviceTest` at the head shows no new failures against the board's last verified counts, its total up by the tests the unit added | booted AVD, see memory `running-android-device-suite`. No run at the base |
| a new sealed or enum variant | handled at every exhaustive `when` | `grep -rn '<Variant>'` across modules |
| a module dependency or import | the layer guards | `./gradlew checkForbiddenImports checkLayering` |
| a public declaration | KDoc per `CLAUDE.md`, never one line, wrapped at 120 | read it |
| a caller-visible behaviour | an edit to the matching page under `website/src/content/docs/guides/` | read the matching section |
| a Kotlin `Map` into AVFoundation or VideoToolbox | a real dictionary, and an iOS run, not only macOS | `AvSettingsKeyTest`, then iOS simulator or device |
| the edit model | no `@Serializable` | `grep -rn '@Serializable' filmstrip-core filmstrip-effects` |
| a file past 500 lines | a nits line in the branch's `upnext.md` section | `wc -l` |
