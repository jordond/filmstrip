package dev.jordond.filmstrip.player.internal

import dev.jordond.filmstrip.player.PlaybackError
import dev.jordond.filmstrip.player.SetCompositionCallback
import dev.jordond.filmstrip.player.SetCompositionResult
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.shouldBe
import kotlinx.coroutines.Job
import kotlin.test.Test
import kotlin.time.Duration.Companion.seconds

class PendingLoadTest {
  @Test
  fun `settling answers the tracked caller once`() {
    val load = PendingLoad()
    val caller = Caller()
    val success = SetCompositionResult.Success(4.seconds)
    load.track(Job(), caller)

    load.settle(success)
    load.settle(SetCompositionResult.Failure(PlaybackError.SourceUnreadable("late")))

    caller.results.shouldContainExactly(success)
  }

  @Test
  fun `superseding cancels the load and tells its caller`() {
    val load = PendingLoad()
    val caller = Caller()
    val job = Job()
    load.track(job, caller)

    load.supersede()
    load.settle(SetCompositionResult.Success(4.seconds))

    job.isCancelled shouldBe true
    caller.results.shouldContainExactly(SetCompositionResult.Superseded)
  }

  @Test
  fun `superseding a settled load leaves its job and caller alone`() {
    val load = PendingLoad()
    val caller = Caller()
    val job = Job()
    val success = SetCompositionResult.Success(4.seconds)
    load.track(job, caller)
    load.settle(success)

    load.supersede()

    job.isCancelled shouldBe false
    caller.results.shouldContainExactly(success)
  }

  @Test
  fun `cancelling the handle cancels the job without answering`() {
    val load = PendingLoad()
    val caller = Caller()
    val job = Job()
    val handle = load.track(job, caller)

    handle.cancel()
    load.settle(SetCompositionResult.Success(4.seconds))
    load.supersede()

    job.isCancelled shouldBe true
    caller.results.shouldBeEmpty()
  }

  @Test
  fun `a superseded load's handle leaves the newer load to settle`() {
    val load = PendingLoad()
    val older = Caller()
    val newer = Caller()
    val olderJob = Job()
    val newerJob = Job()
    val success = SetCompositionResult.Success(4.seconds)
    val handle = load.track(olderJob, older)
    load.supersede()
    load.track(newerJob, newer)

    handle.cancel()
    load.settle(success)

    newerJob.isCancelled shouldBe false
    older.results.shouldContainExactly(SetCompositionResult.Superseded)
    newer.results.shouldContainExactly(success)
  }

  @Test
  fun `nothing tracked means nothing to settle or supersede`() {
    val load = PendingLoad()

    load.settle(SetCompositionResult.Success(4.seconds))
    load.supersede()
  }

  private class Caller : SetCompositionCallback {
    val results = mutableListOf<SetCompositionResult>()

    override fun onResult(result: SetCompositionResult) {
      results += result
    }
  }
}
