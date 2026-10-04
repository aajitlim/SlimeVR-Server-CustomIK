package dev.slimevr.tracking.processor.retarget

import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/**
 * Adds bounded inertial secondary motion to exported tracker Y positions.
 *
 * X/Z and all tracker rotations remain untouched.
 *
 * The default driver derives an impulse from changes in solved vertical
 * velocity. When a physical IMU world-Y acceleration sample is supplied, that
 * acceleration becomes the fast transient driver while a small amount of the
 * position-derived signal remains as a stabilizer.
 */
class TrackerSpringBoneProcessor {
	private data class SpringState(
		var offset: Float = 0f,
		var velocity: Float = 0f,
		var lastBaseY: Float = 0f,
		var filteredBaseVelocity: Float = 0f,
		var lastTimeNanos: Long = 0L,
		var accelerationBaselineY: Float = 0f,
		var filteredDynamicAccelerationG: Float = 0f,
		var hasAccelerationBaseline: Boolean = false,
	)

	private val states = mutableMapOf<TrackerRole, SpringState>()

	fun reset(role: TrackerRole) {
		states.remove(role)
	}

	fun resetAll() {
		states.clear()
	}

	fun apply(
		role: TrackerRole,
		sourcePosition: Vector3,
		config: TrackerSpringBoneConfig,
		nowNanos: Long = System.nanoTime(),
		accelerationY: Float? = null,
	): Vector3 {
		if (!config.enabled || config.distance <= 0f || config.pull <= 0f) {
			reset(role)
			return sourcePosition
		}

		val state = states.getOrPut(role) {
			SpringState(
				lastBaseY = sourcePosition.y,
				lastTimeNanos = nowNanos,
			)
		}

		if (state.lastTimeNanos == nowNanos) {
			return sourcePosition
		}

		val rawDt = (nowNanos - state.lastTimeNanos).toDouble() / 1_000_000_000.0
		if (rawDt <= 0.0 || rawDt > 0.125) {
			state.offset = 0f
			state.velocity = 0f
			state.lastBaseY = sourcePosition.y
			state.filteredBaseVelocity = 0f
			state.accelerationBaselineY = accelerationY ?: 0f
			state.filteredDynamicAccelerationG = 0f
			state.hasAccelerationBaseline = accelerationY?.isFinite() == true
			state.lastTimeNanos = nowNanos
			return sourcePosition
		}

		val dt = rawDt.toFloat().coerceIn(1f / 240f, 1f / 20f)
		val measuredBaseVelocity =
			((sourcePosition.y - state.lastBaseY) / dt).coerceIn(-5f, 5f)

		// Smooth only the position-derived driving velocity. The actual solved
		// tracker position is never delayed.
		val velocityFollow = (dt * 18f).coerceIn(0f, 1f)
		val newFilteredBaseVelocity =
			state.filteredBaseVelocity +
				(measuredBaseVelocity - state.filteredBaseVelocity) * velocityFollow
		val anchorDeltaVelocity =
			newFilteredBaseVelocity - state.filteredBaseVelocity

		val pull = config.pull.coerceIn(0f, 2f)
		val validAcceleration =
			accelerationY?.takeIf { it.isFinite() && abs(it) < 32f }

		if (validAcceleration != null) {
			if (!state.hasAccelerationBaseline) {
				// First valid sample becomes the static gravity/bias reference so
				// enabling acceleration mode cannot kick the spring immediately.
				state.accelerationBaselineY = validAcceleration
				state.filteredDynamicAccelerationG = 0f
				state.hasAccelerationBaseline = true
			}

			// Slowly adapt the baseline to gravity / sensor bias while preserving
			// fast body acceleration as the transient signal.
			val baselineFollow = (dt * 0.65f).coerceIn(0f, 1f)
			state.accelerationBaselineY +=
				(validAcceleration - state.accelerationBaselineY) * baselineFollow

			// Normalize against the observed static gravity scale. This keeps the
			// driver useful across tracker transports that encode acceleration at
			// slightly different scales.
			val gravityScale = abs(state.accelerationBaselineY).coerceAtLeast(0.25f)
			var dynamicAccelerationG =
				(validAcceleration - state.accelerationBaselineY) / gravityScale

			// Small deadzone removes stationary IMU chatter before it reaches the
			// oscillator.
			val deadzoneG = 0.025f
			dynamicAccelerationG = when {
				dynamicAccelerationG > deadzoneG -> dynamicAccelerationG - deadzoneG
				dynamicAccelerationG < -deadzoneG -> dynamicAccelerationG + deadzoneG
				else -> 0f
			}.coerceIn(-3f, 3f)

			val accelerationFollow = (dt * 28f).coerceIn(0f, 1f)
			state.filteredDynamicAccelerationG +=
				(dynamicAccelerationG - state.filteredDynamicAccelerationG) *
					accelerationFollow

			// Convert normalized gravity units into a velocity impulse. We never
			// integrate IMU acceleration into absolute tracker position.
			val accelerationVelocityKick =
				state.filteredDynamicAccelerationG * GRAVITY_MPS2 * dt

			state.velocity -= accelerationVelocityKick * pull

			// Retain a small amount of the solved-position derivative as a
			// stabilizer and cross-check while the accelerometer supplies the
			// high-frequency excitation.
			state.velocity -=
				anchorDeltaVelocity * pull * POSITION_STABILIZER_BLEND
		} else {
			// No usable IMU acceleration: fall back to the original
			// position-derived excitation.
			state.hasAccelerationBaseline = false
			state.filteredDynamicAccelerationG = 0f
			state.velocity -= anchorDeltaVelocity * pull
		}

		val strength = config.strength.coerceIn(1f, 30f)
		val springAcceleration =
			-(strength * strength) * state.offset -
				(2f * DAMPING_RATIO * strength) * state.velocity

		// Semi-implicit integration is stable for this small real-time oscillator.
		state.velocity += springAcceleration * dt
		state.offset += state.velocity * dt

		val maxDistance = config.distance.coerceIn(0f, 0.25f)
		if (abs(state.offset) > maxDistance) {
			state.offset = state.offset.coerceIn(-maxDistance, maxDistance)
			if ((state.offset > 0f && state.velocity > 0f) ||
				(state.offset < 0f && state.velocity < 0f)
			) {
				state.velocity = 0f
			}
		}

		state.lastBaseY = sourcePosition.y
		state.filteredBaseVelocity = newFilteredBaseVelocity
		state.lastTimeNanos = nowNanos

		return Vector3(
			sourcePosition.x,
			sourcePosition.y + state.offset,
			sourcePosition.z,
		)
	}

	companion object {
		private const val GRAVITY_MPS2 = 9.80665f
		private const val DAMPING_RATIO = 0.38f
		private const val POSITION_STABILIZER_BLEND = 0.15f
	}
}
