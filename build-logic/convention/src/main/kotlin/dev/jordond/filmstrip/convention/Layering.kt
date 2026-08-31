package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.api.artifacts.ProjectDependency
import org.gradle.api.artifacts.component.ModuleComponentIdentifier
import org.gradle.api.artifacts.component.ProjectComponentIdentifier
import org.gradle.api.artifacts.result.ResolvedComponentResult
import org.gradle.api.artifacts.result.ResolvedDependencyResult
import org.gradle.kotlin.dsl.listProperty
import org.gradle.kotlin.dsl.register

data class Layer(
  /**
   * Project paths that may appear anywhere in this module's resolved graph, direct or transitive.
   */
  val allowedProjects: Set<String>,
  /**
   * Project paths this module may declare a dependency on. Narrower than [allowedProjects]
   * wherever a project is only supposed to arrive through another one.
   */
  val directProjects: Set<String>,
  val forbiddenExternals: Set<String>,
  val forbiddenImports: Set<String> = emptySet(),
  val allowedNpm: Set<String> = emptySet(),
)

private val COMPOSE = setOf("org.jetbrains.compose", "androidx.compose")

/**
 * The fixture module a test source set may reach for.
 *
 * It carries `:filmstrip-core` and nothing else, so allowing it widens a module's graph by nothing
 * that module does not already have. Guard B walks test configurations along with main ones, so a
 * module whose tests compare frames has to name it.
 */
private val TEST_FIXTURES = setOf(":filmstrip-test")

/**
 * The single source of truth for the layering contract:
 * `core -> effects -> {transform, transform-media3, transform-avfoundation, transform-webcodecs,
 * transform-ffmpeg} -> player -> {compose, filmstrip}`.
 *
 * `filmstrip-test` is absent: fixtures may depend on everything.
 */
private val LAYERING: Map<String, Layer> =
  mapOf(
    ":filmstrip-core" to
      Layer(
        // The strictest rule in the map. `core` carries the effect SPI, so this is what keeps a
        // third-party effect author's single dependency free of any runtime and any shader pack.
        allowedProjects = emptySet(),
        directProjects = emptySet(),
        forbiddenExternals = COMPOSE + setOf("androidx.media3", "io.ktor"),
        // NOTE the deny list is SYMBOL-level for AVFoundation, not package-level. `core`
        // legitimately imports platform.AVFoundation.AVURLAsset and AVAssetImageGenerator for
        // probe() and thumbnail(), and those are read-only. What must never
        // appear is a PLAYBACK or EXPORT type. Banning the whole package would break probe;
        // banning none of it would let a runtime in through the back door.
        forbiddenImports =
          COMPOSE +
            setOf(
              "androidx.media3",
              "platform.AVKit",
              "platform.AVFoundation.AVPlayer*",
              "platform.AVFoundation.AVAssetExportSession*",
              "platform.AVFoundation.AVAssetWriter*",
              "platform.AVFoundation.AVAssetReader*",
              "platform.VideoToolbox",
            ),
      ),
    ":filmstrip-effects" to
      Layer(
        allowedProjects = setOf(":filmstrip-core"),
        directProjects = setOf(":filmstrip-core"),
        forbiddenExternals =
          COMPOSE +
            setOf(
              "androidx.media3:media3-transformer",
              "androidx.media3:media3-exoplayer",
            ),
        forbiddenImports = COMPOSE,
      ),
    ":filmstrip-transform" to
      Layer(
        allowedProjects = setOf(":filmstrip-core", ":filmstrip-effects"),
        directProjects = setOf(":filmstrip-core", ":filmstrip-effects"),
        forbiddenExternals = COMPOSE + setOf("androidx.media3:media3-transformer"),
        forbiddenImports =
          COMPOSE +
            setOf(
              "androidx.media3.transformer",
              "androidx.media3.exoplayer",
              "platform.AVKit",
              "platform.AVFoundation.AVPlayer*",
              "platform.AVFoundation.AVAssetExportSession*",
              "platform.AVFoundation.AVAssetWriter*",
              "platform.AVFoundation.AVAssetReader*",
              "platform.VideoToolbox",
            ),
      ),
    ":filmstrip-transform-media3" to
      Layer(
        allowedProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        directProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        forbiddenExternals = COMPOSE,
        forbiddenImports = COMPOSE + setOf("androidx.media3.exoplayer"),
      ),
    ":filmstrip-transform-avfoundation" to
      Layer(
        allowedProjects =
          setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform") + TEST_FIXTURES,
        directProjects =
          setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform") + TEST_FIXTURES,
        forbiddenExternals = COMPOSE + setOf("androidx.media3"),
        forbiddenImports = COMPOSE + setOf("androidx.media3"),
      ),
    ":filmstrip-transform-webcodecs" to
      Layer(
        allowedProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        directProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        forbiddenExternals = COMPOSE + setOf("androidx.media3"),
        forbiddenImports = COMPOSE + setOf("androidx.media3"),
        allowedNpm = setOf("mediabunny"),
      ),
    ":filmstrip-transform-ffmpeg" to
      Layer(
        allowedProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        directProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-transform"),
        forbiddenExternals = COMPOSE + setOf("androidx.media3", "org.bytedeco"),
        forbiddenImports =
          COMPOSE +
            setOf(
              "androidx.media3",
              "org.bytedeco",
              "platform.AVFoundation",
              "platform.AVKit",
              "platform.VideoToolbox",
            ),
      ),
    ":filmstrip-player" to
      Layer(
        allowedProjects =
          setOf(
            ":filmstrip-core",
            ":filmstrip-effects",
            ":filmstrip-transform",
            ":filmstrip-transform-media3",
            ":filmstrip-transform-avfoundation",
            ":filmstrip-transform-webcodecs",
            ":filmstrip-transform-ffmpeg",
          ) + TEST_FIXTURES,
        directProjects =
          setOf(
            ":filmstrip-core",
            ":filmstrip-effects",
            ":filmstrip-transform",
            ":filmstrip-transform-media3",
            ":filmstrip-transform-avfoundation",
            ":filmstrip-transform-webcodecs",
            ":filmstrip-transform-ffmpeg",
          ) + TEST_FIXTURES,
        forbiddenExternals = COMPOSE,
        forbiddenImports = COMPOSE,
      ),
    ":filmstrip-compose" to
      Layer(
        allowedProjects =
          setOf(
            ":filmstrip-core",
            ":filmstrip-effects",
            ":filmstrip-player",
            ":filmstrip-transform",
            ":filmstrip-transform-media3",
            ":filmstrip-transform-avfoundation",
            ":filmstrip-transform-webcodecs",
            ":filmstrip-transform-ffmpeg",
          ),
        directProjects = setOf(":filmstrip-core", ":filmstrip-effects", ":filmstrip-player"),
        forbiddenExternals = emptySet(),
        forbiddenImports = setOf("androidx.media3.transformer"),
      ),
    ":filmstrip" to
      Layer(
        allowedProjects =
          setOf(
            ":filmstrip-core",
            ":filmstrip-effects",
            ":filmstrip-player",
            ":filmstrip-transform",
            ":filmstrip-transform-media3",
            ":filmstrip-transform-avfoundation",
            ":filmstrip-transform-webcodecs",
            ":filmstrip-transform-ffmpeg",
          ),
        directProjects =
          setOf(
            ":filmstrip-core",
            ":filmstrip-effects",
            ":filmstrip-player",
            ":filmstrip-transform",
            ":filmstrip-transform-media3",
            ":filmstrip-transform-avfoundation",
            ":filmstrip-transform-webcodecs",
            ":filmstrip-transform-ffmpeg",
          ),
        forbiddenExternals = COMPOSE,
        forbiddenImports = COMPOSE + setOf("androidx.media3"),
      ),
  )

/**
 * Which resolvable configurations represent "what a consumer actually gets".
 *
 * Confirm against `./gradlew :filmstrip-core:resolvableConfigurations` when KGP moves;
 * [CheckLayeringTask] fails loudly if this list stops matching anything.
 */
private val CHECKED_SUFFIXES =
  listOf(
    "CompileClasspath", // jvm, android, wasmJs, and the metadata compile classpath
    "RuntimeClasspath", // jvm, android, wasmJs
    "CompileKlibraries", // native targets
  )

/**
 * The declarable buckets, as opposed to the resolvable ones in [CHECKED_SUFFIXES].
 *
 * Guard C reads these because an `npm(...)` dependency lands in one of them and never on a
 * resolvable graph. Guard B's direct-dependency half reads them because a resolved graph has
 * already lost the difference between what a module declared and what arrived through something
 * else.
 */
private val DECLARABLE_SUFFIXES = listOf("Implementation", "Api", "CompileOnly", "RuntimeOnly")

/**
 * Registers all three layering guards and hangs them off each module's `check`.
 *
 * Called from the root project, which is where the layering map lives.
 */
fun Project.configureArchitectureGuards() {
  require(this == rootProject) {
    "configureArchitectureGuards() must be applied to the root project"
  }

  LAYERING.forEach { (path, layer) ->
    val target = project(path)

    // Guard A: source imports. Cheap, and catches the mistake people actually make.
    if (layer.forbiddenImports.isNotEmpty()) {
      val importsTask =
        target.tasks.register<ForbiddenImportsTask>("checkForbiddenImports") {
          group = "verification"
          description = "Verifies $path contains no forbidden imports."
          moduleName.set(path)
          forbiddenPrefixes.set(layer.forbiddenImports)
          sources.from(target.layout.projectDirectory.dir("src"))
        }

      target.pluginManager.withPlugin("base") {
        target.tasks.named("check") { dependsOn(importsTask) }
      }
    }

    // Guard B: resolved coordinates. Thorough, and not free, so `check` only and never `assemble`.
    val suffix =
      path
        .removePrefix(":")
        .split('-')
        .joinToString("") { it.replaceFirstChar(Char::uppercase) }

    val layeringTask =
      target.tasks.register<CheckLayeringTask>("checkLayering$suffix") {
        group = "verification"
        description = "Verifies $path obeys the filmstrip layering contract."
        moduleName.set(path)
        allowedProjects.set(layer.allowedProjects)
        directProjects.set(layer.directProjects)
        forbiddenExternals.set(layer.forbiddenExternals)
      }

    // Configurations only exist once the module has been evaluated.
    target.afterEvaluate {
      val checked =
        configurations
          .filter { it.isCanBeResolved && CHECKED_SUFFIXES.any(it.name::endsWith) }

      val roots = objects.listProperty<ResolvedComponentResult>()
      checked.forEach { roots.add(it.incoming.resolutionResult.rootComponent) }

      val declared = target.declaredProjectDependencies()

      layeringTask.configure {
        inspectedConfigurations.set(checked.map { it.name }.toSet())
        resolvedIds.set(roots.map { components -> components.flatMap(::flattenIds).toSet() })
        declaredProjects.set(declared)
      }
    }

    // Guard C: npm packages. Reads declarations, resolves nothing, so it is nearly free.
    val npmTask =
      target.tasks.register<CheckNpmDependenciesTask>("checkNpmDependencies$suffix") {
        group = "verification"
        description = "Verifies $path declares no unapproved npm dependency."
        moduleName.set(path)
        allowedPackages.set(layer.allowedNpm)
      }

    target.afterEvaluate {
      val declared = target.npmDependencies()
      npmTask.configure { declaredPackages.set(declared) }
    }

    target.pluginManager.withPlugin("base") {
      target.tasks.named("check") { dependsOn(layeringTask, npmTask) }
    }
  }
}

/**
 * Every project path this module names in a declarable configuration.
 */
private fun Project.declaredProjectDependencies(): Set<String> =
  configurations
    .filter { DECLARABLE_SUFFIXES.any(it.name::endsWith) }
    .flatMap { it.dependencies }
    .filterIsInstance<ProjectDependency>()
    .map { it.path }
    .toSet()

/**
 * Every `npm(...)` dependency this project declares, as `name@version`.
 */
private fun Project.npmDependencies(): Set<String> =
  configurations
    .filter { DECLARABLE_SUFFIXES.any(it.name::endsWith) }
    .flatMap { it.dependencies }
    .filter { it.group == null && it.version != null && it !is ProjectDependency }
    .map { "${it.name}@${it.version}" }
    .toSet()

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

    component.dependencies
      .filterIsInstance<ResolvedDependencyResult>()
      .forEach { queue.addLast(it.selected) }
  }

  return ids
}
