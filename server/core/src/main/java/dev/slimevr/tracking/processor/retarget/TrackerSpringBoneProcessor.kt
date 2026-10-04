package dev.slimevr.tracking.processor.retarget

import dev.slimevr.config.TrackerSpringBoneConfig
import dev.slimevr.tracking.trackers.TrackerRole
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs

/**
 * Adds bounded inertial secondary motion to exported tracker Y positions.
 *
 * X/Z and all tracker rotations remain untouched. The spring is driven by
 * changes in the solved point's vertical velocity, so steady slow motion stays
 * close to the anatomical solve while starts/stops create a small bounce.
 */
class TrackerSpringBoneProcessor {
	private data class SpringState(
		var offset: Float = 0f,
		var velocity: Float = 0f,
		var lastBaseY: Float = 0f,
		var filteredBaseVelocity: Float = 0f,
		var lastTimeNanos: Long = 0L,
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
			state.lastTimeNanos = nowNanos
			return sourcePosition
		}

		val dt = rawDt.toFloat().coerceIn(1f / 240f, 1f / 20f)
		val measuredBaseVelocity =
			((sourcePosition.y - state.lastBaseY) / dt).coerceIn(-5f, 5f)

		// Smooth only the driving velocity. The actual solved position is never
		// smoothed or delayed.
		val velocityFollow = (dt * 18f).coerceIn(0f, 1f)
		val newFilteredBaseVelocity =
			state.filteredBaseVelocity +
				(measuredBaseVelocity - state.filteredBaseVelocity) * velocityFollow
		val anchorDeltaVelocity =
			newFilteredBaseVelocity - state.filteredBaseVelocity

		val pull = config.pull.coerceIn(0f, 2f)
		state.velocity -= anchorDeltaVelocity * pull

		val strength = config.strength.coerceIn(1f, 30f)
		val dampingRatio = 0.38f
		val springAcceleration =
			-(strength * strength) * state.offset -
				(2f * dampingRatio * strength) * state.velocity

		// Semi-implicit integration is substantially more stable than updating
		// position first for this kind of small real-time oscillator.
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
}
