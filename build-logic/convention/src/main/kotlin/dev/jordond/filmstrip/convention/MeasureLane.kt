package dev.jordond.filmstrip.convention

import org.gradle.api.Project
import org.gradle.api.Task
import org.gradle.api.internal.tasks.testing.filter.DefaultTestFilter
import org.gradle.api.tasks.testing.AbstractTestTask
import org.jetbrains.kotlin.gradle.tasks.KotlinTest

private const val MEASURE_PROPERTY = "measure"

/**
 * The class pattern matches what the JVM, Android host and Kotlin/Native runners compare it with, a test class name.
 * The browser runner compares a pattern with the whole `Class.method` name and anchors both ends, so it needs the
 * second pattern to see a measure class at all.
 */
private val MEASURE_TEST_PATTERNS = listOf("*MeasureTest", "*MeasureTest.*")

/**
 * Leaves every test class named `*MeasureTest` out of this project's test tasks unless Gradle is given `-Pmeasure`.
 * Any value of the property turns the lane on, `-Pmeasure=false` included.
 *
 * It covers each [AbstractTestTask], so the JVM and Android host `Test` tasks, `KotlinNativeTest` and the browser
 * `KotlinJsTest` tasks all drop them alike. Android instrumentation tasks (`connected*AndroidTest`) and the iPhone
 * `run.sh` binary launch are not test tasks, so they still run every measure class. An excluded class is absent from
 * the results rather than counted as passed.
 *
 * A task left with no tests after the exclusion passes. A `--tests` pattern that matches nothing fails on every test
 * task, and so does naming an excluded class unless `-Pmeasure` is set. The JVM and Android host tasks do that by
 * default, and [configureNoMatchingTestsCheck] turns it back on for `KotlinNativeTest` and `KotlinJsTest`.
 */
internal fun Project.configureMeasureLane() {
  val measure = providers.gradleProperty(MEASURE_PROPERTY).isPresent
  if (measure) return
  tasks.withType(AbstractTestTask::class.java).configureEach {
    MEASURE_TEST_PATTERNS.forEach { filter.excludeTestsMatching(it) }
    doFirst {
      val testFilter = testFilter()
      if (testFilter.includePatterns.isEmpty() && testFilter.commandLineIncludePatterns.isEmpty()) {
        testFilter.isFailOnNoMatchingTests = false
      }
    }
  }
}

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

private fun Task.testFilter(): DefaultTestFilter = (this as AbstractTestTask).filter as DefaultTestFilter
