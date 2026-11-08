package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContain
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.collections.shouldNotContain
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.api.GradleException
import org.gradle.testkit.runner.GradleRunner
import java.io.File
import java.nio.file.Files
import kotlin.random.Random
import kotlin.test.BeforeTest
import kotlin.test.Test
import kotlin.test.assertFailsWith

/**
 * Builds a stub module with jvm, js, wasmJs, macosArm64 and iosSimulatorArm64 targets and one `pureTest` class, wires
 * it through [configureTestLanes], [pureTests] and [configureCompileTests], and reads back the exclude patterns each
 * test task type was given under each property set. Some cases rewrite the stub with only Apple or only web targets.
 */
class TestLanesTest {
  private val project = Files.createTempDirectory("lanes-project").toFile()

  @BeforeTest
  fun writeProject() {
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\n")
    writeBuild(
      targets = listOf("jvm()", "js { browser() }", "wasmJs { browser() }", "macosArm64()", "iosSimulatorArm64()"),
      testTasks = listOf("jvmTest", "jsBrowserTest", "macosArm64Test"),
    )
    writePureFile("LogicTest", "class LogicTest {\n  @Test fun adds() {}\n}\n")
  }

  private fun writeBuild(
    targets: List<String>,
    testTasks: List<String>,
  ) {
    project.resolve("build.gradle").writeText(
      """
      plugins {
        id 'org.jetbrains.kotlin.multiplatform'
        id 'filmstrip.root' apply false
      }
      repositories { mavenCentral() }
      kotlin {
        ${targets.joinToString("\n        ")}
      }
      dev.jordond.filmstrip.convention.TestLanesKt.configureTestLanes(project)
      dev.jordond.filmstrip.convention.TestLanesKt.configureCompileTests(project)
      dev.jordond.filmstrip.convention.TestLanesKt.pureTests(project)
      tasks.register('printExcludes') {
        def lines = [${testTasks.joinToString { "'$it'" }}].collect { name ->
          def task = tasks.named(name).get()
          def type = task.class.name.replace('_Decorated', '').tokenize('.').last()
          "${'$'}name ${'$'}type ${'$'}{task.filter.excludePatterns.sort().join(' ')}"
        }
        doLast { lines.each { println "excludes: ${'$'}it" } }
      }
      """.trimIndent(),
    )
  }

  @Test
  fun `the fast lane leaves out media hardware measure and pure classes off the reference target`() {
    excludes() shouldBe
      mapOf(
        "jvmTest" to ("KotlinJvmTest" to LANE_FAST),
        "jsBrowserTest" to ("KotlinJsTest" to (LANE_FAST + PURE).sorted()),
        "macosArm64Test" to ("KotlinNativeHostTest" to (LANE_FAST + PURE).sorted()),
      )
  }

  @Test
  fun `media brings media classes back on every task type`() {
    val expected = listOf("*HardwareTest", "*HardwareTest.*", "*MeasureTest", "*MeasureTest.*")

    excludes("-Pmedia").mapValues { it.value.second - PURE.toSet() } shouldBe
      mapOf("jvmTest" to expected, "jsBrowserTest" to expected, "macosArm64Test" to expected)
  }

  @Test
  fun `media and hardware leave only measure classes out`() {
    val expected = listOf("*MeasureTest", "*MeasureTest.*")

    excludes("-Pmedia", "-Phardware").mapValues { it.value.second - PURE.toSet() } shouldBe
      mapOf("jvmTest" to expected, "jsBrowserTest" to expected, "macosArm64Test" to expected)
  }

  @Test
  fun `hardware without media leaves media classes out`() {
    excludes("-Phardware").values.forEach { (_, patterns) ->
      patterns.filter { it.startsWith("*MediaTest") } shouldContainExactly MEDIA
      patterns.filter { it.startsWith("*HardwareTest") }.shouldBeEmpty()
    }
  }

  @Test
  fun `hardware classes never run without hardware`() {
    for (properties in listOf(
      arrayOf<String>(),
      arrayOf("-Pmedia"),
      arrayOf("-Pmeasure"),
      arrayOf("-PpureEverywhere"),
    )) {
      excludes(*properties).values.forEach { (_, patterns) ->
        patterns.filter { it.startsWith("*HardwareTest") } shouldContainExactly HARDWARE
      }
    }
  }

  @Test
  fun `measure brings measure classes back on every task type`() {
    excludes("-Pmeasure").values.forEach { (_, patterns) ->
      patterns.filter { it.startsWith("*MeasureTest") }.shouldBeEmpty()
    }
  }

  @Test
  fun `pureEverywhere runs pure classes on every target`() {
    excludes("-PpureEverywhere").values.forEach { (_, patterns) -> patterns shouldBe LANE_FAST }
  }

  @Test
  fun `a pure file with a test but no class named after it fails configuration`() {
    writePureFile("Misnamed", "class SomethingElse {\n  @Test fun adds() {}\n}\n")

    val output = runner("printExcludes").buildAndFail().output

    output shouldContain "Misnamed.kt has a @Test but no class named Misnamed"
  }

  @Test
  fun `compileTests builds the test compilations no fast-lane task runs`() {
    val tasks =
      runner("compileTests", "--dry-run")
        .build()
        .output
        .lines()
        .filter { it.startsWith(":") }
        .map { it.removeSuffix(" SKIPPED") }

    tasks shouldContain ":compileTestKotlinIosSimulatorArm64"
    tasks shouldContain ":compileTestDevelopmentExecutableKotlinWasmJs"
    tasks shouldNotContain ":compileTestKotlinJvm"
    tasks shouldNotContain ":compileTestKotlinMacosArm64"
  }

  @Test
  fun `the lane patterns match what each property set lifts`() {
    TestLane(media = false, hardware = false, measure = false).excludedPatterns() shouldBe
      listOf("*MediaTest", "*MediaTest.*") + HARDWARE + listOf("*MeasureTest", "*MeasureTest.*")
    TestLane(media = true, hardware = true, measure = true).excludedPatterns().shouldBeEmpty()
  }

  @Test
  fun `the reference target is jvm then macosArm64 then js`() {
    pureReferenceTarget(listOf("metadata", "js", "macosArm64", "jvm", "android")) shouldBe "jvm"
    pureReferenceTarget(listOf("metadata", "iosArm64", "macosArm64")) shouldBe "macosArm64"
    pureReferenceTarget(listOf("metadata", "js", "wasmJs")) shouldBe "js"
  }

  @Test
  fun `an apple only module runs pure classes on macosArm64 only`() {
    writeBuild(
      targets = listOf("macosArm64()", "iosSimulatorArm64()"),
      testTasks = listOf("macosArm64Test", "iosSimulatorArm64Test"),
    )

    excludes() shouldBe
      mapOf(
        "macosArm64Test" to ("KotlinNativeHostTest" to LANE_FAST),
        "iosSimulatorArm64Test" to ("KotlinNativeSimulatorTest" to (LANE_FAST + PURE).sorted()),
      )
  }

  @Test
  fun `a web only module runs pure classes on js only`() {
    writeBuild(
      targets = listOf("js { browser() }", "wasmJs { browser() }"),
      testTasks = listOf("jsBrowserTest", "wasmJsBrowserTest"),
    )

    excludes() shouldBe
      mapOf(
        "jsBrowserTest" to ("KotlinJsTest" to LANE_FAST),
        "wasmJsBrowserTest" to ("KotlinJsTest" to (LANE_FAST + PURE).sorted()),
      )
  }

  @Test
  fun `compileTests names every compile task a target or the device test set needs`() {
    compileOnlyTestTasks(
      path = ":stub",
      targets = listOf("jvm", "iosSimulatorArm64", "iosArm64", "wasmJs"),
      sourceSets = listOf("commonTest", "androidDeviceTest"),
      taskNames = COMPILE_ONLY_TASKS + "compileTestKotlinJvm",
    ) shouldContainExactly COMPILE_ONLY_TASKS
    compileOnlyTestTasks(":stub", listOf("jvm"), listOf("commonTest"), listOf("compileTestKotlinJvm")).shouldBeEmpty()
  }

  @Test
  fun `compileTests fails when a target's compile task is missing`() {
    for (missing in COMPILE_ONLY_TASKS) {
      val failure =
        assertFailsWith<GradleException> {
          compileOnlyTestTasks(
            path = ":stub",
            targets = listOf("iosSimulatorArm64", "iosArm64", "wasmJs"),
            sourceSets = listOf("androidDeviceTest"),
            taskNames = COMPILE_ONLY_TASKS - missing,
          )
        }
      failure.message shouldBe ":stub has no $missing, so compileTests cannot compile its tests"
    }
  }

  @Test
  fun `the android host task is never the reference`() {
    pureExcludedPatterns(listOf("a.B"), taskTarget = null, reference = "jvm", pureEverywhere = false) shouldBe
      listOf("a.B", "a.B.*")
  }

  @Test
  fun `every class lands in exactly one shard of any count`() {
    val random = Random(7)
    repeat(50) {
      val classes = List(random.nextInt(0, 40)) { "pkg.Class${random.nextInt(0, 200)}Test" }.distinct()
      val weights = classes.filter { random.nextBoolean() }.associateWith { random.nextDouble(0.1, 400.0) }
      val count = random.nextInt(1, 7)

      val owned = (1..count).map { classes - TestShard(it, count).classesOwnedElsewhere(classes, weights).toSet() }

      owned.flatten().sorted() shouldBe classes.sorted()
    }
  }

  @Test
  fun `a range of shards runs what those shards own and the rest runs the remainder`() {
    val classes = List(30) { "pkg.Class${it}Test" }
    val weights = classes.take(20).withIndex().associate { (i, name) -> name to (i + 1) * 7.0 }
    val owners = assignShards(classes, 4, weights)

    val first = classes - TestShard(1, 4, last = 3).classesOwnedElsewhere(classes, weights).toSet()
    val rest = classes - TestShard(4, 4).classesOwnedElsewhere(classes, weights).toSet()

    first.toSet() shouldBe owners.filterValues { it in 1..3 }.keys
    (first + rest).sorted() shouldBe classes.sorted()
  }

  @Test
  fun `weighted classes pack longest first onto the lightest shard`() {
    val weights = mapOf("a.A" to 5.0, "a.B" to 4.0, "a.C" to 3.0, "a.D" to 3.0, "a.E" to 2.0)

    assignShards(weights.keys, 2, weights) shouldBe
      mapOf("a.A" to 1, "a.B" to 2, "a.C" to 2, "a.D" to 1, "a.E" to 2)
  }

  @Test
  fun `tasks packed with their cell spread each module's heaviest class across the shards`() {
    val player = mapOf("player.HeavyTest" to 300.0, "player.LightTest" to 20.0)
    val effects = mapOf("effects.HeavyTest" to 280.0, "effects.LightTest" to 30.0)
    val transform = mapOf("transform.HeavyTest" to 260.0, "transform.LightTest" to 10.0)
    val weights = player + effects + transform
    val modules = listOf(player, effects, transform)

    modules.map { assignShards(it.keys, 3, it).getValue(it.keys.first()) } shouldBe listOf(1, 1, 1)

    val owners = modules.map { assignShards(it.keys, 3, weights, cell = weights.keys) }
    owners.map { it.getValue(it.keys.first()) }.sorted() shouldBe listOf(1, 2, 3)
    owners.forEachIndexed { index, owned -> owned.keys shouldBe modules[index].keys }
    val loads =
      owners
        .flatMap { it.entries }
        .groupBy({ it.value }) { weights.getValue(it.key) }
        .mapValues { it.value.sum() }
    loads shouldBe mapOf(1 to 300.0, 2 to 300.0, 3 to 300.0)
  }

  @Test
  fun `a class with no weight keeps its shard whatever else is weighted`() {
    val alone = assignShards(listOf("a.Unweighted"), 3, emptyMap()).getValue("a.Unweighted")
    val weights = mapOf("a.Heavy" to 300.0, "a.Mid" to 120.0, "a.Light" to 4.0)

    assignShards(weights.keys + "a.Unweighted", 3, weights).getValue("a.Unweighted") shouldBe alone
    (alone in 1..3) shouldBe true
  }

  @Test
  fun `the shard property needs an index from one to the count`() {
    parseTestShard("2/4") shouldBe TestShard(2, 4)
    parseTestShard("1-3/4") shouldBe TestShard(1, 4, last = 3)
    for (value in listOf("0/4", "5/4", "1", "a/b", "1/0", "1/2/3", "3-2/4", "1-5/4", "1-2-3/4", "-2/4")) {
      assertFailsWith<GradleException> { parseTestShard(value) }.message shouldContain "-PtestShard=$value"
    }
  }

  @Test
  fun `the forks property needs a whole number of at least one`() {
    parseTestForks("3") shouldBe 3
    for (value in listOf("0", "-1", "two", "1.5", "")) {
      assertFailsWith<GradleException> { parseTestForks(value) }.message shouldContain "-PtestForks=$value"
    }
  }

  @Test
  fun `the weights file reads class and seconds and skips comments`() {
    parseTestWeights("# made by hand\n\na.B 12.5\n  a.C   3\n") shouldBe mapOf("a.B" to 12.5, "a.C" to 3.0)
    assertFailsWith<GradleException> { parseTestWeights("a.B twelve\n") }.message shouldContain "line 1"
  }

  @Test
  fun `a scoped weight is read only under its scope`() {
    val text = "a.B 12.5\n@ffmpeg-9 a.B 40\n@ffmpeg-9 a.C 2.5\n@ffmpeg-8 a.D 7\n"

    parseTestWeights(text) shouldBe mapOf("a.B" to 12.5)
    parseTestWeights(text, "ffmpeg-9") shouldBe mapOf("a.B" to 40.0, "a.C" to 2.5)
    parseTestWeights(text, "ffmpeg-7") shouldBe emptyMap()
    for (line in listOf("@ffmpeg-9 a.B\n", "@ a.B 3\n", "@ffmpeg-9 a.B 3 4\n")) {
      assertFailsWith<GradleException> { parseTestWeights(line) }.message shouldContain "line 1"
    }
  }

  @Test
  fun `a device shard leaves out every class another shard owns`() {
    val classes = listOf("a.ATest", "a.BTest", "a.CTest")
    val weights = mapOf("a.ATest" to 10.0, "a.BTest" to 6.0, "a.CTest" to 5.0)

    deviceRunnerArguments(classes, TestShard(1, 2), weights) shouldBe mapOf("notClass" to "a.BTest,a.CTest")
    deviceRunnerArguments(classes, TestShard(2, 2), weights) shouldBe mapOf("notClass" to "a.ATest")
    deviceRunnerArguments(classes, TestShard(1, 1), weights) shouldBe emptyMap()
  }

  @Test
  fun `jvm and browser shards split the module's classes between them`() {
    writeCommonClasses("HeavyTest", "MiddleTest", "LightTest", "FreshTest")
    project.resolve("gradle").mkdirs()
    project.resolve("gradle/test-weights.txt").writeText("stub.HeavyTest 90\nstub.MiddleTest 50\nstub.LightTest 30\n")
    val all = listOf("stub.FreshTest", "stub.HeavyTest", "stub.LightTest", "stub.MiddleTest")

    val first = excludes("-PtestShard=1/2")
    val second = excludes("-PtestShard=2/2")

    for (task in listOf("jvmTest", "jsBrowserTest")) {
      val firstOwns = all.filterNot { "$it.*" in first.getValue(task).second }
      val secondOwns = all.filterNot { "$it.*" in second.getValue(task).second }
      (firstOwns + secondOwns).sorted() shouldBe all
      firstOwns shouldContain "stub.HeavyTest"
      secondOwns shouldContain "stub.MiddleTest"
    }
  }

  @Test
  fun `a contract subclass with no test of its own lands in exactly one shard`() {
    val contract = project.resolve("src/commonTest/kotlin/stub/contract").apply { mkdirs() }
    contract.resolve("ClipContractTest.kt").writeText(
      "package stub.contract\n\nimport kotlin.test.Test\n\n" +
        "abstract class ClipContractTest(\n  val rate: Int,\n) {\n  @Test fun plays() {}\n}\n",
    )
    val web = project.resolve("src/jsTest/kotlin/stub").apply { mkdirs() }
    web.resolve("WebClipTest.kt").writeText(
      "package stub\n\nimport stub.contract.ClipContractTest\n\n" +
        "class WebClipTest : ClipContractTest(30) {\n  fun hook(): Int = rate\n}\n",
    )
    web.resolve("WebLooseTest.kt").writeText(
      "package stub\n\nimport stub.contract.ClipContractTest\n\n" +
        "class WebLooseTest(\n  speed: Int = 1,\n) : stub.contract.ClipContractTest(speed)\n",
    )
    val subclasses = listOf("stub.WebClipTest", "stub.WebLooseTest")

    val first = excludes("-PtestShard=1/2").getValue("jsBrowserTest").second
    val second = excludes("-PtestShard=2/2").getValue("jsBrowserTest").second

    for (name in subclasses) {
      listOf("$name.*" in first, "$name.*" in second).count { it } shouldBe 1
    }
  }

  @Test
  fun `a shard leaves out no class the lane already drops`() {
    writeCommonClasses("SlowMediaTest", "PlainTest")

    val shardExcludes = (excludes("-PtestShard=1/2") + excludes("-PtestShard=2/2")).values.flatMap { it.second }

    shardExcludes shouldNotContain "stub.SlowMediaTest"
  }

  @Test
  fun `the simulator runs only ios classes and the canaries`() {
    writeBuild(
      targets = listOf("macosArm64()", "iosSimulatorArm64()"),
      testTasks = listOf("macosArm64Test", "iosSimulatorArm64Test"),
    )
    writeCommonClasses("SharedTest")
    writeTestClass("iosTest", "stub", "OnlyIosTest")
    writeTestClass("commonTest", "dev.jordond.filmstrip.avfoundation", "AppleExportSmokeTest")

    val ios = excludes().getValue("iosSimulatorArm64Test").second
    val macos = excludes().getValue("macosArm64Test").second

    ios shouldContain "stub.SharedTest"
    ios shouldNotContain "stub.OnlyIosTest"
    ios shouldNotContain "dev.jordond.filmstrip.avfoundation.AppleExportSmokeTest"
    macos shouldNotContain "stub.SharedTest"
  }

  @Test
  fun `iosShared runs the shared classes on the simulator too`() {
    writeBuild(
      targets = listOf("macosArm64()", "iosSimulatorArm64()"),
      testTasks = listOf("iosSimulatorArm64Test"),
    )
    writeCommonClasses("SharedTest")

    excludes("-PiosShared").getValue("iosSimulatorArm64Test").second shouldNotContain "stub.SharedTest"
  }

  private fun writeCommonClasses(vararg names: String) = names.forEach { writeTestClass("commonTest", "stub", it) }

  private fun writeTestClass(
    sourceSet: String,
    pkg: String,
    name: String,
  ) {
    val directory = project.resolve("src/$sourceSet/kotlin/${pkg.replace('.', '/')}").apply { mkdirs() }
    directory
      .resolve(
        "$name.kt",
      ).writeText("package $pkg\n\nimport kotlin.test.Test\n\nclass $name {\n  @Test fun runs() {}\n}\n")
  }

  private fun writePureFile(
    name: String,
    body: String,
  ) {
    val directory = project.resolve("src/pureTest/kotlin/stub").apply { mkdirs() }
    directory.resolve("$name.kt").writeText("package stub\n\nimport kotlin.test.Test\n\n$body")
  }

  private fun excludes(vararg properties: String): Map<String, Pair<String, List<String>>> =
    runner("printExcludes", *properties)
      .build()
      .output
      .lines()
      .filter { it.startsWith("excludes: ") }
      .associate { line ->
        val parts = line.removePrefix("excludes: ").split(" ")
        parts[0] to (parts[1] to parts.drop(2).filter { it.isNotEmpty() })
      }

  private fun runner(vararg arguments: String): GradleRunner =
    GradleRunner
      .create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withTestKitDir(testKit)
      .withArguments(arguments.toList() + "--configuration-cache")

  private companion object {
    val testKit: File = Files.createTempDirectory("lanes-testkit").toFile()

    val HARDWARE = listOf("*HardwareTest", "*HardwareTest.*")

    val MEDIA = listOf("*MediaTest", "*MediaTest.*")

    val COMPILE_ONLY_TASKS =
      listOf(
        "compileTestKotlinIosSimulatorArm64",
        "compileTestKotlinIosArm64",
        "compileTestDevelopmentExecutableKotlinWasmJs",
        "compileAndroidDeviceTest",
      )

    val LANE_FAST =
      listOf("*HardwareTest", "*HardwareTest.*", "*MeasureTest", "*MeasureTest.*", "*MediaTest", "*MediaTest.*")

    val PURE = listOf("stub.LogicTest", "stub.LogicTest.*")
  }
}
