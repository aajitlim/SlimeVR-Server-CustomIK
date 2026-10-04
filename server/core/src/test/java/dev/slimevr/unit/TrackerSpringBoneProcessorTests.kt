package dev.slimevr.unit

import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.processor.retarget.TrackerSpringBoneProcessor
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import org.junit.jupiter.api.Test
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
	fun constantAccelerationBiasDoesNotContinuouslyDriveSpring() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 12f, 1f)

		var time = 1_000_000_000L
		val source = Vector3(0f, 1f, 0f)
		processor.apply(role, source, config, time, accelerationY = 1f)

		repeat(30) {
			time += 16_666_667L
			val output = processor.apply(
				role,
				source,
				config,
				time,
				accelerationY = 1f,
			)
			assertTrue(abs(output.y - source.y) < 1e-4f)
		}
	}

	@Test
	fun accelerationTransientKicksOnlyVerticalSpringOffset() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 10f, 1f)
		val source = Vector3(1f, 2f, 3f)

		var time = 1_000_000_000L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		val output = processor.apply(
			role,
			source,
			config,
			time,
			accelerationY = 1.8f,
		)

		assertEquals(source.x, output.x)
		assertEquals(source.z, output.z)
		assertTrue(output.y != source.y)
		assertTrue(abs(output.y - source.y) <= config.distance + 1e-5f)
	}

	@Test
	fun zeroDerivativeWeightsDoNotInjectImuMotion() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 10f, 1f).apply {
			derivativeDriverEnabled = true
			accelerationWeight = 0f
			jerkWeight = 0f
			snapWeight = 0f
		}
		val source = Vector3(1f, 2f, 3f)

		var time = 1_000_000_000L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		val output = processor.apply(
			role,
			source,
			config,
			time,
			accelerationY = 2f,
		)

		assertEquals(source.x, output.x)
		assertEquals(source.y, output.y)
		assertEquals(source.z, output.z)
	}

	@Test
	fun jerkOnlyDerivativeDriverRespondsToAccelerationRateChange() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.03f, 10f, 1f).apply {
			derivativeDriverEnabled = true
			accelerationWeight = 0f
			jerkWeight = 1f
			snapWeight = 0f
			derivativeResponse = 0.7f
		}
		val source = Vector3(1f, 2f, 3f)

		var time = 1_000_000_000L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1f)

		// First changed sample initializes derivative history.
		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1.4f)

		// A second change produces a non-zero filtered jerk.
		time += 16_666_667L
		val output = processor.apply(
			role,
			source,
			config,
			time,
			accelerationY = 2f,
		)

		assertEquals(source.x, output.x)
		assertEquals(source.z, output.z)
		assertTrue(output.y != source.y)
		assertTrue(abs(output.y - source.y) <= config.distance + 1e-5f)
	}

	@Test
	fun snapOnlyDerivativeDriverRespondsToJerkChange() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.03f, 10f, 1f).apply {
			derivativeDriverEnabled = true
			accelerationWeight = 0f
			jerkWeight = 0f
			snapWeight = 1f
			derivativeResponse = 0.7f
		}
		val source = Vector3(1f, 2f, 3f)

		var time = 1_000_000_000L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		processor.apply(role, source, config, time, accelerationY = 1f)

		time += 16_666_667L
		val output = processor.apply(
			role,
			source,
			config,
			time,
			accelerationY = 1.6f,
		)

		assertEquals(source.x, output.x)
		assertEquals(source.z, output.z)
		assertTrue(output.y != source.y)
		assertTrue(abs(output.y - source.y) <= config.distance + 1e-5f)
	}

	@Test
	fun springStateIsIndependentPerTrackerRole() {
		val processor = TrackerSpringBoneProcessor()
		val config = TrackerSpringBoneConfig(true, 0.05f, 10f, 1f)

		var time = 1_000_000_000L
		val chestBase = Vector3(0f, 1.4f, 0f)
		val waistBase = Vector3(0f, 1.0f, 0f)

		processor.apply(
			TrackerRole.CHEST,
			chestBase,
			config,
			time,
			accelerationY = 1f,
		)
		processor.apply(
			TrackerRole.WAIST,
			waistBase,
			config,
			time,
			accelerationY = 1f,
		)

		time += 16_666_667L
		processor.apply(
			TrackerRole.CHEST,
			chestBase,
			config,
			time,
			accelerationY = 2f,
		)

		val waistOutput = processor.apply(
			TrackerRole.WAIST,
			waistBase,
			config,
			time,
			accelerationY = 1f,
		)

		assertEquals(waistBase.x, waistOutput.x)
		assertEquals(waistBase.y, waistOutput.y)
		assertEquals(waistBase.z, waistOutput.z)
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
