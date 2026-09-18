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
 * the layers in [mayDependOn].
 */
internal enum class Layer(
  val mayDependOn: Set<Layer>,
) {
  Core(emptySet()),
  Effects(setOf(Core)),
  Transform(setOf(Core, Effects)),
  Backend(setOf(Core, Effects, Transform)),
  Player(setOf(Core, Effects, Transform, Backend)),
  Compose(setOf(Core, Effects, Transform, Backend, Player)),
  ComposeUi(setOf(Core, Effects, Transform, Backend, Player, Compose)),
  Umbrella(setOf(Core, Effects, Transform, Backend, Player)),

  // The io adapters branch off core rather than climbing the ladder, and nothing above them takes one.
  Io(setOf(Core)),
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
    ":filmstrip-transform" to Layer.Transform,
    ":filmstrip-transform-media3" to Layer.Backend,
    ":filmstrip-transform-avfoundation" to Layer.Backend,
    ":filmstrip-transform-webcodecs" to Layer.Backend,
    ":filmstrip-transform-ffmpeg" to Layer.Backend,
    ":filmstrip-player" to Layer.Player,
    ":filmstrip-compose" to Layer.Compose,
    ":filmstrip-compose-ui" to Layer.ComposeUi,
    ":filmstrip" to Layer.Umbrella,
    ":filmstrip-io-filekit" to Layer.Io,
    ":filmstrip-io-kotlinx" to Layer.Io,
    ":filmstrip-test" to Layer.Fixtures,
  )

private val COMPOSE = setOf("org.jetbrains.compose", "androidx.compose")

private val MEDIA3_RUNTIMES = setOf("androidx.media3:media3-transformer", "androidx.media3:media3-exoplayer")

/**
 * The one hole in [COMPOSE].
 *
 * `runtime-annotation` carries `@Immutable` and `@Stable` and nothing else: no runtime, no composer, and no
 * transitive dependency of its own. `core` takes it so its value types declare their own stability, which is the only
 * way a consumer's call site can compare an `EditComposition` or a `TimeRange` by value rather than by identity. It
 * reaches every module from there, so the allowance is repo-wide rather than named layer by layer.
 */
private val COMPOSE_ANNOTATIONS = setOf("androidx.compose.runtime:runtime-annotation")

private val COMPOSE_ANNOTATION_IMPORTS = setOf("androidx.compose.runtime.Immutable", "androidx.compose.runtime.Stable")

/**
 * Coordinate prefixes that must not reach a layer's resolved classpath by any route.
 */
private val FORBIDDEN_EXTERNALS: Map<Layer, Set<String>> =
  mapOf(
    Layer.Core to COMPOSE + "androidx.media3",
    Layer.Effects to COMPOSE + MEDIA3_RUNTIMES,
    Layer.Transform to COMPOSE + MEDIA3_RUNTIMES,
    Layer.Backend to COMPOSE,
    Layer.Player to COMPOSE,
    Layer.Umbrella to COMPOSE,
    Layer.Io to COMPOSE + "androidx.media3",
    Layer.Fixtures to COMPOSE,
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
 * Import prefixes a layer must not name, limited to the ones that are on its classpath anyway. Anything else a rule
 * could ban is absent and fails to compile, so the rule would never fire.
 */
private val FORBIDDEN_IMPORTS: Map<Layer, Set<String>> =
  mapOf(
    Layer.Core to NATIVE_PLAYBACK_AND_EXPORT,
    Layer.Transform to NATIVE_PLAYBACK_AND_EXPORT,
    Layer.Backend to setOf("androidx.media3.exoplayer"),
    Layer.Compose to setOf("androidx.media3.transformer"),
    // The material ban is what keeps compose-ui a foundation-only timeline. It covers `material`, `material3` and
    // `material3.adaptive` by prefix, so a tool panel or a styled control cannot be written there.
    Layer.ComposeUi to setOf("androidx.media3.transformer", "androidx.compose.material"),
    Layer.Io to NATIVE_PLAYBACK_AND_EXPORT,
  )

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
    FORBIDDEN_IMPORTS[module]?.let { forbidden ->
      val importsTask =
        target.tasks.register<ForbiddenImportsTask>("checkForbiddenImports") {
          group = "verification"
          description = "Verifies $path contains no forbidden imports."
          moduleName.set(path)
          forbiddenPrefixes.set(forbidden)
          allowedPrefixes.set(COMPOSE_ANNOTATION_IMPORTS)
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
        allowedTestLayers.set((module.mayDependOn + Layer.Fixtures).map(Layer::name))
        forbiddenExternals.set(FORBIDDEN_EXTERNALS[module].orEmpty())
        allowedExternals.set(COMPOSE_ANNOTATIONS)
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
