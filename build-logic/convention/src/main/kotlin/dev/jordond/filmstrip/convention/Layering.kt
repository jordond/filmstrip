package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register

/**
 * A rung of the layering contract. A module sits on exactly one, and its resolved graph may only hold projects from
 * the layers in [mayDependOn]. Its test classpaths may also hold the layers in [mayTestWith].
 */
internal enum class Layer(
  val mayDependOn: Set<Layer>,
  val mayTestWith: Set<Layer> = emptySet(),
) {
  Core(emptySet()),
  Effects(setOf(Core)),

  // Opt-in, so nothing above it may take it in main code. Its end to end tests run from the layers that can export.
  Filters(setOf(Core, Effects)),
  Transform(setOf(Core, Effects), mayTestWith = setOf(Filters)),
  Backend(setOf(Core, Effects, Transform), mayTestWith = setOf(Filters)),

  Player(setOf(Core, Effects, Transform, Backend), mayTestWith = setOf(Filters)),

  // Opt-in, the binaries a backend runs in place of the machine's own. Nothing above it may take it. Its tests read a
  // preview frame through the player. Declared below Player, since an enum entry cannot name one that comes later.
  Binaries(setOf(Core, Effects, Transform, Backend), mayTestWith = setOf(Player)),

  // Fakes a consumer's tests run against. It reaches the player for the half of an engine with no platform in it.
  // Declared above the layers that test with it, since an enum entry cannot name one that comes later.
  Testing(setOf(Core, Effects, Transform, Backend, Player)),
  Compose(setOf(Core, Effects, Transform, Backend, Player), mayTestWith = setOf(Testing)),
  ComposeUi(setOf(Core, Effects, Transform, Backend, Player, Compose)),
  Umbrella(setOf(Core, Effects, Transform, Backend, Player)),

  // The io adapters branch off core rather than climbing the ladder. Only io-filekit is taken by anything, and
  // only by io-filekit-compose.
  Io(setOf(Core)),
  IoCompose(setOf(Core, Io)),
  Fixtures(setOf(Core)),
}

/**
 * The layer every module sits on, one line each. [configureArchitectureGuards] fails on a `:filmstrip` project that
 * is missing. Sample and internal projects are not layered.
 */
private val LAYERS: Map<String, Layer> =
  mapOf(
    ":filmstrip-core" to Layer.Core,
    ":filmstrip-effects" to Layer.Effects,
    ":filmstrip-effects-filters" to Layer.Filters,
    ":filmstrip-transform" to Layer.Transform,
    ":filmstrip-transform-media3" to Layer.Backend,
    ":filmstrip-transform-avfoundation" to Layer.Backend,
    ":filmstrip-transform-webcodecs" to Layer.Backend,
    ":filmstrip-transform-ffmpeg" to Layer.Backend,
    ":filmstrip-transform-ffmpeg-bundled" to Layer.Binaries,
    ":filmstrip-player" to Layer.Player,
    ":filmstrip-compose" to Layer.Compose,
    ":filmstrip-compose-ui" to Layer.ComposeUi,
    ":filmstrip" to Layer.Umbrella,
    ":filmstrip-io-filekit" to Layer.Io,
    ":filmstrip-io-filekit-compose" to Layer.IoCompose,
    ":filmstrip-io-kotlinx" to Layer.Io,
    ":filmstrip-io-okio" to Layer.Io,
    ":filmstrip-test" to Layer.Fixtures,
    ":filmstrip-testing" to Layer.Testing,
  )

private val COMPOSE = setOf("org.jetbrains.compose", "androidx.compose")

private val MEDIA3_RUNTIMES = setOf("androidx.media3:media3-transformer", "androidx.media3:media3-exoplayer")

/**
 * The one hole in [COMPOSE].
 *
 * `runtime-annotation` carries `@Immutable` and `@Stable` and nothing else: no runtime, no composer, and no
 * transitive dependency of its own. `core` takes it so its value types declare their own stability, which is the only
 * way a consumer's call site can compare a `Composition` or a `TimeRange` by value rather than by identity. It
 * reaches every module from there, so the allowance is repo-wide rather than named layer by layer.
 */
private val COMPOSE_ANNOTATIONS = setOf("androidx.compose.runtime:runtime-annotation")

/**
 * The one hole in the `androidx.media3` ban.
 *
 * `common` declares `Effect` and `effect` declares `TextureOverlay`, the two types the Android `PlatformEffect` wraps.
 * `effect` depends on `datasource`, which depends on `database`, so both come with it. None of the four plays or
 * exports anything, which is what [MEDIA3_RUNTIMES] keeps out. `core` takes `common` and `effect` as `api`, so they
 * reach every module that sees core and the allowance is repo-wide like [COMPOSE_ANNOTATIONS].
 */
private val MEDIA3_EFFECT_TYPES =
  setOf(
    "androidx.media3:media3-common",
    "androidx.media3:media3-effect",
    "androidx.media3:media3-datasource",
    "androidx.media3:media3-database",
  )

private val COMPOSE_ANNOTATION_IMPORTS = setOf("androidx.compose.runtime.Immutable", "androidx.compose.runtime.Stable")

/**
 * Coordinate prefixes that must not reach a layer's resolved classpath by any route.
 */
private val FORBIDDEN_EXTERNALS: Map<Layer, Set<String>> =
  mapOf(
    Layer.Core to COMPOSE + "androidx.media3",
    Layer.Effects to COMPOSE + MEDIA3_RUNTIMES,
    Layer.Filters to COMPOSE + MEDIA3_RUNTIMES,
    Layer.Transform to COMPOSE + MEDIA3_RUNTIMES,
    Layer.Backend to COMPOSE,
    Layer.Binaries to COMPOSE,
    Layer.Player to COMPOSE,
    Layer.Umbrella to COMPOSE,
    Layer.Io to COMPOSE + "androidx.media3",
    Layer.IoCompose to setOf("androidx.media3"),
    Layer.Fixtures to COMPOSE,
    Layer.Testing to COMPOSE,
  )

/**
 * Apple playback and export, banned by symbol rather than by package.
 *
 * `core` legitimately imports `platform.AVFoundation.AVURLAsset` and `AVAssetImageGenerator` for `probe()` and
 * `thumbnail()`, and both are read-only. Banning the whole package would break those, and banning none of it would
 * let a runtime in through the back door.
 */
private val NATIVE_PLAYBACK_AND_EXPORT =
  setOf(
    "platform.AVKit",
    "platform.AVFoundation.AVPlayer*",
    "platform.AVFoundation.AVAssetExportSession*",
    "platform.AVFoundation.AVAssetWriter*",
    "platform.AVFoundation.AVAssetReader*",
    "platform.VideoToolbox",
  )

/**
 * The packages under each backend's internal package that hold what the player consumes, keyed by backend.
 *
 * `preview` holds what only the player reads. The others hold what the backend's own export reads as well, so nothing
 * in them imports `preview` and the player names them directly.
 */
private val PLAYER_PACKAGES: Map<String, Set<String>> =
  mapOf(
    "avfoundation" to setOf("composition", "failure", "time"),
    "media3" to setOf("bounds", "failure", "preview"),
    "ffmpeg" to setOf("preview"),
    "webcodecs" to setOf("preview"),
  )

/**
 * Every backend's internal package. The player drives each backend's preview lowering, and [PLAYER_IMPORTS] is the
 * only part of these it may name.
 */
private val BACKEND_INTERNALS = PLAYER_PACKAGES.keys.map { "dev.jordond.filmstrip.$it.internal" }.toSet()

/**
 * Every package in [PLAYER_PACKAGES], by its full name.
 */
private val PLAYER_IMPORTS =
  PLAYER_PACKAGES
    .flatMap { (backend, packages) -> packages.map { "dev.jordond.filmstrip.$backend.internal.$it" } }
    .toSet()

/**
 * Import prefixes a layer must not name, limited to the ones that are on its classpath anyway. Anything else a rule
 * could ban is absent and fails to compile, so the rule would never fire.
 */
private val FORBIDDEN_IMPORTS: Map<Layer, Set<String>> =
  mapOf(
    Layer.Core to NATIVE_PLAYBACK_AND_EXPORT,
    Layer.Transform to NATIVE_PLAYBACK_AND_EXPORT,
    Layer.Backend to setOf("androidx.media3.exoplayer"),
    Layer.Player to BACKEND_INTERNALS,
    // The material ban is what keeps compose-ui a foundation-only timeline. It covers `material`, `material3` and
    // `material3.adaptive` by prefix, so a tool panel or a styled control cannot be written there.
    Layer.ComposeUi to setOf("androidx.compose.material"),
    Layer.Io to NATIVE_PLAYBACK_AND_EXPORT,
    Layer.IoCompose to NATIVE_PLAYBACK_AND_EXPORT,
  )

/**
 * The one hole in the backend's `androidx.media3.exoplayer` ban.
 *
 * A source with several audio tracks plays the one the plan chose for its clip, and media3 only lets a caller pick a
 * track through the track selector its asset loader runs or the media source factory its composition player builds
 * each item's source with. So the media3 backend may name the track selection package, `MediaSource` for its
 * `Factory`, `DefaultMediaSourceFactory`, which it wraps, and the four types that factory's settings take, which it
 * hands on. Nothing here builds or drives a player, which is what the ban keeps out.
 */
private val MEDIA3_TRACK_CHOICE_IMPORTS =
  setOf(
    "androidx.media3.exoplayer.trackselection",
    "androidx.media3.exoplayer.source.MediaSource",
    "androidx.media3.exoplayer.source.DefaultMediaSourceFactory",
    "androidx.media3.exoplayer.drm.DrmSessionManagerProvider",
    "androidx.media3.exoplayer.upstream.LoadErrorHandlingPolicy",
    "androidx.media3.exoplayer.upstream.CmcdConfiguration",
    "androidx.media3.exoplayer.util.ReleasableExecutor",
  )

/**
 * Import prefixes a layer may name even though one of its [FORBIDDEN_IMPORTS] covers them.
 */
private val ALLOWED_IMPORTS: Map<Layer, Set<String>> =
  mapOf(
    Layer.Backend to MEDIA3_TRACK_CHOICE_IMPORTS,
    Layer.Player to PLAYER_IMPORTS,
  )

/**
 * The import prefixes [checkForbiddenImports][ForbiddenImportsTask] fails this layer on, or null when it checks none.
 */
internal val Layer.forbiddenImports: Set<String>? get() = FORBIDDEN_IMPORTS[this]

/**
 * The imports this layer may name even though one of its [forbiddenImports] covers them.
 */
internal val Layer.allowedImports: Set<String> get() = COMPOSE_ANNOTATION_IMPORTS + ALLOWED_IMPORTS[this].orEmpty()

/**
 * Which resolvable configurations represent "what a consumer actually gets". Confirm against
 * `./gradlew :filmstrip-core:resolvableConfigurations` when KGP moves, and note that [CheckLayeringTask] fails loudly
 * if this list stops matching anything.
 */
private val CHECKED_SUFFIXES =
  listOf(
    "CompileClasspath", // jvm, android, wasmJs, and the metadata compile classpath
    "RuntimeClasspath", // jvm, android, wasmJs
    "CompileKlibraries", // native targets
  )

/**
 * Registers the import guard and the resolved-dependency guard, and hangs both off each module's `check`.
 *
 * Called from the root project, which is where [LAYERS] lives.
 */
fun Project.configureArchitectureGuards() {
  require(this == rootProject) { "configureArchitectureGuards() must be applied to the root project" }

  val unlayered = subprojects.map { it.path }.filter { it.startsWith(":filmstrip") && it !in LAYERS }
  require(unlayered.isEmpty()) {
    "Missing from LAYERS in build-logic Layering.kt: ${unlayered.sorted().joinToString()}"
  }

  LAYERS.forEach { (path, module) ->
    val target = project(path)

    // Guard A: source imports. Cheap, and catches the mistake people actually make.
    module.forbiddenImports?.let { forbidden ->
      val importsTask =
        target.tasks.register<ForbiddenImportsTask>("checkForbiddenImports") {
          group = "verification"
          description = "Verifies $path contains no forbidden imports."
          moduleName.set(path)
          forbiddenPrefixes.set(forbidden)
          allowedPrefixes.set(module.allowedImports)
          sources.from(target.layout.projectDirectory.dir("src"))
        }

      target.pluginManager.withPlugin("base") { target.tasks.named("check") { dependsOn(importsTask) } }
    }

    // Guard B: resolved coordinates. Thorough, and not free, so `check` only and never `assemble`.
    val layeringTask =
      target.tasks.register<CheckLayeringTask>("checkLayering") {
        group = "verification"
        description = "Verifies $path obeys the filmstrip layering contract."
        moduleName.set(path)
        layer.set(module.name)
        layers.set(LAYERS.mapValues { it.value.name })
        allowedLayers.set(module.mayDependOn.map(Layer::name))
        allowedTestLayers.set((module.mayDependOn + module.mayTestWith + Layer.Fixtures).map(Layer::name))
        forbiddenExternals.set(FORBIDDEN_EXTERNALS[module].orEmpty())
        allowedExternals.set(COMPOSE_ANNOTATIONS + MEDIA3_EFFECT_TYPES)
      }

    // Configurations only exist once the module has been evaluated.
    target.afterEvaluate {
      val checked = configurations.filter { it.isCanBeResolved && CHECKED_SUFFIXES.any(it.name::endsWith) }
      val mainRoots = objects.listProperty<ResolvedComponentResult>()
      val testRoots = objects.listProperty<ResolvedComponentResult>()
      checked.forEach {
        val roots = if (it.name.contains("Test")) testRoots else mainRoots
        roots.add(it.incoming.resolutionResult.rootComponent)
      }

      layeringTask.configure {
        inspectedConfigurations.set(checked.map { it.name }.toSet())
        mainIds.set(mainRoots.map { roots -> roots.flatMap(::flattenIds).toSet() })
        testIds.set(testRoots.map { roots -> roots.flatMap(::flattenIds).toSet() })
      }
    }

    target.pluginManager.withPlugin("base") { target.tasks.named("check") { dependsOn(layeringTask) } }
  }
}

/**
 * Walks a resolved graph into flat `":path"` / `"group:artifact"` ids.
 */
private fun flattenIds(root: ResolvedComponentResult): Set<String> {
  val seen = mutableSetOf<ResolvedComponentResult>()
  val ids = mutableSetOf<String>()
  val queue = ArrayDeque(listOf(root))

  while (queue.isNotEmpty()) {
    val component = queue.removeFirst()
    if (!seen.add(component)) continue

    ids +=
      when (val id = component.id) {
        is ProjectComponentIdentifier -> id.projectPath
        is ModuleComponentIdentifier -> "${id.group}:${id.module}"
        else -> id.displayName
      }

    component.dependencies.filterIsInstance<ResolvedDependencyResult>().forEach { queue.addLast(it.selected) }
  }

  return ids
}
