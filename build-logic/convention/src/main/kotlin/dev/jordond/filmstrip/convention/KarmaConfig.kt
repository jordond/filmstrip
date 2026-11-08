package dev.jordond.filmstrip.convention

import org.gradle.api.Action
import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.file.DirectoryProperty
import org.gradle.api.provider.Property
import org.gradle.api.tasks.IgnoreEmptyDirectories
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.InputFiles
import org.gradle.api.tasks.OutputDirectory
import org.gradle.api.tasks.PathSensitive
import org.gradle.api.tasks.PathSensitivity
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTestFramework
import org.jetbrains.kotlin.gradle.targets.js.testing.karma.KotlinKarma

/**
 * Writes the Karma config directory a browser test task reads.
 *
 * The directory holds [script], which every web module shares, beside a copy of the module's own `karma.config.d`
 * files. The Kotlin plugin appends every file there to the Karma config in name order.
 */
abstract class KarmaConfigTask : DefaultTask() {
  /**
   * The config every web module's browser tests start from.
   */
  @get:Input
  abstract val script: Property<String>

  /**
   * The module's own `karma.config.d` files.
   */
  @get:InputFiles
  @get:IgnoreEmptyDirectories
  @get:PathSensitive(PathSensitivity.NAME_ONLY)
  abstract val moduleConfigs: ConfigurableFileCollection

  /**
   * The directory the browser test tasks read their Karma config from.
   */
  @get:OutputDirectory
  abstract val outputDirectory: DirectoryProperty

  /**
   * Clears [outputDirectory] and writes [script] into it beside a copy of each of [moduleConfigs].
   *
   * It fails when a module's own file has the shared config's name.
   */
  @TaskAction
  fun write() {
    val output = outputDirectory.get().asFile
    output.listFiles()?.forEach { it.deleteRecursively() }

    output.resolve(SHARED_CONFIG).writeText(script.get())
    moduleConfigs.files.sortedBy { it.name }.forEach { file ->
      if (file.name == SHARED_CONFIG) {
        throw GradleException("${file.path} has the name of the shared Karma config, rename it")
      }
      file.copyTo(output.resolve(file.name))
    }
  }

  internal companion object {
    const val SHARED_CONFIG = "filmstrip-shared.js"
  }
}

/**
 * Points every browser test task at a Karma config directory holding [BROWSER_TEST_CONFIG] and the module's own
 * `karma.config.d` files.
 *
 * It registers `writeKarmaConfig`, which every Kotlin JS and wasm test task depends on, and hands that directory to
 * each task that runs on Karma.
 */
internal fun Project.configureBrowserTests() {
  val moduleConfigDirectory = layout.projectDirectory.dir("karma.config.d")
  val configDirectory = layout.buildDirectory.dir("karma.config.d")
  val writeKarmaConfig =
    tasks.register<KarmaConfigTask>("writeKarmaConfig") {
      description = "Writes the Karma config the browser tests read."
      script.set(BROWSER_TEST_CONFIG)
      moduleConfigs.from(fileTree(moduleConfigDirectory) { include("*.js") })
      outputDirectory.set(configDirectory)
    }

  tasks.withType<KotlinJsTest>().configureEach {
    inputs
      .files(writeKarmaConfig)
      .withPropertyName("karmaConfig")
      .withPathSensitivity(PathSensitivity.NAME_ONLY)

    val directory = configDirectory.get().asFile
    val useConfig =
      Action<KotlinJsTestFramework> {
        if (this is KotlinKarma) useConfigDirectory(directory)
      }
    testFramework?.let(useConfig::execute)
    onTestFrameworkSet(useConfig)
  }
}

/**
 * Runs [configureBrowserTests] on this project once the Kotlin multiplatform plugin is applied to it.
 *
 * A module that never applies the plugin is left as it is.
 */
internal fun Project.configureBrowserTestsOnMultiplatform() {
  pluginManager.withPlugin("org.jetbrains.kotlin.multiplatform") { configureBrowserTests() }
}

/**
 * The Karma config every module's browser tests share.
 *
 * It sets Mocha's per test timeout to 300 s and Karma's no activity, disconnect and capture windows, in milliseconds.
 */
internal val BROWSER_TEST_CONFIG: String =
  """
  config.set({
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
    captureTimeout: 120000,
    client: {
      mocha: {
        timeout: 300000,
      },
    },
  });
  """.trimIndent() + "\n"
