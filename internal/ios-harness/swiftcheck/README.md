# The Swift half of the frame handoff

Not a Gradle task, because Gradle cannot compile Swift. Two files, each answering a different
question about how a rendered frame reaches a Swift caller.

- `Probe.swift` checks what typechecks. Every candidate accessor on `HandoffImage`, plus the
  `as?` matching a non-SKIE consumer has to write against a sealed result. Compiled with
  `-typecheck` only, so it never runs.
- `Bench.swift` checks what it costs. Reads one 1080p RGBA frame's worth of bytes through each
  escape and prices the crossing.

Both link the framework, so build it first:

```
./gradlew :internal:ios-harness:linkDebugFrameworkIosSimulatorArm64
./gradlew :internal:ios-harness:linkReleaseFrameworkMacosArm64
```

```
# what typechecks, against the iOS framework
cd internal/ios-harness/build/bin/iosSimulatorArm64/debugFramework
xcrun --sdk iphonesimulator swiftc -typecheck -target arm64-apple-ios15.0-simulator \
  -F . ../../../../swiftcheck/Probe.swift

# what it costs, against the macOS framework
cd internal/ios-harness/build/bin/macosArm64/releaseFramework
swiftc -O -target arm64-apple-macos13.0 -F . -framework IosHarness \
  ../../../../swiftcheck/Bench.swift -o /tmp/bench && /tmp/bench
```

`-O` matters. Without it the loops are not representative. With it, and without a consumed result,
the optimiser deletes them outright and every arm reports 0.000 ms. Every arm therefore accumulates
into a printed global. The generated Objective-C header, the other half of the finding, is at
`build/bin/<target>/<config>Framework/IosHarness.framework/Headers/IosHarness.h`.

Results: `../results/frame-handoff.tsv` (Kotlin side) and `../results/swift-bridge.tsv` (Swift side).
