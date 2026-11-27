package dev.jordond.filmstrip.sample.stories

import dev.jordond.filmstrip.compose.overlay.OverlayTransform
import dev.jordond.filmstrip.geometry.Anchor
import io.kotest.matchers.collections.shouldBeEmpty
import io.kotest.matchers.collections.shouldContainExactly
import io.kotest.matchers.nulls.shouldBeNull
import io.kotest.matchers.shouldBe
import kotlin.test.Test

class StoryStateTest {
  @Test
  fun selectingMovesAnElementToTheTop() {
    val state = StoryState()
    val bottom = state.add(StoryContent.Caption("Bottom"))
    val top = state.add(StoryContent.Caption("Top"))

    state.select(bottom)

    state.elements.map { it.id } shouldContainExactly listOf(top, bottom)
    state.selectedId shouldBe bottom
  }

  @Test
  fun aDragOfManyReportsIsOneUndoStep() {
    val state = StoryState()
    val id = state.add(StoryContent.Caption("Dragged"))
    val before = state.elements.single().transform

    state.gesture(true)
    repeat(REPORTS) { report -> state.transform(id, OverlayTransform(Anchor(0.5f, 0.5f + report * STEP))) }
    state.gesture(false)
    state.undo()

    state.elements.single().transform shouldBe before
  }

  @Test
  fun undoThenRedoRoundTrips() {
    val state = StoryState()
    val id = state.add(StoryContent.Caption("Moved"))
    state.gesture(true)
    state.transform(id, MOVED)
    state.gesture(false)
    val after = state.elements

    state.undo()
    state.redo()

    state.elements shouldBe after
    state.canRedo shouldBe false
  }

  @Test
  fun aTapLeavesNoUndoStep() {
    val state = StoryState()
    state.add(StoryContent.Caption("Tapped"))

    state.gesture(true)
    state.gesture(false)
    state.undo()

    state.elements.shouldBeEmpty()
  }

  @Test
  fun stepsOfOneElementOutsideAGestureFoldIntoOneUndoStep() {
    val state = StoryState()
    val id = state.add(StoryContent.Caption("Nudged"))
    val before = state.elements.single().transform

    repeat(REPORTS) { report -> state.transform(id, OverlayTransform(Anchor(0.5f + report * STEP, 0.5f))) }
    state.undo()

    state.elements.single().transform shouldBe before
  }

  @Test
  fun anEditAfterAnUndoDropsTheRedo() {
    val state = StoryState()
    val id = state.add(StoryContent.Caption("First"))
    state.transform(id, MOVED)
    state.undo()

    state.add(StoryContent.Sticker(StoryStickerKind.Heart))

    state.canRedo shouldBe false
  }

  @Test
  fun removingTheSelectionClearsItAndUndoBringsTheElementBack() {
    val state = StoryState()
    val id = state.add(StoryContent.Caption("Gone"))

    state.remove(id)
    state.selectedId.shouldBeNull()
    state.undo()

    state.elements.map { it.id } shouldContainExactly listOf(id)
  }

  @Test
  fun undoDropsTheLastStroke() {
    val state = StoryState()
    val first = StoryStroke(listOf(Anchor(0.1f, 0.1f), Anchor(0.2f, 0.2f)))
    state.addStroke(first)
    state.addStroke(StoryStroke(listOf(Anchor(0.5f, 0.5f), Anchor(0.6f, 0.6f))))

    state.undo()

    state.strokes shouldContainExactly listOf(first)
  }

  @Test
  fun aLookIsAnUndoStep() {
    val state = StoryState()

    state.look(StoryLook.Mono)
    state.undo()

    state.look shouldBe StoryLook.Original
  }

  @Test
  fun sizingAnElementThatRestsOnTheBinKeepsItAndCarryingItThereDropsIt() {
    val editor = StoryEditor()
    val id = editor.story.add(StoryContent.Sticker(StoryStickerKind.Heart), OverlayTransform(BIN_CENTER))

    editor.gesture(id, true)
    editor.moved(id, OverlayTransform(BIN_CENTER, scale = 2f))
    editor.overBin shouldBe false
    editor.gesture(id, false)
    editor.story.elements
      .single()
      .transform.scale shouldBe 2f

    val carried = editor.story.add(StoryContent.Sticker(StoryStickerKind.Star), OverlayTransform(Anchor(0.2f, 0.2f)))
    editor.gesture(carried, true)
    editor.moved(carried, OverlayTransform(BIN_CENTER))
    editor.overBin shouldBe true
    editor.gesture(carried, false)
    editor.story.elements.map { it.id } shouldContainExactly listOf(id)
  }

  private companion object {
    const val REPORTS = 12
    const val STEP = 0.01f
    val MOVED = OverlayTransform(Anchor(0.3f, 0.7f), scale = 2f, rotationDegrees = 20f)
  }
}
