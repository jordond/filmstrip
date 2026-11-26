package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.provider.SetProperty
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction

/**
 * Guard A. Catches a forbidden `import` before it can become a dependency.
 */
@CacheableTask
abstract class ForbiddenImportsTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  /**
   * Import prefixes that must not appear, e.g. `androidx.compose`, `org.jetbrains.compose`.
   *
   * A rule matches the exact FQN or anything under it as a package. Kotlin/Native flattens every
   * ObjC class to `platform.<Framework>.<Class>`, so a rule may end in `*` to cover a class
   * family: `platform.AVFoundation.AVPlayer*` catches `AVPlayerItem` and `AVPlayerLayer` too.
   */
  @get:Input
  abstract val forbiddenPrefixes: SetProperty<String>

  /**
   * Imports the rules do not apply to, checked before [forbiddenPrefixes]. Each matches the exact FQN or anything under
   * it as a package, the way a rule without a `*` does.
   */
  @get:Input
  abstract val allowedPrefixes: SetProperty<String>

  /**
   * Import rules checked only in a test source set, on top of [forbiddenPrefixes]. They match the way
   * [forbiddenPrefixes] do, and [allowedPrefixes] does not lift them.
   */
  @get:Input
  abstract val testForbiddenPrefixes: SetProperty<String>

  @get:Input
  abstract val moduleName: Property<String>

  @TaskAction
  fun check() {
    val prefixes = forbiddenPrefixes.get()
    val testPrefixes = testForbiddenPrefixes.get()
    val allowed = allowedPrefixes.get()
    val violations = mutableListOf<String>()
    var testOnly = false

    sources.files.forEach { root ->
      root.walkTopDown().filter { it.isFile && it.extension == "kt" }.forEach { file ->
        val inTest = isTestSource(file.relativeTo(root).invariantSeparatorsPath)
        file.useLines { lines ->
          lines.withIndex().filter { (_, line) -> line.startsWith("import ") }.forEach { (i, line) ->
            val fqn = line.removePrefix("import ").substringBefore(' ').trim()
            val testRule = inTest && isForbiddenImport(fqn, testPrefixes, emptySet())
            if (isForbiddenAt(inTest, fqn, prefixes, testPrefixes, allowed)) {
              violations += "${file.path}:${i + 1}: ${line.trim()}"
              testOnly = testOnly || testRule
            }
          }
        }
      }
    }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("Forbidden imports in ${moduleName.get()} (${violations.size}):")
          violations.forEach { appendLine("  $it") }
          appendLine()
          if (testOnly) appendLine("Tests write their files under TestScratch.directory from filmstrip-test.")
          appendLine("See LAYERS in build-logic Layering.kt for the layering contract.")
        },
      )
    }
  }
}

/**
 * Whether [relativePath], a path under a module's `src` directory, sits in a test source set.
 *
 * A test source set is one whose directory name ends in `Test`, like `commonTest`, `appleHardwareTest` or `pureTest`.
 */
internal fun isTestSource(relativePath: String): Boolean = relativePath.substringBefore('/').endsWith("Test")

/**
 * Whether [fqn] is forbidden in a file, which is a test source when [inTest] is true.
 *
 * Every file is held to [forbidden] less [allowed]. A test source is also held to [testForbidden], which [allowed]
 * does not lift.
 */
internal fun isForbiddenAt(
  inTest: Boolean,
  fqn: String,
  forbidden: Set<String>,
  testForbidden: Set<String>,
  allowed: Set<String>,
): Boolean = (inTest && isForbiddenImport(fqn, testForbidden, emptySet())) || isForbiddenImport(fqn, forbidden, allowed)

/**
 * Whether [fqn] matches one of the [forbidden] rules and none of the [allowed] prefixes.
 *
 * An allowed prefix and a rule without a `*` each match the exact name or anything under it as a package. A rule
 * ending in `*` matches any name starting with the rest of it.
 */
internal fun isForbiddenImport(
  fqn: String,
  forbidden: Set<String>,
  allowed: Set<String>,
): Boolean {
  if (allowed.any { fqn == it || fqn.startsWith("$it.") }) return false
  return forbidden.any { rule ->
    if (rule.endsWith("*")) {
      fqn.startsWith(rule.dropLast(1))
    } else {
      fqn == rule || fqn.startsWith("$rule.")
    }
  }
}
