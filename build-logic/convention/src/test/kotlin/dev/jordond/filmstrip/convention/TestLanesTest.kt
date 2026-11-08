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
