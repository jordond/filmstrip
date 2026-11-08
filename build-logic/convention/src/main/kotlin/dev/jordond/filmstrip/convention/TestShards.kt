package dev.jordond.filmstrip.convention

import com.android.build.api.variant.KotlinMultiplatformAndroidComponentsExtension
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.tasks.testing.AbstractTestTask
import org.gradle.api.tasks.testing.Test
import org.jetbrains.kotlin.gradle.dsl.KotlinMultiplatformExtension
import org.jetbrains.kotlin.gradle.plugin.KotlinSourceSet
import java.io.File
import java.util.zip.CRC32

private const val TEST_SHARD_PROPERTY = "testShard"
private const val TEST_FORKS_PROPERTY = "testForks"
private const val TEST_FORKS_SYSTEM_PROPERTY = "filmstrip.testForks"
private const val TEST_WEIGHTS_PROPERTY = "testWeights"
private const val IOS_SHARED_PROPERTY = "iosShared"
private const val IOS_SIMULATOR_TEST_TASK = "iosSimulatorArm64Test"

/**
 * The checked-in class weights shards are packed from, relative to the root of the build. Each line is a test class
 * name and its run time in seconds. `.github/scripts/slowest-tests.py --weights` writes it from result XML.
 */
internal const val TEST_WEIGHTS_PATH = "gradle/test-weights.txt"

/**
 * One slice of a test run split across machines, read from `-PtestShard=i/n` or `i-j/n`. The slice runs shards [index]
 * through [last] of [count], each counted from one, so a faster machine can take several shards in one call.
 */
internal data class TestShard(
  val index: Int,
  val count: Int,
  val last: Int = index,
) {
  /**
   * The shards this slice runs, [index] through [last].
   */
  val shards: IntRange get() = index..last
}

/**
 * Reads a `-PtestShard` value, `i/n` with `1 <= i <= n` or `i-j/n` with `1 <= i <= j <= n`, and fails naming the
 * value when it is anything else.
 */
internal fun parseTestShard(value: String): TestShard {
  val parts = value.split("/")
  val range = parts.getOrNull(0)?.split("-").orEmpty()
  val index = range.getOrNull(0)?.trim()?.toIntOrNull()
  val last = range.getOrNull(range.size - 1)?.trim()?.toIntOrNull()
  val count = parts.getOrNull(1)?.trim()?.toIntOrNull()
  if (parts.size != 2 || range.size !in 1..2 || index == null || last == null || count == null || count < 1 ||
    index !in 1..count || last !in index..count
  ) {
    throw GradleException("-PtestShard=$value is not i/n or i-j/n with 1 <= i <= j <= n")
  }
  return TestShard(index, count, last)
}

/**
 * Reads a `-PtestForks` value, a whole number of at least one, and fails naming the value when it is anything else.
 */
internal fun parseTestForks(value: String): Int =
  value.trim().toIntOrNull()?.takeIf { it >= 1 }
    ?: throw GradleException("-PtestForks=$value is not a whole number of at least 1")

/**
 * Runs every JVM and Android host [Test] task in this project on as many test JVMs at once as `-PtestForks` says, and
 * hands each test JVM that count as the `filmstrip.testForks` system property so a test that loads the host can keep
 * to its own share of it. Without it Gradle's default of one stands and the property is unset.
 */
internal fun Project.configureTestForks() {
  val forks = providers.gradleProperty(TEST_FORKS_PROPERTY).orNull?.let(::parseTestForks) ?: return
  tasks.withType(Test::class.java).configureEach {
    maxParallelForks = forks
    systemProperty(TEST_FORKS_SYSTEM_PROPERTY, forks)
  }
}

/**
 * Reads a weights file, one `class seconds` pair per line, or `@scope class seconds` for a line only a call given
 * `-PtestWeights=scope` reads. With no [scope] it returns the unscoped lines, and with one it returns only that scope's
 * lines. Blank lines and lines starting with `#` are skipped, and a line that is neither fails naming its line number.
 */
internal fun parseTestWeights(
  text: String,
  scope: String? = null,
): Map<String, Double> =
  text
    .lineSequence()
    .withIndex()
    .filter { (_, line) -> line.isNotBlank() && !line.trimStart().startsWith("#") }
    .mapNotNull { (number, line) ->
      val parts = line.trim().split(Regex("""\s+"""))
      val lineScope = parts.first().takeIf { it.startsWith("@") }?.removePrefix("@")
      val fields = if (lineScope == null) parts else parts.drop(1)
      val seconds = fields.getOrNull(1)?.toDoubleOrNull()
      if (fields.size != 2 || seconds == null || lineScope?.isEmpty() == true) {
        throw GradleException("$TEST_WEIGHTS_PATH line ${number + 1} is not a class and its seconds: $line")
      }
      (fields[0] to seconds).takeIf { lineScope == scope }
    }.toMap()

/**
 * Gives each of [classes] a shard from 1 to [count], so every class lands in exactly one.
 *
 * Classes with a weight go longest first onto whichever shard has the least time so far, the lowest one on a tie. A
 * class with no weight goes to the shard its name's CRC-32 picks, so it stays put while the weighted classes move.
 *
 * The weighted classes of [cell] pack alongside [classes] without being returned. A test task given every class its
 * Gradle call runs packs as one with the call's other tasks, so their heaviest classes spread across the shards
 * rather than all landing on shard 1.
 */
internal fun assignShards(
  classes: Collection<String>,
  count: Int,
  weights: Map<String, Double>,
  cell: Collection<String> = emptyList(),
): Map<String, Int> {
  val (weighted, unweighted) = classes.distinct().partition { it in weights }
  val loads = DoubleArray(count)
  val owners = mutableMapOf<String, Int>()
  (weighted + cell.filter { it in weights })
    .distinct()
    .sortedWith(compareByDescending<String> { weights.getValue(it) }.thenBy { it })
    .forEach { name ->
      val lightest = loads.indices.minBy { loads[it] }
      loads[lightest] += weights.getValue(name)
      owners[name] = lightest + 1
    }
  unweighted.forEach { owners[it] = hashShard(it, count) }
  val own = classes.toSet()
  return owners.filterKeys { it in own }
}

private fun hashShard(
  name: String,
  count: Int,
): Int {
  val crc = CRC32().apply { update(name.toByteArray()) }
  return (crc.value % count).toInt() + 1
}

/**
 * The [classes] that [assignShards] gives to any shard outside this slice, packed alongside [cell], sorted. A slice
 * excludes these and never lists what it runs, so a class the scan missed runs in every slice rather than in none.
 */
internal fun TestShard.classesOwnedElsewhere(
  classes: Collection<String>,
  weights: Map<String, Double>,
  cell: Collection<String> = emptyList(),
): List<String> = assignShards(classes, count, weights, cell).filterValues { it !in shards }.keys.sorted()

/**
 * The instrumentation runner arguments an Android device test gets in [shard], a `notClass` list of the [classes]
 * other shards own. A shard that owns every class gets none.
 */
internal fun deviceRunnerArguments(
  classes: Collection<String>,
  shard: TestShard,
  weights: Map<String, Double>,
): Map<String, String> {
  val elsewhere = shard.classesOwnedElsewhere(classes, weights)
  return if (elsewhere.isEmpty()) emptyMap() else mapOf("notClass" to elsewhere.joinToString(","))
}

/**
 * The source sets whose classes `iosSimulatorArm64Test` runs without `-PiosShared`. Shared `commonTest` and
 * `appleTest` classes already run on `macosArm64Test`, so the simulator only runs what no other target can.
 */
internal val IOS_ONLY_SOURCE_SETS = setOf("iosTest", "iosSimulatorArm64Test", "appleHostTest")

/**
 * Shared classes that still run on the simulator, each one reaching a platform API whose iOS behaviour has differed
 * from macOS before, like bridged dictionary keys and the file sandbox.
 */
internal val IOS_CANARY_CLASSES =
  setOf(
    "dev.jordond.filmstrip.avfoundation.AppleExportSmokeTest",
    "dev.jordond.filmstrip.avfoundation.AppleProbeTest",
    "dev.jordond.filmstrip.avfoundation.AvSettingsKeyTest",
    "dev.jordond.filmstrip.internal.FormatDescriptionAppleTest",
    "dev.jordond.filmstrip.internal.PlatformProberBytesAppleTest",
    "dev.jordond.filmstrip.filekit.PlatformFilesAppleTest",
    "dev.jordond.filmstrip.player.internal.AvTransportMediaTest",
    "dev.jordond.filmstrip.effects.CoreImageTest",
  )

/**
 * The classes `iosSimulatorArm64Test` leaves out under the iOS-only rule, every one of [sharedClasses] that is
 * neither in [iosOnlyClasses] nor a canary in [IOS_CANARY_CLASSES].
 */
internal fun iosExcludedClasses(
  sharedClasses: Collection<String>,
  iosOnlyClasses: Collection<String>,
): List<String> = (sharedClasses.toSet() - iosOnlyClasses.toSet() - IOS_CANARY_CLASSES).sorted()

/**
 * Applies `-PtestShard` and the iOS-only rule to every [AbstractTestTask] in this project.
 *
 * A task's classes are scanned from its test source set and everything that source set depends on. Under `-PtestShard`
 * the task excludes the classes other shards own, counting only classes its [lane] runs. A test task outside a Kotlin
 * Multiplatform target has no scan, so it runs whole in shard 1 and is skipped in the rest.
 *
 * Under `-PtestWeights=scope` the task reads only that scope's weights and packs with every class in them, so the test
 * tasks of one Gradle call share a single longest-first pack. The scope lists the classes the call runs, written from
 * its own results.
 *
 * `iosSimulatorArm64Test` also excludes the shared classes [iosExcludedClasses] names, unless Gradle is given
 * `-PiosShared`.
 */
internal fun Project.configureTestShards(lane: TestLane) {
  val shard = providers.gradleProperty(TEST_SHARD_PROPERTY).orNull?.let(::parseTestShard)
  val iosShared = providers.gradleProperty(IOS_SHARED_PROPERTY).isPresent
  val scope = providers.gradleProperty(TEST_WEIGHTS_PROPERTY).orNull
  val weights = if (shard == null) emptyMap() else testWeights(scope)
  val cell = if (scope == null) emptySet() else weights.keys
  val module = this
  tasks.withType(AbstractTestTask::class.java).configureEach {
    val iosRule = !iosShared && name == IOS_SIMULATOR_TEST_TASK
    if (shard == null && !iosRule) return@configureEach
    val sourceSets = module.testSourceSetClosure(testSourceSetName(name))
    if (sourceSets == null) {
      if (shard != null && 1 !in shard.shards) onlyIf("it runs whole in shard 1 of -PtestShard") { false }
      return@configureEach
    }
    val excluded =
      if (iosRule) {
        val (iosOnly, shared) = sourceSets.partition { it.name in IOS_ONLY_SOURCE_SETS }
        iosExcludedClasses(module.testClasses(shared), module.testClasses(iosOnly))
      } else {
        emptyList()
      }
    val elsewhere =
      shard
        ?.classesOwnedElsewhere(
          module.testClasses(sourceSets).filter { lane.runs(it) && it !in excluded },
          weights,
          cell,
        ).orEmpty()
    excludeTestClasses((excluded + elsewhere).flatMap(::classPatterns))
  }
}

/**
 * Sets the `notClass` runner argument [deviceRunnerArguments] gives this module's Android device tests under
 * `-PtestShard`. The classes are read once the variants exist, after the build script, so a directory the script
 * adds to `androidDeviceTest` is scanned too.
 */
internal fun Project.configureDeviceTestShard() {
  val shard = providers.gradleProperty(TEST_SHARD_PROPERTY).orNull?.let(::parseTestShard) ?: return
  val weights = testWeights()
  val module = this
  extensions.findByType(KotlinMultiplatformAndroidComponentsExtension::class.java)?.onVariants { variant ->
    val classes = module.testClasses(module.testSourceSetClosure("androidDeviceTest").orEmpty())
    val arguments = deviceRunnerArguments(classes, shard, weights)
    variant.deviceTests.values.forEach { it.instrumentationRunnerArguments.putAll(arguments) }
  }
}

// The root plugin's action can run before the Kotlin plugin sets a task's target, so this goes by the task name. A
// JVM or Kotlin/Native task shares its source set's name and a browser task drops Browser from it.
private fun testSourceSetName(taskName: String): String =
  when {
    taskName == "testAndroidHostTest" -> "androidHostTest"
    taskName.endsWith("BrowserTest") -> taskName.removeSuffix("BrowserTest") + "Test"
    else -> taskName
  }

private fun Project.testSourceSetClosure(name: String): List<KotlinSourceSet>? {
  val kotlin = extensions.findByType(KotlinMultiplatformExtension::class.java) ?: return null
  val root = kotlin.sourceSets.findByName(name) ?: return null
  val seen = linkedSetOf<KotlinSourceSet>()

  fun walk(sourceSet: KotlinSourceSet) {
    if (seen.add(sourceSet)) sourceSet.dependsOn.forEach(::walk)
  }
  walk(root)
  return seen.toList()
}

private fun Project.testClasses(sourceSets: Collection<KotlinSourceSet>): List<String> {
  val directories: List<File> = sourceSets.flatMap { it.kotlin.srcDirs }
  return providers
    .of(TestClassesSource::class.java) {
      parameters.directories.from(directories)
      parameters.fileNamedClasses.set(false)
    }.get()
}

private fun Project.testWeights(scope: String? = null): Map<String, Double> {
  val weights =
    providers
      .fileContents(layout.settingsDirectory.file(TEST_WEIGHTS_PATH))
      .asText.orNull
      ?.let { parseTestWeights(it, scope) }
      .orEmpty()
  if (scope != null && weights.isEmpty()) {
    throw GradleException("-P$TEST_WEIGHTS_PROPERTY=$scope names no scope in $TEST_WEIGHTS_PATH")
  }
  return weights
}
