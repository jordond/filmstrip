package dev.jordond.filmstrip.convention

import org.gradle.api.DefaultTask
import org.gradle.api.GradleException
import org.gradle.api.Project
import org.gradle.api.provider.Property
import org.gradle.api.tasks.Input
import org.gradle.api.tasks.TaskAction
import org.gradle.kotlin.dsl.property
import org.gradle.kotlin.dsl.register
import org.gradle.kotlin.dsl.withType
import org.gradle.process.ExecOperations
import org.jetbrains.kotlin.gradle.targets.native.tasks.KotlinNativeSimulatorTest
import java.io.ByteArrayOutputStream
import javax.inject.Inject

/**
 * Boots one simulator device and waits until it has finished coming up.
 *
 * A test spawned into a device needs that device already booted, since `simctl spawn` boots none
 * itself. `simctl bootstatus -b` boots a shut down device and returns straight away for one that is
 * already up, so a test task can depend on this unconditionally.
 *
 * Every module with simulator tests has one of these, and other builds on the machine may boot the
 * same device too. When two callers find the device shut down at once, both ask for a boot and the
 * one that loses fails with "Unable to boot device in current state: Booted". The device is booting
 * by then, and calling `bootstatus -b` again waits for that boot to finish, so a failed attempt is
 * retried before the task gives up.
 */
abstract class BootSimulatorTask : DefaultTask() {
  /**
   * The device name or UDID to boot.
   */
  @get:Input
  abstract val device: Property<String>

  @get:Inject
  abstract val execOperations: ExecOperations

  @TaskAction
  fun boot() {
    var output = ""
    repeat(BOOT_ATTEMPTS) { attempt ->
      if (attempt > 0) Thread.sleep(RETRY_DELAY_MS)

      val buffer = ByteArrayOutputStream()
      val result =
        execOperations.exec {
          commandLine("xcrun", "simctl", "bootstatus", device.get(), "-b")
          standardOutput = buffer
          errorOutput = buffer
          isIgnoreExitValue = true
        }
      output = buffer.toString()
      logger.info(output)
      if (result.exitValue == 0) return
    }

    throw GradleException("could not boot ${device.get()} after $BOOT_ATTEMPTS attempts:\n$output")
  }

  private companion object {
    const val BOOT_ATTEMPTS = 3
    const val RETRY_DELAY_MS = 2_000L
  }
}

/**
 * Makes `iosSimulatorArm64Test` spawn into a booted simulator instead of running standalone.
 *
 * AVFoundation reaches mediaserverd over XPC, and a standalone spawn is given no bootstrap to reach
 * it through: every AVPlayerItem fails with -12746 and CIContext() answers nil. Spawning into a
 * booted device is what gives the test process the services a real app has, and simctl boots none
 * on its own.
 */
fun Project.bootIosSimulatorForTests() {
  val simulatorDevice = objects.property<String>()

  val bootIosSimulator =
    tasks.register<BootSimulatorTask>("bootIosSimulator") {
      description = "Boots the simulator the iOS test task spawns into."
      device.set(simulatorDevice)
    }

  tasks.withType<KotlinNativeSimulatorTest>().configureEach {
    if (name != "iosSimulatorArm64Test") return@configureEach

    standalone.set(false)
    simulatorDevice.set(device)
    dependsOn(bootIosSimulator)
  }
}
