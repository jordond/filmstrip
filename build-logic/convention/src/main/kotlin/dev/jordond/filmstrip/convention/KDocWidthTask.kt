package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.provider.Property
import org.gradle.api.tasks.CacheableTask
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register

/**
 * The widest a KDoc line may be, counted in columns from the start of the line.
 */
const val KDOC_MAX_WIDTH: Int = 120

/**
 * Fails when a line inside a `/** ... */` block runs past [maxWidth] columns, naming each one as file:line.
 *
 * Only KDoc is checked. Code lines are left to spotless.
 */
@CacheableTask
abstract class KDocWidthTask : DefaultTask() {
  @get:InputFiles
  @get:PathSensitive(PathSensitivity.RELATIVE)
  abstract val sources: ConfigurableFileCollection

  @get:Input
  abstract val maxWidth: Property<Int>

  @get:Input
  abstract val moduleName: Property<String>

  @TaskAction
  fun check() {
    val width = maxWidth.get()
    val violations =
      sources.asFileTree
        .matching { include("**/*.kt") }
        .flatMap { file -> kdocWidthViolations(file.readLines(), width).map { "${file.path}:$it" } }

    if (violations.isNotEmpty()) {
      throw GradleException(
        buildString {
          appendLine("KDoc lines past $width columns in ${moduleName.get()} (${violations.size}):")
          violations.forEach { appendLine("  $it") }
        },
      )
    }
  }
}

/**
 * Returns the 1-based numbers of the lines inside a KDoc block that are longer than [maxWidth].
 *
 * A block opens on a line whose first token is `/**` and closes on the first line holding `*/`. Both of those lines
 * count as part of the block.
 */
fun kdocWidthViolations(
  lines: List<String>,
  maxWidth: Int = KDOC_MAX_WIDTH,
): List<Int> {
  val violations = mutableListOf<Int>()
  var inKDoc = false
  lines.forEachIndexed { index, line ->
    val opens = !inKDoc && line.trimStart().startsWith("/**")
    if (opens) inKDoc = true
    if (inKDoc) {
      if (line.length > maxWidth) violations += index + 1
      val closeSearchStart = if (opens) line.indexOf("/**") + 3 else 0
      if (line.indexOf("*/", closeSearchStart) >= 0) inKDoc = false
    }
  }
  return violations
}

/**
 * Registers `checkKDocWidth` over the `src` directory of this project and hangs it off `check`.
 */
fun Project.configureKDocWidthCheck() {
  val task =
    tasks.register<KDocWidthTask>("checkKDocWidth") {
      group = "verification"
      description = "Verifies $path has no KDoc line past $KDOC_MAX_WIDTH columns."
      moduleName.set(path)
      maxWidth.set(KDOC_MAX_WIDTH)
      sources.from(layout.projectDirectory.dir("src"))
    }

  pluginManager.withPlugin("base") { tasks.named("check") { dependsOn(task) } }
}
