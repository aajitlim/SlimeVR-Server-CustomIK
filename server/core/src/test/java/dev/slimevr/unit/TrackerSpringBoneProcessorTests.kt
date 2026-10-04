package dev.slimevr.unit

import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.processor.retarget.TrackerSpringBoneProcessor
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import kotlin.test.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class TrackerSpringBoneProcessorTests {
	private val role = TrackerRole.CHEST

	@Test
	fun disabledSpringLeavesPositionUntouched() {
		val processor = TrackerSpringBoneProcessor()
		val source = Vector3(1f, 2f, 3f)
		val config = TrackerSpringBoneConfig(false, 0.03f, 12f, 0.65f)

		val output = processor.apply(role, source, config, 1_000_000_000L)

		assertEquals(source.x, output.x)
		assertEquals(source.y, output.y)
		assertEquals(source.z, output.z)
	}

	@Test
	fun springOnlyChangesVerticalAxis() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 10f, 1f)

		processor.apply(role, Vector3(1f, 1f, 3f), config, 1_000_000_000L)
		val output = processor.apply(
			role,
			Vector3(1f, 1.05f, 3f),
			config,
			1_016_666_667L,
		)

		assertEquals(1f, output.x)
		assertEquals(3f, output.z)
		assertTrue(output.y != 1.05f)
	}

	@Test
	fun springOffsetNeverExceedsConfiguredDistance() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.02f, 8f, 2f)

		var time = 1_000_000_000L
		var baseY = 1f
		processor.apply(role, Vector3(0f, baseY, 0f), config, time)

		repeat(120) { index ->
			time += 16_666_667L
			baseY = if (index % 2 == 0) 1.2f else 0.8f
			val output = processor.apply(role, Vector3(0f, baseY, 0f), config, time)
			assertTrue(abs(output.y - baseY) <= 0.02001f)
		}
	}

	@Test
	fun staleFrameGapResetsSpringInsteadOfExploding() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 12f, 1f)

		processor.apply(role, Vector3(0f, 1f, 0f), config, 1_000_000_000L)
		processor.apply(role, Vector3(0f, 1.05f, 0f), config, 1_016_666_667L)
		val output = processor.apply(
			role,
			Vector3(0f, 1.5f, 0f),
			config,
			2_000_000_000L,
		)

		assertEquals(1.5f, output.y)
	}
}
