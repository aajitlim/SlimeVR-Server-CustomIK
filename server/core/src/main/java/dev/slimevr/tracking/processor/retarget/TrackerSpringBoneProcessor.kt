package dev.slimevr.tracking.processor.retarget

import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import kotlin.math.tanh

/**
 * Adds bounded inertial secondary motion to exported tracker Y positions.
 *
 * X/Z and all tracker rotations remain untouched.
 *
 * The default driver derives an impulse from changes in solved vertical
 * velocity. When a physical IMU world-Y acceleration sample is supplied, that
 * acceleration becomes the fast transient driver while a small amount of the
 * position-derived signal remains as a stabilizer.
 *
 * An optional derivative driver builds a filtered hierarchy:
 *
 *   acceleration -> jerk -> snap
 *
 * Jerk and snap are normalized by a characteristic response time before the
 * configured weights are combined. This keeps derivative terms comparable to
 * acceleration instead of allowing their different units to dominate simply
 * because they are numerically larger.
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

		var previousDerivativeAccelerationG: Float = 0f,
		var filteredJerkGPerSecond: Float = 0f,
		var previousFilteredJerkGPerSecond: Float = 0f,
		var filteredSnapGPerSecondSquared: Float = 0f,
		var hasDerivativeHistory: Boolean = false,
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
		if (rawDt <= 0.0 || rawDt > MAX_FRAME_GAP_SECONDS) {
			resetStateAfterGap(
				state = state,
				sourcePosition = sourcePosition,
				accelerationY = accelerationY,
				nowNanos = nowNanos,
			)
			return sourcePosition
		}

		val dt = rawDt.toFloat().coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
		val measuredBaseVelocity =
			((sourcePosition.y - state.lastBaseY) / dt).coerceIn(-5f, 5f)

		// Smooth only the position-derived driving velocity. The actual solved
		// tracker position is never delayed.
		val velocityFollow = (dt * POSITION_VELOCITY_FILTER_HZ).coerceIn(0f, 1f)
		val newFilteredBaseVelocity =
			state.filteredBaseVelocity +
				(measuredBaseVelocity - state.filteredBaseVelocity) * velocityFollow
		val anchorDeltaVelocity =
			newFilteredBaseVelocity - state.filteredBaseVelocity

		val pull = config.pull.coerceIn(0f, 2f)
		val validAcceleration =
			accelerationY?.takeIf { it.isFinite() && abs(it) < MAX_VALID_ACCELERATION }

		if (validAcceleration != null) {
			val driveG = buildAccelerationDriveG(
				state = state,
				accelerationY = validAcceleration,
				config = config,
				dt = dt,
			)

			// Acceleration is used only as a transient velocity excitation.
			// It is never integrated into absolute tracker position.
			state.velocity -= driveG * GRAVITY_MPS2 * dt * pull

			// Keep a small amount of solved-position response while IMU data is
			// active. This anchors the effect to the reconstructed body and
			// provides a stable fallback-like cross-check.
			state.velocity -=
				anchorDeltaVelocity * pull * POSITION_STABILIZER_BLEND
		} else {
			resetAccelerationDriverState(state)

			// No usable IMU acceleration: fall back to the original independent
			// position-derived excitation for this tracker role only.
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

	private fun buildAccelerationDriveG(
		state: SpringState,
		accelerationY: Float,
		config: TrackerSpringBoneConfig,
		dt: Float,
	): Float {
		if (!state.hasAccelerationBaseline) {
			// The first valid sample establishes the stationary gravity/bias
			// reference and must not produce an artificial enable-time kick.
			state.accelerationBaselineY = accelerationY
			state.filteredDynamicAccelerationG = 0f
			state.hasAccelerationBaseline = true
			resetDerivativeHistory(state)
			return 0f
		}

		// Slowly follow gravity and stationary sensor bias while leaving transient
		// body acceleration in the high-frequency component.
		val baselineFollow = (dt * ACCELERATION_BASELINE_FILTER_HZ).coerceIn(0f, 1f)
		state.accelerationBaselineY +=
			(accelerationY - state.accelerationBaselineY) * baselineFollow

		// Normalize against observed stationary gravity magnitude. This is more
		// tolerant of transports/firmware that expose acceleration at slightly
		// different scales.
		val gravityScale = abs(state.accelerationBaselineY).coerceAtLeast(MIN_GRAVITY_SCALE)
		var dynamicAccelerationG =
			(accelerationY - state.accelerationBaselineY) / gravityScale

		dynamicAccelerationG = applyDeadzone(
			dynamicAccelerationG,
			ACCELERATION_DEADZONE_G,
		).coerceIn(-MAX_DYNAMIC_ACCELERATION_G, MAX_DYNAMIC_ACCELERATION_G)

		val response = config.derivativeResponse.coerceIn(0f, 1f)
		val accelerationFilterHz = lerp(
			ACCEL_FILTER_HZ_SMOOTH,
			ACCEL_FILTER_HZ_REACTIVE,
			response,
		)
		val accelerationFollow = (dt * accelerationFilterHz).coerceIn(0f, 1f)
		val filteredAccelerationG =
			state.filteredDynamicAccelerationG +
				(dynamicAccelerationG - state.filteredDynamicAccelerationG) *
					accelerationFollow

		state.filteredDynamicAccelerationG = filteredAccelerationG

		if (!config.derivativeDriverEnabled) {
			resetDerivativeHistory(state)
			return filteredAccelerationG
		}

		return buildDerivativeDriveG(
			state = state,
			filteredAccelerationG = filteredAccelerationG,
			config = config,
			dt = dt,
			response = response,
		)
	}

	private fun buildDerivativeDriveG(
		state: SpringState,
		filteredAccelerationG: Float,
		config: TrackerSpringBoneConfig,
		dt: Float,
		response: Float,
	): Float {
		if (!state.hasDerivativeHistory) {
			state.previousDerivativeAccelerationG = filteredAccelerationG
			state.filteredJerkGPerSecond = 0f
			state.previousFilteredJerkGPerSecond = 0f
			state.filteredSnapGPerSecondSquared = 0f
			state.hasDerivativeHistory = true

			// Acceleration may still contribute on the initialization frame, but
			// jerk/snap begin from zero so toggling derivative mode cannot create
			// a synthetic derivative spike.
			return softClampDriveG(
				weightedDerivativeDrive(
					accelerationG = filteredAccelerationG,
					normalizedJerkG = 0f,
					normalizedSnapG = 0f,
					config = config,
				),
			)
		}

		val rawJerkGPerSecond =
			((filteredAccelerationG - state.previousDerivativeAccelerationG) / dt)
				.coerceIn(-MAX_JERK_G_PER_SECOND, MAX_JERK_G_PER_SECOND)

		val jerkFilterHz = lerp(
			JERK_FILTER_HZ_SMOOTH,
			JERK_FILTER_HZ_REACTIVE,
			response,
		)
		val jerkFollow = (dt * jerkFilterHz).coerceIn(0f, 1f)
		val newFilteredJerkGPerSecond =
			state.filteredJerkGPerSecond +
				(rawJerkGPerSecond - state.filteredJerkGPerSecond) * jerkFollow

		val rawSnapGPerSecondSquared =
			((newFilteredJerkGPerSecond - state.previousFilteredJerkGPerSecond) / dt)
				.coerceIn(
					-MAX_SNAP_G_PER_SECOND_SQUARED,
					MAX_SNAP_G_PER_SECOND_SQUARED,
				)

		val snapFilterHz = lerp(
			SNAP_FILTER_HZ_SMOOTH,
			SNAP_FILTER_HZ_REACTIVE,
			response,
		)
		val snapFollow = (dt * snapFilterHz).coerceIn(0f, 1f)
		val newFilteredSnapGPerSecondSquared =
			state.filteredSnapGPerSecondSquared +
				(rawSnapGPerSecondSquared - state.filteredSnapGPerSecondSquared) *
					snapFollow

		// Convert derivative units back to acceleration-like normalized values:
		//
		//   jerk * tau
		//   snap * tau^2
		//
		// A smoother response uses a longer characteristic time; a reactive
		// response uses a shorter one.
		val tauSeconds = lerp(
			DERIVATIVE_TAU_SECONDS_SMOOTH,
			DERIVATIVE_TAU_SECONDS_REACTIVE,
			response,
		)
		val normalizedJerkG = newFilteredJerkGPerSecond * tauSeconds
		val normalizedSnapG =
			newFilteredSnapGPerSecondSquared * tauSeconds * tauSeconds

		val weightedDrive = weightedDerivativeDrive(
			accelerationG = filteredAccelerationG,
			normalizedJerkG = normalizedJerkG,
			normalizedSnapG = normalizedSnapG,
			config = config,
		)

		state.previousDerivativeAccelerationG = filteredAccelerationG
		state.filteredJerkGPerSecond = newFilteredJerkGPerSecond
		state.previousFilteredJerkGPerSecond = newFilteredJerkGPerSecond
		state.filteredSnapGPerSecondSquared = newFilteredSnapGPerSecondSquared

		return softClampDriveG(weightedDrive)
	}

	private fun weightedDerivativeDrive(
		accelerationG: Float,
		normalizedJerkG: Float,
		normalizedSnapG: Float,
		config: TrackerSpringBoneConfig,
	): Float {
		val accelerationWeight = config.accelerationWeight.coerceIn(0f, 2f)
		val jerkWeight = config.jerkWeight.coerceIn(0f, 2f)
		val snapWeight = config.snapWeight.coerceIn(0f, 2f)
		val weightSum = accelerationWeight + jerkWeight + snapWeight

		if (weightSum <= 1e-6f) return 0f

		return (
			accelerationG * accelerationWeight +
				normalizedJerkG * jerkWeight +
				normalizedSnapG * snapWeight
		) / weightSum
	}

	private fun softClampDriveG(driveG: Float): Float {
		// tanh is approximately linear near zero but smoothly saturates larger
		// derivative spikes instead of hard-clipping them into sharp corners.
		return (
			DRIVE_SOFT_LIMIT_G *
				tanh((driveG / DRIVE_SOFT_LIMIT_G).toDouble())
		).toFloat()
	}

	private fun applyDeadzone(value: Float, deadzone: Float): Float =
		when {
			value > deadzone -> value - deadzone
			value < -deadzone -> value + deadzone
			else -> 0f
		}

	private fun resetStateAfterGap(
		state: SpringState,
		sourcePosition: Vector3,
		accelerationY: Float?,
		nowNanos: Long,
	) {
		state.offset = 0f
		state.velocity = 0f
		state.lastBaseY = sourcePosition.y
		state.filteredBaseVelocity = 0f
		state.accelerationBaselineY = accelerationY ?: 0f
		state.filteredDynamicAccelerationG = 0f
		state.hasAccelerationBaseline = accelerationY?.isFinite() == true
		resetDerivativeHistory(state)
		state.lastTimeNanos = nowNanos
	}

	private fun resetAccelerationDriverState(state: SpringState) {
		state.hasAccelerationBaseline = false
		state.filteredDynamicAccelerationG = 0f
		resetDerivativeHistory(state)
	}

	private fun resetDerivativeHistory(state: SpringState) {
		state.previousDerivativeAccelerationG = 0f
		state.filteredJerkGPerSecond = 0f
		state.previousFilteredJerkGPerSecond = 0f
		state.filteredSnapGPerSecondSquared = 0f
		state.hasDerivativeHistory = false
	}

	private fun lerp(from: Float, to: Float, t: Float): Float =
		from + (to - from) * t

	companion object {
		private const val GRAVITY_MPS2 = 9.80665f
		private const val DAMPING_RATIO = 0.38f
		private const val POSITION_STABILIZER_BLEND = 0.15f

		private const val MAX_FRAME_GAP_SECONDS = 0.125
		private const val MIN_DT_SECONDS = 1f / 240f
		private const val MAX_DT_SECONDS = 1f / 20f

		private const val POSITION_VELOCITY_FILTER_HZ = 18f
		private const val ACCELERATION_BASELINE_FILTER_HZ = 0.65f
		private const val MIN_GRAVITY_SCALE = 0.25f
		private const val ACCELERATION_DEADZONE_G = 0.025f
		private const val MAX_VALID_ACCELERATION = 32f
		private const val MAX_DYNAMIC_ACCELERATION_G = 3f

		private const val ACCEL_FILTER_HZ_SMOOTH = 14f
		private const val ACCEL_FILTER_HZ_REACTIVE = 36f
		private const val JERK_FILTER_HZ_SMOOTH = 8f
		private const val JERK_FILTER_HZ_REACTIVE = 28f
		private const val SNAP_FILTER_HZ_SMOOTH = 6f
		private const val SNAP_FILTER_HZ_REACTIVE = 22f

		private const val DERIVATIVE_TAU_SECONDS_SMOOTH = 0.18f
		private const val DERIVATIVE_TAU_SECONDS_REACTIVE = 0.06f

		private const val MAX_JERK_G_PER_SECOND = 80f
		private const val MAX_SNAP_G_PER_SECOND_SQUARED = 800f
		private const val DRIVE_SOFT_LIMIT_G = 3f
	}
}
