package dev.slimevr.unit

import com.jme3.math.FastMath
import dev.slimevr.tracking.processor.skeleton.SpineRotationAnchor
import dev.slimevr.tracking.processor.skeleton.SpineRotationDistributor
import io.github.axisangles.ktmath.Quaternion
import org.junit.jupiter.api.Test
import kotlin.math.abs
import kotlin.test.assertTrue

class SpineRotationDistributorTests {
	private fun assertQuaternionApprox(
		expected: Quaternion,
		actual: Quaternion,
		epsilon: Float = 1e-5f,
	) {
		assertTrue(abs(expected.w - actual.w) <= epsilon)
		assertTrue(abs(expected.x - actual.x) <= epsilon)
		assertTrue(abs(expected.y - actual.y) <= epsilon)
		assertTrue(abs(expected.z - actual.z) <= epsilon)
	}

	@Test
	fun preservesAnchorRotations() {
		val chest = Quaternion.IDENTITY
		val hip = Quaternion.rotationAroundYAxis(FastMath.HALF_PI)
		val anchors = listOf(
			SpineRotationAnchor(0.4f, chest),
			SpineRotationAnchor(0.95f, hip),
		)

		assertQuaternionApprox(chest, SpineRotationDistributor.sample(anchors, 0.4f, 1f))
		assertQuaternionApprox(hip, SpineRotationDistributor.sample(anchors, 0.95f, 1f))
	}

	@Test
	fun distributesMissingWaistByItsPositionBetweenAnchors() {
		val chest = Quaternion.IDENTITY
		val hip = Quaternion.rotationAroundYAxis(FastMath.HALF_PI)
		val anchors = listOf(
			SpineRotationAnchor(0.42857143f, chest),
			SpineRotationAnchor(0.96428573f, hip),
		)

		val waistPosition = 0.75f
		val expectedT = 0.6f
		val expected = chest.interpQ(hip, expectedT).unit()
		val actual = SpineRotationDistributor.sample(anchors, waistPosition, 1f)

		assertQuaternionApprox(expected, actual)
	}

	@Test
	fun curvePowerCanMoveBendEarlierWithoutChangingAnchors() {
		val chest = Quaternion.IDENTITY
		val hip = Quaternion.rotationAroundYAxis(FastMath.HALF_PI)
		val anchors = listOf(
			SpineRotationAnchor(0f, chest),
			SpineRotationAnchor(1f, hip),
		)

		val linear = SpineRotationDistributor.sample(anchors, 0.5f, 1f)
		val earlier = SpineRotationDistributor.sample(anchors, 0.5f, 0.5f)

		val linearForward = linear.sandwich(io.github.axisangles.ktmath.Vector3(0f, 0f, 1f))
		val earlierForward = earlier.sandwich(io.github.axisangles.ktmath.Vector3(0f, 0f, 1f))
		assertTrue(abs(earlierForward.x) > abs(linearForward.x))
	}
}
