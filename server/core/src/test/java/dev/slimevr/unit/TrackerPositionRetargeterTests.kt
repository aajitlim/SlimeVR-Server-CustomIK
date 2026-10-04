package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.config.TrackerPositionAdjustmentConfig
import dev.slimevr.tracking.processor.retarget.TrackerPositionRetargeter
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import org.junit.jupiter.api.Test
import kotlin.test.assertTrue

class TrackerPositionRetargeterTests {
	private fun assertVectorApprox(expected: Vector3, actual: Vector3, epsilon: Float = 1e-5f) {
		assertTrue(kotlin.math.abs(expected.x - actual.x) <= epsilon, "x: expected ${expected.x}, got ${actual.x}")
		assertTrue(kotlin.math.abs(expected.y - actual.y) <= epsilon, "y: expected ${expected.y}, got ${actual.y}")
		assertTrue(kotlin.math.abs(expected.z - actual.z) <= epsilon, "z: expected ${expected.z}, got ${actual.z}")
	}

	@Test
	fun disabledAdjustmentDoesNothing() {
		val source = Vector3(1f, 2f, 3f)
		val adjustment = TrackerPositionAdjustmentConfig(false, 0.5f, 0.5f, 0.5f, "world")

		val result = TrackerPositionRetargeter.apply(source, Quaternion.IDENTITY, adjustment)

		assertVectorApprox(source, result)
	}

	@Test
	fun worldOffsetDoesNotDependOnBodyYaw() {
		val source = Vector3(1f, 2f, 3f)
		val adjustment = TrackerPositionAdjustmentConfig(true, 0.10f, -0.20f, 0.30f, "world")
		val yaw = Quaternion.rotationAroundYAxis(FastMath.HALF_PI)

		val result = TrackerPositionRetargeter.apply(source, yaw, adjustment)

		assertVectorApprox(Vector3(1.10f, 1.80f, 3.30f), result)
	}

	@Test
	fun bodyYawOffsetRotatesWithUserWithoutChangingSourcePose() {
		val source = Vector3(0.25f, 1.0f, -0.5f)
		val offset = Vector3(0f, 0f, 0.20f)
		val adjustment = TrackerPositionAdjustmentConfig(true, offset.x, offset.y, offset.z, "body_yaw")
		val yaw = Quaternion.rotationAroundYAxis(FastMath.HALF_PI)

		val result = TrackerPositionRetargeter.apply(source, yaw, adjustment)
		val expected = source + yaw.sandwich(offset)

		assertVectorApprox(expected, result)
		assertVectorApprox(Vector3(0.25f, 1.0f, -0.5f), source)
	}
}
