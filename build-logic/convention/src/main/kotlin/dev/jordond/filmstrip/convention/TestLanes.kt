package dev.jordond.filmstrip.convention

import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.file.ConfigurableFileCollection
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.provider.Property
import org.gradle.api.provider.ValueSource
import org.gradle.api.provider.ValueSourceParameters
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.kotlin.dsl.configure
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.targets.js.testing.KotlinJsTest
import org.jetbrains.kotlin.gradle.targets.jvm.tasks.KotlinJvmTest
import org.jetbrains.kotlin.gradle.tasks.KotlinTest
import java.io.File

private const val MEDIA_PROPERTY = "media"
private const val HARDWARE_PROPERTY = "hardware"
private const val MEASURE_PROPERTY = "measure"
private const val PURE_EVERYWHERE_PROPERTY = "pureEverywhere"

/**
 * The test classes one run of Gradle leaves in, read from its `-Pmedia`, `-Phardware` and `-Pmeasure` properties. Any
 * value of a property turns its classes on, `-Pmedia=false` included.
 *
 * Plain classes and `*SmokeTest` classes always run. `*MediaTest` classes need [media], `*HardwareTest` classes need
 * [hardware] and `*MeasureTest` classes need [measure]. Each property lifts only its own suffix, so the local lane
 * passes `-Pmedia -Phardware` to run both.
 */
internal data class TestLane(
  val media: Boolean,
  val hardware: Boolean,
  val measure: Boolean,
)

/**
 * The exclude patterns a test task gets in this [TestLane], two per suffix that is off.
 *
 * The first pattern matches what the JVM, Android host and Kotlin/Native runners compare it with, a test class name.
 * The browser runner compares a pattern with the whole `Class.method` name and anchors both ends, so it needs the
 * second pattern to see the class at all.
 */
internal fun TestLane.excludedPatterns(): List<String> =
  buildList {
    if (!media) add("MediaTest")
    if (!hardware) add("HardwareTest")
    if (!measure) add("MeasureTest")
  }.flatMap { classPatterns("*$it") }

/**
 * The exclude patterns a test task on [taskTarget] gets for the classes in a module's `pureTest` source set.
 *
 * Pure classes run on the module's [reference] target only, and on every target under `-PpureEverywhere`. A task with
 * no Kotlin target, the Android host one, is never the reference.
 */
internal fun pureExcludedPatterns(
  pureClasses: List<String>,
  taskTarget: String?,
  reference: String,
  pureEverywhere: Boolean,
): List<String> = if (pureEverywhere || taskTarget == reference) emptyList() else pureClasses.flatMap(::classPatterns)

/**
 * True when this lane runs the class named [className], going by the same suffixes [excludedPatterns] drops.
 */
internal fun TestLane.runs(className: String): Boolean {
  val simpleName = className.substringAfterLast('.')
  return (media || !simpleName.endsWith("MediaTest")) &&
    (hardware || !simpleName.endsWith("HardwareTest")) &&
    (measure || !simpleName.endsWith("MeasureTest"))
}

/**
 * The target a module's pure classes run on, the first of `jvm`, `macosArm64` and `js` the module declares. That is
 * `jvm` for `filmstrip.library`, `macosArm64` for `filmstrip.library.apple` and `js` for `filmstrip.library.web`.
 */
internal fun pureReferenceTarget(targetNames: Collection<String>): String =
  PURE_REFERENCE_ORDER.firstOrNull { it in targetNames }
    ?: throw GradleException("pureTests() needs one of ${PURE_REFERENCE_ORDER.joinToString()} as a target")

private val PURE_REFERENCE_ORDER = listOf("jvm", "macosArm64", "js")

/**
 * Applies the [TestLane] Gradle was given to every [AbstractTestTask] in this project, then the shard and iOS rules
 * [configureTestShards] sets out and the fork count [configureTestForks] reads.
 *
 * That covers the JVM and Android host `Test` tasks, `KotlinNativeTest` and the browser `KotlinJsTest` tasks alike.
 * Android instrumentation tasks (`connected*AndroidTest`) and the iPhone `run.sh` binary launch are not test tasks, so
 * they run every class. An excluded class is absent from the results rather than counted as passed. The filter acts at
 * run time only, so every test source still compiles.
 *
 * A task left with no tests after the exclusion passes. A `--tests` pattern that matches nothing fails on every test
 * task, and so does naming an excluded class without its property. The JVM and Android host tasks do that by default,
 * and [configureNoMatchingTestsCheck] turns it back on for `KotlinNativeTest` and `KotlinJsTest`.
 */
internal fun Project.configureTestLanes() {
  val lane =
    TestLane(
      media = providers.gradleProperty(MEDIA_PROPERTY).isPresent,
      hardware = providers.gradleProperty(HARDWARE_PROPERTY).isPresent,
      measure = providers.gradleProperty(MEASURE_PROPERTY).isPresent,
    )
  val patterns = lane.excludedPatterns()
  tasks.withType(AbstractTestTask::class.java).configureEach { excludeTestClasses(patterns) }
  configureTestShards(lane)
  configureTestForks()
}

/**
 * Adds the `pureTest` source set, for logic tests that need no platform. They compile into every test compilation of
 * the module and run on one reference target, as [configurePureTestLane] sets out.
 *
 * `pureTest` depends on `commonTest`, so its tests see every shared fixture there. Each of the module's JVM, Android
 * host, browser and Kotlin/Native test sets depends on it. The Android device test set does not, since it does not see
 * `commonTest` either.
 */
fun Project.pureTests() {
  extensions.configure<KotlinMultiplatformExtension> {
    val pureTest = sourceSets.create("pureTest") { dependsOn(sourceSets.commonTest.get()) }
    PURE_TEST_SOURCE_SETS.mapNotNull { sourceSets.findByName(it) }.forEach { it.dependsOn(pureTest) }
    this@pureTests.configurePureTestLane(pureReferenceTarget(targets.map { it.name }))
  }
}

private val PURE_TEST_SOURCE_SETS =
  listOf(
    "jvmTest",
    "androidHostTest",
    "jsTest",
    "wasmJsTest",
    "macosArm64Test",
    "iosSimulatorArm64Test",
    "iosArm64Test",
  )

/**
 * Runs the classes in this module's `pureTest` source set on its [reference] target only, unless Gradle is given
 * `-PpureEverywhere`.
 *
 * A class name is the file's package plus its file name, so configuration fails naming the file when one holding a
 * `@Test` declares no class of that name.
 */
internal fun Project.configurePureTestLane(reference: String) {
  val pureEverywhere = providers.gradleProperty(PURE_EVERYWHERE_PROPERTY).isPresent
  val pureClasses =
    providers
      .of(TestClassesSource::class.java) {
        parameters.directories.from(layout.projectDirectory.dir("src/pureTest/kotlin"))
        parameters.fileNamedClasses.set(true)
      }.get()
  tasks.withType(AbstractTestTask::class.java).configureEach {
    excludeTestClasses(pureExcludedPatterns(pureClasses, kotlinTargetName(), reference, pureEverywhere))
  }
}

/**
 * Registers `compileTests` in this project, for the test compilations no fast-lane test task builds. That is the iOS
 * simulator and `iosArm64` test klibs, the wasm test executable and the Android device test. Run from the root, it
 * covers the whole build.
 */
internal fun Project.configureCompileTests() {
  val module = this
  tasks.register("compileTests") {
    group = "verification"
    description = "Compiles the test sources that no fast-lane test task runs."
    dependsOn(
      module.provider {
        val kotlin = module.extensions.findByType(KotlinMultiplatformExtension::class.java)
        if (kotlin == null) {
          emptyList()
        } else {
          compileOnlyTestTasks(module.path, kotlin.targets.names, kotlin.sourceSets.names, module.tasks.names)
        }
      },
    )
  }
}

/**
 * The compile tasks `compileTests` runs in the project at [path], one for each of the iOS simulator, `iosArm64` and
 * `wasmJs` targets in [targets] and one for an `androidDeviceTest` source set in [sourceSets].
 *
 * It fails naming the missing task when one of those is not in [taskNames], so a task a plugin renames stops the build
 * rather than dropping out of `compileTests`.
 */
internal fun compileOnlyTestTasks(
  path: String,
  targets: Collection<String>,
  sourceSets: Collection<String>,
  taskNames: Collection<String>,
): List<String> {
  val expected =
    COMPILE_ONLY_TARGET_TASKS.filterKeys { it in targets }.values +
      listOfNotNull(ANDROID_DEVICE_TEST_TASK.takeIf { ANDROID_DEVICE_TEST_SOURCE_SET in sourceSets })
  val missing = expected.filterNot { it in taskNames }
  if (missing.isNotEmpty()) {
    throw GradleException("$path has no ${missing.joinToString()}, so compileTests cannot compile its tests")
  }
  return expected
}

private val COMPILE_ONLY_TARGET_TASKS =
  mapOf(
    "iosSimulatorArm64" to "compileTestKotlinIosSimulatorArm64",
    "iosArm64" to "compileTestKotlinIosArm64",
    "wasmJs" to "compileTestDevelopmentExecutableKotlinWasmJs",
  )

private const val ANDROID_DEVICE_TEST_SOURCE_SET = "androidDeviceTest"
private const val ANDROID_DEVICE_TEST_TASK = "compileAndroidDeviceTest"

/**
 * Makes every [KotlinTest] task given `--tests` on the command line fail when no test matched, the way a JVM `Test`
 * task does. A task run without `--tests` keeps the Kotlin Gradle plugin's default, which never fails on no match.
 */
internal fun Project.configureNoMatchingTestsCheck() {
  tasks.withType(KotlinTest::class.java).configureEach {
    doFirst {
      val testFilter = testFilter()
      if (testFilter.commandLineIncludePatterns.isNotEmpty()) {
        testFilter.isFailOnNoMatchingTests = true
      }
    }
  }
}

/**
 * Lists the test class names in a set of Kotlin directories, sorted.
 *
 * With [Parameters.fileNamedClasses], the way the `pureTest` lane reads its directory, each file gives its package plus
 * its file name. Configuration then fails naming the file when one holding a `@Test` declares no class named after
 * itself, since its tests would run everywhere unnoticed. Without it, each top-level class in a file holding a `@Test`
 * is listed by its package and name.
 */
internal abstract class TestClassesSource : ValueSource<List<String>, TestClassesSource.Parameters> {
  interface Parameters : ValueSourceParameters {
    val directories: ConfigurableFileCollection
    val fileNamedClasses: Property<Boolean>
  }

  override fun obtain(): List<String> {
    val fileNamed = parameters.fileNamedClasses.get()
    return parameters.directories.files
      .filter { it.isDirectory }
      .flatMap { root -> root.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList() }
      .flatMap { file -> if (fileNamed) listOf(fileNamedClass(file)) else declaredTestClasses(file) }
      .distinct()
      .sorted()
  }

  private fun fileNamedClass(file: File): String {
    val text = file.readText()
    val name = file.nameWithoutExtension
    val declaresClass = Regex("""^\s*(?:[a-z]+\s+)*class\s+${Regex.escape(name)}\b""", RegexOption.MULTILINE)
    if (TEST_ANNOTATION.containsMatchIn(text) && !declaresClass.containsMatchIn(text)) {
      throw GradleException("$file has a @Test but no class named $name, so the pure test lane cannot find it")
    }
    return qualified(text, name)
  }

  private fun declaredTestClasses(file: File): List<String> {
    val text = file.readText()
    if (!TEST_ANNOTATION.containsMatchIn(text)) return emptyList()
    return TOP_LEVEL_CLASS.findAll(text).map { qualified(text, it.groupValues[1]) }.toList()
  }

  private fun qualified(
    text: String,
    name: String,
  ): String {
    val pkg = PACKAGE.find(text)?.groupValues?.get(1)
    return if (pkg == null) name else "$pkg.$name"
  }

  private companion object {
    val TEST_ANNOTATION = Regex("""@Test\b""")
    val PACKAGE = Regex("""^\s*package\s+([\w.]+)""", RegexOption.MULTILINE)
    val TOP_LEVEL_CLASS = Regex("""^(?:@\w+(?:\([^)\n]*\))?\s+)*(?:[a-z]+\s+)*class\s+(\w+)""", RegexOption.MULTILINE)
  }
}

internal fun classPatterns(name: String): List<String> = listOf(name, "$name.*")

internal fun AbstractTestTask.excludeTestClasses(patterns: List<String>) {
  if (patterns.isEmpty()) return
  patterns.forEach { filter.excludeTestsMatching(it) }
  doFirst {
    val testFilter = testFilter()
    if (testFilter.includePatterns.isEmpty() && testFilter.commandLineIncludePatterns.isEmpty()) {
      testFilter.isFailOnNoMatchingTests = false
    }
  }
}

// A browser task's own targetName joins the target and its environment, "js, browser", so its compilation names it.
private fun AbstractTestTask.kotlinTargetName(): String? =
  when (this) {
    is KotlinJvmTest -> targetName
    is KotlinJsTest -> compilation.target.name
    is KotlinTest -> targetName
    else -> null
  }

private fun Task.testFilter(): DefaultTestFilter = (this as AbstractTestTask).filter as DefaultTestFilter
