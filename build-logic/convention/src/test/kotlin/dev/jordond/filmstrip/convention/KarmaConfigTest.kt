package dev.jordond.filmstrip.convention

import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import io.kotest.matchers.string.shouldContain
import org.gradle.testkit.runner.GradleRunner
import org.junit.AfterClass
import java.nio.file.Files
import kotlin.test.AfterTest
import kotlin.test.BeforeTest
import kotlin.test.Test

/**
 * Builds a stub module with js and wasmJs browser targets, wires its browser tests through [configureBrowserTests], and
 * reads back the Karma config directory each test task was given and what was written there. The project and TestKit
 * directories are deleted after.
 */
class KarmaConfigTest {
  private val project = Files.createTempDirectory("karma-project").toFile()

  private val configDirectory = project.resolve("build/karma.config.d")

  @BeforeTest
  fun writeProject() {
    project.resolve("settings.gradle").writeText("rootProject.name = 'stub'\n")
    writeBuild(
      plugins = "id 'org.jetbrains.kotlin.multiplatform'",
      wiring = "dev.jordond.filmstrip.convention.KarmaConfigKt.configureBrowserTests(project)",
    )
    project.resolve("karma.config.d").apply {
      mkdirs()
      resolve("fonts.js").writeText("config.files.push('fonts');\n")
    }
  }

  @AfterTest
  fun deleteProject() {
    project.deleteRecursively()
  }

  @Test
  fun `every browser test task reads the generated directory`() {
    assertEveryTaskReadsTheGeneratedDirectory()
  }

  @Test
  fun `the generated directory holds the shared mocha timeout and the module's own files`() {
    run("writeKarmaConfig")

    configDirectory.list()!!.sorted() shouldContainExactly
      listOf(KarmaConfigTask.SHARED_CONFIG, KarmaConfigTask.TIMES_CONFIG, "fonts.js")
    configDirectory.resolve(KarmaConfigTask.SHARED_CONFIG).readText() shouldBe BROWSER_TEST_CONFIG
    BROWSER_TEST_CONFIG shouldContain "timeout: 300000"
  }

  @Test
  fun `the times config writes into the module's own karma results directory`() {
    run("writeKarmaConfig")

    val times = project.canonicalFile.resolve("build/$KARMA_TIMES_DIRECTORY").path
    configDirectory.resolve(KarmaConfigTask.TIMES_CONFIG).readText() shouldBe browserTimesConfig(times)
    browserTimesConfig(times) shouldContain "const directory = '$times';"
    browserTimesConfig("C:\\it's") shouldContain "const directory = 'C:\\\\it\\'s';"
    browserTimesConfig(times) shouldContain "concat(['filmstrip-times'])"
  }

  @Test
  fun `the shared config runs every module on the shared launcher`() {
    BROWSER_TEST_CONFIG shouldContain "browsers: ['FilmstripChrome'],"
    BROWSER_TEST_CONFIG shouldContain "FilmstripChrome: {"
  }

  @Test
  fun `the shared launcher lets an AudioContext run without a user gesture`() {
    val launcher = BROWSER_TEST_CONFIG.substringAfter("FilmstripChrome: {")
    val alwaysAppended = launcher.substringAfter(".concat([").substringBefore("])")

    alwaysAppended shouldContain "'--autoplay-policy=no-user-gesture-required'"
    alwaysAppended shouldContain "'--mute-audio'"
  }

  @Test
  fun `the shared launcher keeps its sandbox and port flags under an override`() {
    val launcher = BROWSER_TEST_CONFIG.substringAfter("FilmstripChrome: {")
    val alwaysAppended = launcher.substringAfter(".concat([").substringBefore("])")

    alwaysAppended shouldContain "'--no-sandbox'"
    alwaysAppended shouldContain "'--remote-debugging-port=0'"
  }

  @Test
  fun `a module that applies the multiplatform plugin later reads the generated directory`() {
    writeBuild(
      plugins = "id 'org.jetbrains.kotlin.multiplatform' apply false",
      wiring =
        "dev.jordond.filmstrip.convention.KarmaConfigKt.configureBrowserTestsOnMultiplatform(project)\n" +
          "apply plugin: 'org.jetbrains.kotlin.multiplatform'",
    )

    assertEveryTaskReadsTheGeneratedDirectory()
  }

  private fun assertEveryTaskReadsTheGeneratedDirectory() {
    val printed =
      run("printKarmaDirectories")
        .lines()
        .filter { it.startsWith("karma: ") }
        .map { it.removePrefix("karma: ") }

    printed shouldContainExactly
      listOf(
        "jsBrowserTest ${configDirectory.canonicalPath}",
        "wasmJsBrowserTest ${configDirectory.canonicalPath}",
      )
  }

  private fun writeBuild(
    plugins: String,
    wiring: String,
  ) {
    project.resolve("build.gradle").writeText(
      """
      |plugins {
      |  $plugins
      |  id 'filmstrip.root' apply false
      |}
      |repositories { mavenCentral() }
      |$wiring
      |kotlin {
      |  js { browser() }
      |  wasmJs { browser() }
      |}
      |tasks.register('printKarmaDirectories') {
      |  def lines = ['jsBrowserTest', 'wasmJsBrowserTest'].collect { name ->
      |    "${'$'}name ${'$'}{tasks.named(name).get().testFramework.configDirectory.canonicalPath}"
      |  }
      |  doLast { lines.each { println "karma: ${'$'}it" } }
      |}
      """.trimMargin(),
    )
  }

  private fun run(task: String): String =
    GradleRunner
      .create()
      .withProjectDir(project)
      .withPluginClasspath()
      .withTestKitDir(testKit)
      .withArguments(task, "--configuration-cache")
      .build()
      .output

  companion object {
    private val testKit = Files.createTempDirectory("karma-testkit").toFile()

    @AfterClass
    @JvmStatic
    fun deleteTestKit() {
      testKit.deleteRecursively()
    }
  }
}
