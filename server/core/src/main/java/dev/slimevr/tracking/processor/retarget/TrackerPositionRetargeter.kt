package dev.slimevr.tracking.processor.retarget

import dev.slimevr.config.TrackerPositionAdjustmentConfig
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3

enum class PositionRetargetSpace {
	WORLD,
	BODY_YAW,
	;

	companion object {
		fun fromConfig(value: String?): PositionRetargetSpace = when (value?.lowercase()) {
			"world" -> WORLD
			else -> BODY_YAW
		}
	}
}

/**
 * Pure position-space retargeting used at bridge export time.
 *
 * This class deliberately knows nothing about tracker rotation output. The
 * caller supplies the already-solved position and a yaw reference, and gets a
 * translated position back. No skeleton state is mutated.
 */
object TrackerPositionRetargeter {
	fun apply(
		sourcePosition: Vector3,
		bodyYaw: Quaternion,
		adjustment: TrackerPositionAdjustmentConfig,
	): Vector3 {
		if (!adjustment.enabled) return sourcePosition

		val offset = Vector3(adjustment.x, adjustment.y, adjustment.z)
		val worldOffset = when (PositionRetargetSpace.fromConfig(adjustment.space)) {
			PositionRetargetSpace.WORLD -> offset
			PositionRetargetSpace.BODY_YAW -> bodyYaw.sandwich(offset)
		}

		return sourcePosition + worldOffset
	}
}
