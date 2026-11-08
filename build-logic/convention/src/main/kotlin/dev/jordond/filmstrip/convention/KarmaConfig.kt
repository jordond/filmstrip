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
 * The directory holds [script], which every web module shares, and [timesScript], which writes the module's class
 * times, beside a copy of the module's own `karma.config.d` files. The Kotlin plugin appends every file there to the
 * Karma config in name order.
 */
abstract class KarmaConfigTask : DefaultTask() {
  /**
   * The config every web module's browser tests start from.
   */
  @get:Input
  abstract val script: Property<String>

  /**
   * The reporter config that writes each test class's run time under this module's build directory.
   */
  @get:Input
  abstract val timesScript: Property<String>

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
   * Clears [outputDirectory] and writes [script] and [timesScript] into it beside a copy of each of [moduleConfigs].
   *
   * It fails when a module's own file has the name of either generated config.
   */
  @TaskAction
  fun write() {
    val output = outputDirectory.get().asFile
    output.listFiles()?.forEach { it.deleteRecursively() }

    output.resolve(SHARED_CONFIG).writeText(script.get())
    output.resolve(TIMES_CONFIG).writeText(timesScript.get())
    moduleConfigs.files.sortedBy { it.name }.forEach { file ->
      if (file.name == SHARED_CONFIG || file.name == TIMES_CONFIG) {
        throw GradleException("${file.path} has the name of a generated Karma config, rename it")
      }
      file.copyTo(output.resolve(file.name))
    }
  }

  internal companion object {
    const val SHARED_CONFIG = "filmstrip-shared.js"
    const val TIMES_CONFIG = "filmstrip-times.js"
  }
}

/**
 * Points every browser test task at a Karma config directory holding [BROWSER_TEST_CONFIG], this module's
 * [browserTimesConfig] and the module's own `karma.config.d` files.
 *
 * It registers `writeKarmaConfig`, which every Kotlin JS and wasm test task depends on, and hands that directory to
 * each task that runs on Karma.
 */
internal fun Project.configureBrowserTests() {
  val moduleConfigDirectory = layout.projectDirectory.dir("karma.config.d")
  val configDirectory = layout.buildDirectory.dir("karma.config.d")
  val timesDirectory = layout.buildDirectory.dir(KARMA_TIMES_DIRECTORY)
  val writeKarmaConfig =
    tasks.register<KarmaConfigTask>("writeKarmaConfig") {
      description = "Writes the Karma config the browser tests read."
      script.set(BROWSER_TEST_CONFIG)
      timesScript.set(timesDirectory.map { browserTimesConfig(it.asFile.path) })
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
 * It also defines the `FilmstripChrome` launcher and runs every module's browser tests on it. That launcher gives
 * headless Chrome WebGL through ANGLE over SwiftShader, mutes its output and lets an AudioContext run without a user
 * gesture.
 */
internal val BROWSER_TEST_CONFIG: String =
  """
  // Karma's stock ChromeHeadless has no GL at all, so getContext('webgl2') returns null and every
  // export fails on a null context. These flags give it ANGLE over SwiftShader, a software
  // rasteriser, which is enough to composite correctly but far too slow to time anything against.
  //
  // Set FILMSTRIP_CHROME_FLAGS to override, for a run against a real GPU.
  // FILMSTRIP_CHROME_BASE=Chrome with FILMSTRIP_CHROME_FLAGS='--headless=new --use-angle=metal'
  // runs the same tests against a real GPU, because karma-chrome-launcher's ChromeHeadless base
  // passes --disable-gpu and nothing later can undo it.
  //
  // The audio flags are always appended, overrides included. --mute-audio keeps the fixtures' tone
  // off the speakers, and the autoplay policy lets an AudioContext run with no gesture to unlock it.
  config.set({
    browsers: ['FilmstripChrome'],
    browserNoActivityTimeout: 600000,
    browserDisconnectTimeout: 60000,
    captureTimeout: 120000,
    client: {
      mocha: {
        timeout: 300000,
      },
    },
    customLaunchers: {
      FilmstripChrome: {
        base: process.env.FILMSTRIP_CHROME_BASE || 'ChromeHeadless',
        flags: (process.env.FILMSTRIP_CHROME_FLAGS || [
          '--use-gl=angle',
          '--use-angle=swiftshader',
          '--enable-unsafe-swiftshader',
          '--ignore-gpu-blocklist',
          '--no-sandbox',
        ].join(' ')).split(' ').filter(Boolean).concat([
          '--mute-audio',
          '--autoplay-policy=no-user-gesture-required',
        ]),
      },
    },
  });
  """.trimIndent() + "\n"

/**
 * Where a module's browser test times go, relative to its build directory. Each target, `js` or `wasm`, writes its own
 * `<target>/TEST-<target>-karma.xml` there, so the two count as separate results.
 */
internal const val KARMA_TIMES_DIRECTORY = "test-results/karma"

/**
 * The Karma config that writes each test's real run time as JUnit XML into [directory].
 *
 * The times the Kotlin reporter hands Gradle are all zero, so this `filmstrip-times` reporter keeps the time Mocha
 * measured for every test that ran and writes them once the run completes. A test's class is its suite names joined
 * with dots. `.github/scripts/slowest-tests.py` reads the file like any other result.
 */
internal fun browserTimesConfig(directory: String): String =
  """
  // Writes the time Mocha measured for each test, since the Kotlin reporter hands Gradle zeros.
  ;(function(config) {
    const fs = require('fs');
    const path = require('path');
    const directory = ${jsString(directory)};
    const entities = { '<': '&lt;', '>': '&gt;', '&': '&amp;', '"': '&quot;' };
    const escape = (text) => String(text).replace(/[<>&"]/g, (c) => entities[c]);
    function FilmstripTimes(karmaConfig) {
      const target = path.basename(path.resolve(karmaConfig.basePath, '..', '..'));
      const cases = [];
      this.adapters = [];
      this.onSpecComplete = function(browser, result) {
        if (result.skipped) return;
        cases.push({ classname: result.suite.join('.'), name: result.description, seconds: result.time / 1000 });
      };
      this.onRunComplete = function() {
        const total = cases.reduce((sum, test) => sum + test.seconds, 0);
        const lines = cases.map((test) =>
          '  <testcase classname="' + escape(test.classname) + '" name="' + escape(test.name) +
            '" time="' + test.seconds.toFixed(3) + '"/>\n');
        fs.mkdirSync(path.join(directory, target), { recursive: true });
        fs.writeFileSync(
          path.join(directory, target, 'TEST-' + target + '-karma.xml'),
          '<?xml version="1.0" encoding="UTF-8"?>\n' +
            '<testsuite name="' + target + '-karma" tests="' + cases.length + '" time="' + total.toFixed(3) + '">\n' +
            lines.join('') + '</testsuite>\n',
        );
      };
    }
    FilmstripTimes.${'$'}inject = ['config'];
    config.plugins = config.plugins || [];
    config.plugins.push({ 'reporter:filmstrip-times': ['type', FilmstripTimes] });
    config.reporters = (config.reporters || []).concat(['filmstrip-times']);
  })(config);
  """.trimIndent() + "\n"

private fun jsString(text: String): String = "'" + text.replace("\\", "\\\\").replace("'", "\\'") + "'"
