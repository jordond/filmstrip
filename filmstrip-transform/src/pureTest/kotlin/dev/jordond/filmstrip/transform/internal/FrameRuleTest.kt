package dev.jordond.filmstrip.transform.internal

import dev.jordond.filmstrip.edit.Composition
import dev.jordond.filmstrip.edit.Track
import dev.jordond.filmstrip.effect.EffectSpec
import dev.jordond.filmstrip.effects.geometry.Crop
import dev.jordond.filmstrip.effects.geometry.CropRect
import dev.jordond.filmstrip.effects.geometry.Rotate
import dev.jordond.filmstrip.effects.geometry.Scale
import dev.jordond.filmstrip.effects.geometry.turnedAspect
import dev.jordond.filmstrip.effects.geometry.turnedFrame
import dev.jordond.filmstrip.export.ExportSpec
import dev.jordond.filmstrip.export.Verdict
import dev.jordond.filmstrip.geometry.AspectRatio
import dev.jordond.filmstrip.geometry.Fit
import dev.jordond.filmstrip.geometry.NormalizedRect
import dev.jordond.filmstrip.geometry.Size
import dev.jordond.filmstrip.transform.internal.geometry.MeasuredFrame
import dev.jordond.filmstrip.transform.internal.negotiation.fittedTo
import dev.jordond.filmstrip.transform.internal.resolve.ResolvedHdr
import io.kotest.assertions.withClue
import io.kotest.matchers.floats.shouldBeLessThan
import io.kotest.matchers.shouldBe
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.roundToInt
import kotlin.math.sin
import kotlin.test.Test

/**
 * The frame a composition writes under a canvas crop and a scale, which has to follow the aspect asked for and not the
 * size of whichever clip comes first.
 */
class FrameRuleTest {
  private val fixture = PlannerFixture()

  @Test
  fun `a canvas crop and a scale write one frame in every order of the clips`() {
    val orders = orders(SIZES, 4) + orders(SIZES, 5)
    for (aspect in ASPECTS) {
      for (height in HEIGHTS) {
        val expected = onEvenGrid(Size((height * aspect.value).roundToInt(), height), aspect)
        val written =
          orders
            .map { order ->
              val clips = order.map { fixture.clip(size = it) }
              val composition =
                Composition(listOf(Track(clips)), effects = listOf(Crop(aspect, Fit.Contain), Scale(height)))
              fixture.resolve(composition).output.size
            }.toSet()

        withClue("$aspect scaled to $height") { written shouldBe setOf(expected) }
      }
    }
  }

  // The compositions an app failed on: a portrait beside a landscape clip, and a 4K clip beside a 4:3 one.
  @Test
  fun `an app's pairs of clips under a canvas crop and a scale write one frame in either order`() {
    for (sizes in APP_PAIRS) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        for (height in listOf(320, 540)) {
          val expected = onEvenGrid(Size((height * aspect.value).roundToInt(), height), aspect)
          val written =
            listOf(sizes, sizes.reversed())
              .map { order ->
                val composition =
                  Composition(
                    listOf(Track(order.map { fixture.clip(size = it) })),
                    effects = listOf(Crop(aspect, Fit.Contain), Scale(height)),
                  )
                fixture.resolve(composition).output.size
              }.toSet()

          withClue("$sizes at $aspect scaled to $height") { written shouldBe setOf(expected) }
        }
      }
    }
  }

  @Test
  fun `a clip crop before the canvas crop leaves the frame the same`() {
    for (aspect in ASPECTS) {
      val expected = Size((SCALED * aspect.value).roundToInt(), SCALED)
      for (order in orders(SIZES, 4)) {
        val clips = order.map { fixture.clip(size = it, effects = listOf(Crop(AspectRatio(4, 3)))) }
        val composition =
          Composition(listOf(Track(clips)), effects = listOf(Crop(aspect, Fit.Contain), Scale(SCALED)))

        withClue("$aspect over ${order.map { "${it.width}x${it.height}" }}") {
          fixture.resolve(composition).naturalSize shouldBe expected
        }
      }
    }
  }

  // 86x50 holds a 4:3 canvas of 67x50, which stands for 4:3 exactly: 324 high is 432 wide, where 67x50 rounds to 434.
  private fun turnedCanvas(
    turn: Rotate,
    height: Int = SCALED,
  ): Size {
    val clip = fixture.clip(size = Size(86, 50), effects = listOf(Crop(AspectRatio(4, 3), Fit.Contain)))
    val composition = Composition(listOf(Track(listOf(clip))), effects = listOf(turn, Scale(height)))
    return fixture.resolve(composition).naturalSize
  }

  @Test
  fun `a quarter turn of the composition turns a clip's canvas aspect over`() {
    turnedCanvas(Rotate(QUARTER_TURN)) shouldBe Size(243, SCALED)
  }

  @Test
  fun `a half turn of the composition leaves a clip's canvas aspect as it was`() {
    turnedCanvas(Rotate(2 * QUARTER_TURN)) shouldBe Size(432, SCALED)
  }

  @Test
  fun `no turn of the composition leaves a clip's canvas aspect as it was`() {
    turnedCanvas(Rotate(0)) shouldBe Size(432, SCALED)
  }

  // At 1080 high the exact aspect writes 1166 wide, where the aspect of the turned 67x50 frame's rounded pixels writes
  // 1165.
  @Test
  fun `a turn that is not a quarter follows the exact aspect of the turned canvas and not its rounded pixels`() {
    val turn = Rotate(30)
    val exact = turn.turnedAspect(AspectRatio(4, 3).value)
    val rounded = turn.turnedFrame(Size(67, 50)).aspect

    (TURNED_HEIGHT * rounded).roundToInt() shouldBe 1165
    turnedCanvas(turn, TURNED_HEIGHT) shouldBe Size((TURNED_HEIGHT * exact).roundToInt(), TURNED_HEIGHT)
    turnedCanvas(turn, TURNED_HEIGHT).width shouldBe 1166
  }

  // A height the spec asks for moves the frame along the aspect the canvas was asked for, whichever clip is first.
  @Test
  fun `a height the spec asks for under a canvas crop writes one frame in either order`() {
    for (sizes in APP_PAIRS) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        for (height in listOf(163, 135)) {
          val expected = onEvenGrid(Size((height * aspect.value).roundToInt(), height), aspect)
          val written =
            listOf(sizes, sizes.reversed())
              .map { order ->
                val composition =
                  Composition(
                    listOf(Track(order.map { fixture.clip(size = it) })),
                    effects = listOf(Crop(aspect, Fit.Contain)),
                  )
                fixture.resolve(composition, ExportSpec(heightPx = height)).output.size
              }.toSet()

          withClue("$sizes at $aspect capped to $height") { written shouldBe setOf(expected) }
        }
      }
    }
  }

  // The default crop fits the picture to the aspect by cutting it, and the frame still stands for that aspect.
  @Test
  fun `a crop that cuts and a scale write one frame in either order`() {
    for (sizes in APP_PAIRS + listOf(listOf(Size(181, 320), Size(640, 480)))) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        val written =
          listOf(sizes, sizes.reversed())
            .map { order ->
              val composition =
                Composition(
                  listOf(Track(order.map { fixture.clip(size = it) })),
                  effects = listOf(Crop(aspect), Scale(540)),
                )
              fixture.resolve(composition).naturalSize
            }.toSet()

        withClue("$sizes cut to $aspect") { written shouldBe setOf(Size((540 * aspect.value).roundToInt(), 540)) }
      }
    }
  }

  private fun naturalSizes(
    sizes: List<Size>,
    effects: List<EffectSpec>,
    clipEffects: List<EffectSpec> = emptyList(),
  ): List<Size> =
    listOf(sizes, sizes.reversed()).map { order ->
      val clips = order.map { fixture.clip(size = it, effects = clipEffects) }
      fixture.resolve(Composition(listOf(Track(clips)), effects = effects)).naturalSize
    }

  @Test
  fun `a crop rect after a canvas crop and a scale writes one frame in either order`() {
    val rect = NormalizedRect(0f, 0f, 0.5f, 1f)
    for (sizes in APP_PAIRS) {
      withClue("$sizes") {
        naturalSizes(sizes, listOf(Crop(AspectRatio(4, 3), Fit.Contain), CropRect(rect), Scale(540))) shouldBe
          listOf(Size(360, 540), Size(360, 540))
      }
    }
  }

  @Test
  fun `a crop rect after a cutting crop and a scale writes one frame in either order`() {
    val rect = NormalizedRect(0.25f, 0f, 0.75f, 0.5f)
    for (sizes in APP_PAIRS) {
      withClue("$sizes") {
        naturalSizes(sizes, listOf(Crop(AspectRatio(16, 9)), CropRect(rect), Scale(540))) shouldBe
          listOf(Size(960, 540), Size(960, 540))
      }
    }
  }

  @Test
  fun `a free turn of a clip's canvas crop and a scale writes one frame in either order`() {
    val radians = 30 * PI / 180
    val width = 4.0 / 3 * cos(radians) + sin(radians)
    val height = 4.0 / 3 * sin(radians) + cos(radians)
    for (scaled in listOf(324, 540, 1080)) {
      val expected = Size((scaled * width / height).roundToInt(), scaled)
      for (sizes in APP_PAIRS) {
        withClue("$sizes scaled to $scaled") {
          naturalSizes(sizes, listOf(Rotate(30), Scale(scaled)), listOf(Crop(AspectRatio(4, 3), Fit.Contain))) shouldBe
            listOf(expected, expected)
        }
      }
    }
  }

  // A height never scales up, so a cap above the first clip's frame leaves that frame, and the first clip decides.
  @Test
  fun `a cap above a clip leaves the first clip's frame where a scale does not`() {
    val sizes = listOf(Size(180, 320), Size(320, 180))
    val effects = listOf<EffectSpec>(Crop(AspectRatio(4, 3), Fit.Contain))
    val written =
      listOf(sizes, sizes.reversed()).map { order ->
        val composition = Composition(listOf(Track(order.map { fixture.clip(size = it) })), effects = effects)
        fixture.resolve(composition, ExportSpec(heightPx = 250)).output.size
      }

    written shouldBe listOf(Size(334, 250), Size(240, 180))
  }

  @Test
  fun `a height the spec asks for under a crop that cuts writes one frame in either order`() {
    for (sizes in APP_PAIRS) {
      for (aspect in listOf(AspectRatio(4, 3), AspectRatio(9, 16))) {
        val expected = onEvenGrid(Size((100 * aspect.value).roundToInt(), 100), aspect)
        val written =
          listOf(sizes, sizes.reversed())
            .map { order ->
              val composition =
                Composition(listOf(Track(order.map { fixture.clip(size = it) })), effects = listOf(Crop(aspect)))
              fixture.resolve(composition, ExportSpec(heightPx = 100)).output.size
            }.toSet()

        withClue("$sizes cut to $aspect capped to 100") { written shouldBe setOf(expected) }
      }
    }
  }

  @Test
  fun `a frame with no canvas crop keeps its own aspect through a scale`() {
    val composition =
      Composition(listOf(Track(listOf(fixture.clip(size = Size(1920, 1080))))), effects = listOf(Scale(540)))

    fixture.resolve(composition).naturalSize shouldBe Size(960, 540)
  }

  @Test
  fun `a canvas whose width is whole at the first clip's height is the frame it was`() {
    val composition =
      Composition(
        listOf(Track(listOf(fixture.clip(size = Size(1920, 1080))))),
        effects = listOf(Crop(AspectRatio(4, 3), Fit.Contain), Scale(540)),
      )

    fixture.resolve(composition).naturalSize shouldBe Size(720, 540)
  }

  // The size an encoder that writes even sides takes for a frame of this aspect.
  private fun onEvenGrid(
    size: Size,
    aspect: AspectRatio,
  ) = MeasuredFrame(size, aspect.value).fittedTo(null, 2)

  // The frames crop-join found drifting: the width followed the rounded pixels and not the aspect asked for.
  @Test
  fun `a contain crop at the first clip's height writes the width the aspect asks for`() {
    val aspect = AspectRatio(4, 3)
    for (size in listOf(Size(91, 51), Size(97, 61), Size(31, 17))) {
      val clipCrop = fixture.clip(size = size, effects = listOf(Crop(aspect, Fit.Contain)))
      val onClip = fixture.resolve(Composition(listOf(Track(listOf(clipCrop)))))
      val onComposition =
        fixture.resolve(
          Composition(listOf(Track(listOf(fixture.clip(size = size)))), effects = listOf(Crop(aspect, Fit.Contain))),
        )

      for (plan in listOf(onClip, onComposition)) {
        val exact = plan.output.size.height * aspect.value
        withClue("$size") {
          plan.output.size shouldBe MeasuredFrame(plan.naturalSize, aspect.value).fittedTo(null, 2)
          abs(plan.output.size.width - exact) shouldBeLessThan 1.01f
        }
      }
    }
    fixture
      .resolve(
        Composition(
          listOf(Track(listOf(fixture.clip(size = Size(91, 51))))),
          effects = listOf(Crop(aspect, Fit.Contain)),
        ),
      ).output.size shouldBe Size(66, 50)
  }

  private fun Verdict.planned() =
    when (this) {
      is Verdict.Capable -> plan
      is Verdict.Degraded -> plan
      else -> error("No plan in $this")
    }

  @Test
  fun `a preview and an export of the same edit report one frame`() {
    val composition =
      Composition(
        listOf(Track(listOf(fixture.clip(size = Size(91, 51))))),
        effects = listOf(Crop(AspectRatio(4, 3), Fit.Contain)),
      )

    val preview =
      fixture
        .plan(composition, grade = ResolvedHdr.Keep)
        .planned()
        .output.size
    val export =
      fixture
        .plan(composition)
        .planned()
        .output.size

    preview shouldBe export
    export shouldBe Size(66, 50)
  }

  // Every ordering of [count] of [sizes].
  private fun orders(
    sizes: List<Size>,
    count: Int,
  ): List<List<Size>> =
    if (count == 0) {
      listOf(emptyList())
    } else {
      sizes.flatMap { first -> orders(sizes - first, count - 1).map { listOf(first) + it } }
    }

  private companion object {
    val SIZES = listOf(Size(192, 108), Size(86, 48), Size(64, 48), Size(108, 192), Size(854, 480))
    val ASPECTS = listOf(AspectRatio(4, 3), AspectRatio(16, 9), AspectRatio(9, 16), AspectRatio(1, 1))
    val HEIGHTS = listOf(192, 324, 1080, 1920)
    val APP_PAIRS = listOf(listOf(Size(180, 320), Size(320, 180)), listOf(Size(3840, 2160), Size(640, 480)))
    const val SCALED = 324
    const val TURNED_HEIGHT = 1080
    const val QUARTER_TURN = 90
  }
}
