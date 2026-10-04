package dev.slimevr.tracking.processor.skeleton

import dev.slimevr.config.BoneComplianceConfig
import dev.slimevr.config.BoneComplianceSegmentConfig
import kotlin.math.abs
import kotlin.math.tanh

data class BoneComplianceSegmentInput(
	val key: String,
	val restLength: Float,
	val relativeRotationRadians: Float,
	val relativeAccelerationY: Float?,
)

/**
 * Estimates small bounded axial strain for the torso from several independent
 * signals without chaining one segment's deformation into the next segment's
 * input.
 *
 * The returned values are fractional length changes:
 *  -0.03 = 3% compression
 *  +0.02 = 2% extension
 */
class BoneComplianceProcessor {
	private data class SegmentState(
		var strain: Float = 0f,
		var accelerationBaseline: Float = 0f,
		var filteredAcceleration: Float = 0f,
		var previousFilteredAcceleration: Float = 0f,
		var filteredJerk: Float = 0f,
		var hasAccelerationBaseline: Boolean = false,
		var hasDerivativeHistory: Boolean = false,
		var lastTimeNanos: Long = 0L,
	)

	private val states = mutableMapOf<String, SegmentState>()

	fun reset() {
		states.clear()
	}

	fun solve(
		inputs: List<BoneComplianceSegmentInput>,
		config: BoneComplianceConfig,
		nowNanos: Long = System.nanoTime(),
	): Map<String, Float> {
		if (!config.enabled || config.overallCompliance <= 0f) {
			reset()
			return inputs.associate { it.key to 0f }
		}

		val solved = mutableMapOf<String, Float>()
		val active = mutableListOf<BoneComplianceSegmentInput>()

		for (input in inputs) {
			val segmentConfig = config.getSegment(input.key)
			if (!segmentConfig.enabled ||
				segmentConfig.compliance <= 0f ||
				input.restLength <= MIN_LENGTH
			) {
				states.remove(input.key)
				solved[input.key] = 0f
				continue
			}

			active.add(input)
			solved[input.key] = solveSegment(
				input,
				segmentConfig,
				config,
				nowNanos,
			)
		}

		if (config.preserveTorsoLength && active.size > 1) {
			projectZeroNetLengthChange(active, config, solved)
		}

		// Keep the persistent state aligned with the simultaneously projected
		// solution. This prevents the per-segment smoothers from fighting the
		// total-length constraint on the next frame.
		for ((key, strain) in solved) {
			states[key]?.strain = strain
		}

		return solved
	}

	private fun solveSegment(
		input: BoneComplianceSegmentInput,
		segmentConfig: BoneComplianceSegmentConfig,
		globalConfig: BoneComplianceConfig,
		nowNanos: Long,
	): Float {
		val state = states.getOrPut(input.key) {
			SegmentState(lastTimeNanos = nowNanos)
		}

		if (state.lastTimeNanos == nowNanos) return state.strain

		val rawDt = (nowNanos - state.lastTimeNanos).toDouble() / 1_000_000_000.0
		if (rawDt <= 0.0 || rawDt > MAX_FRAME_GAP_SECONDS) {
			state.strain = 0f
			resetSensorState(state, input.relativeAccelerationY)
			state.lastTimeNanos = nowNanos
			return 0f
		}

		val dt = rawDt.toFloat().coerceIn(MIN_DT_SECONDS, MAX_DT_SECONDS)
		val blend =
			globalConfig.overallCompliance.coerceIn(0f, 1f) *
				segmentConfig.compliance.coerceIn(0f, 1f)
		val compressionLimit =
			segmentConfig.compressionLimit.coerceIn(0f, MAX_STRAIN_LIMIT) * blend
		val extensionLimit =
			segmentConfig.extensionLimit.coerceIn(0f, MAX_STRAIN_LIMIT) * blend

		// Bending shortens the effective endpoint-to-endpoint axial distance of
		// an abstract torso segment. Rotation is therefore a stable low-frequency
		// compression prior, not an oscillator force.
		val bendRatio =
			(input.relativeRotationRadians / BEND_FULL_SCALE_RADIANS)
				.coerceIn(0f, 1f)
		val rotationTarget =
			-compressionLimit * BEND_COMPRESSION_FRACTION * bendRatio

		val sensorTarget = buildSensorTarget(
			state = state,
			accelerationY = input.relativeAccelerationY,
			compressionLimit = compressionLimit,
			extensionLimit = extensionLimit,
			sensorInfluence = segmentConfig.sensorInfluence.coerceIn(0f, 1f),
			response = globalConfig.response.coerceIn(0f, 1f),
			dt = dt,
		)

		val target =
			(rotationTarget + sensorTarget)
				.coerceIn(-compressionLimit, extensionLimit)

		// This is deliberately a non-oscillating strain estimator. Secondary
		// oscillation belongs to the later Spring Bones export layer.
		val followHz = lerp(STRAIN_FOLLOW_HZ_SMOOTH, STRAIN_FOLLOW_HZ_REACTIVE, globalConfig.response.coerceIn(0f, 1f))
		val follow = (dt * followHz).coerceIn(0f, 1f)
		state.strain += (target - state.strain) * follow
		state.strain = state.strain.coerceIn(-compressionLimit, extensionLimit)
		state.lastTimeNanos = nowNanos
		return state.strain
	}

	private fun buildSensorTarget(
		state: SegmentState,
		accelerationY: Float?,
		compressionLimit: Float,
		extensionLimit: Float,
		sensorInfluence: Float,
		response: Float,
		dt: Float,
	): Float {
		val sample = accelerationY?.takeIf { it.isFinite() && abs(it) < MAX_RELATIVE_ACCELERATION }
			?: run {
				state.hasAccelerationBaseline = false
				state.hasDerivativeHistory = false
				state.filteredAcceleration = 0f
				state.filteredJerk = 0f
				return 0f
			}

		if (!state.hasAccelerationBaseline) {
			state.accelerationBaseline = sample
			state.filteredAcceleration = 0f
			state.previousFilteredAcceleration = 0f
			state.filteredJerk = 0f
			state.hasAccelerationBaseline = true
			state.hasDerivativeHistory = false
			return 0f
		}

		// Neighboring-world-Y subtraction already removes most common gravity and
		// whole-body translation. This slower baseline removes remaining sensor
		// mismatch and mounting bias.
		val baselineFollow = (dt * RELATIVE_ACCEL_BASELINE_HZ).coerceIn(0f, 1f)
		state.accelerationBaseline +=
			(sample - state.accelerationBaseline) * baselineFollow

		var dynamicAcceleration =
			(sample - state.accelerationBaseline) / RELATIVE_ACCEL_FULL_SCALE
		dynamicAcceleration = applyDeadzone(
			dynamicAcceleration,
			RELATIVE_ACCEL_DEADZONE,
		).coerceIn(-1.5f, 1.5f)

		val accelHz = lerp(RELATIVE_ACCEL_FILTER_HZ_SMOOTH, RELATIVE_ACCEL_FILTER_HZ_REACTIVE, response)
		val accelFollow = (dt * accelHz).coerceIn(0f, 1f)
		state.filteredAcceleration +=
			(dynamicAcceleration - state.filteredAcceleration) * accelFollow

		if (!state.hasDerivativeHistory) {
			state.previousFilteredAcceleration = state.filteredAcceleration
			state.filteredJerk = 0f
			state.hasDerivativeHistory = true
		}

		val rawJerk =
			((state.filteredAcceleration - state.previousFilteredAcceleration) / dt)
				.coerceIn(-MAX_NORMALIZED_JERK, MAX_NORMALIZED_JERK)
		val jerkHz = lerp(RELATIVE_JERK_FILTER_HZ_SMOOTH, RELATIVE_JERK_FILTER_HZ_REACTIVE, response)
		val jerkFollow = (dt * jerkHz).coerceIn(0f, 1f)
		state.filteredJerk += (rawJerk - state.filteredJerk) * jerkFollow

		val tau = lerp(DERIVATIVE_TAU_SMOOTH, DERIVATIVE_TAU_REACTIVE, response)
		val normalizedJerk = state.filteredJerk * tau

		// Relative acceleration estimates separation/compression tendency; jerk
		// makes starts/stops contribute without allowing a sustained acceleration
		// plateau to dominate the physical length estimate.
		val combined =
			SENSOR_ACCEL_WEIGHT * state.filteredAcceleration +
				SENSOR_JERK_WEIGHT * normalizedJerk
		val signal = tanh(combined.toDouble()).toFloat().coerceIn(-1f, 1f)

		state.previousFilteredAcceleration = state.filteredAcceleration

		val limit = if (signal >= 0f) extensionLimit else compressionLimit
		return signal * limit * sensorInfluence
	}

	private fun projectZeroNetLengthChange(
		active: List<BoneComplianceSegmentInput>,
		config: BoneComplianceConfig,
		solved: MutableMap<String, Float>,
	) {
		val adjustable = active.toMutableList()

		// A few projection iterations are enough for three torso variables and
		// respect per-segment asymmetric compression/extension limits.
		repeat(4) {
			if (adjustable.isEmpty()) return

			var totalDelta = 0f
			var totalLength = 0f
			for (input in active) {
				totalDelta += input.restLength * (solved[input.key] ?: 0f)
				totalLength += input.restLength
			}
			if (abs(totalDelta) < LENGTH_PROJECTION_EPSILON || totalLength <= MIN_LENGTH) return

			var adjustableLength = 0f
			for (input in adjustable) adjustableLength += input.restLength
			if (adjustableLength <= MIN_LENGTH) return

			val commonStrainCorrection = totalDelta / adjustableLength
			val stillAdjustable = mutableListOf<BoneComplianceSegmentInput>()

			for (input in adjustable) {
				val segmentConfig = config.getSegment(input.key)
				val blend =
					config.overallCompliance.coerceIn(0f, 1f) *
						segmentConfig.compliance.coerceIn(0f, 1f)
				val minStrain =
					-segmentConfig.compressionLimit.coerceIn(0f, MAX_STRAIN_LIMIT) * blend
				val maxStrain =
					segmentConfig.extensionLimit.coerceIn(0f, MAX_STRAIN_LIMIT) * blend
				val current = solved[input.key] ?: 0f
				val corrected = (current - commonStrainCorrection).coerceIn(minStrain, maxStrain)
				solved[input.key] = corrected

				if (corrected > minStrain + 1e-6f && corrected < maxStrain - 1e-6f) {
					stillAdjustable.add(input)
				}
			}

			adjustable.clear()
			adjustable.addAll(stillAdjustable)
		}
	}

	private fun resetSensorState(state: SegmentState, accelerationY: Float?) {
		state.accelerationBaseline = accelerationY ?: 0f
		state.filteredAcceleration = 0f
		state.previousFilteredAcceleration = 0f
		state.filteredJerk = 0f
		state.hasAccelerationBaseline = accelerationY?.isFinite() == true
		state.hasDerivativeHistory = false
	}

	private fun applyDeadzone(value: Float, deadzone: Float): Float =
		when {
			value > deadzone -> value - deadzone
			value < -deadzone -> value + deadzone
			else -> 0f
		}

	private fun lerp(from: Float, to: Float, t: Float): Float =
		from + (to - from) * t

	companion object {
		private const val MIN_LENGTH = 1e-5f
		private const val MAX_STRAIN_LIMIT = 0.12f
		private const val MAX_FRAME_GAP_SECONDS = 0.125
		private const val MIN_DT_SECONDS = 1f / 240f
		private const val MAX_DT_SECONDS = 1f / 20f

		private const val BEND_FULL_SCALE_RADIANS = 1.15f
		private const val BEND_COMPRESSION_FRACTION = 0.65f

		private const val RELATIVE_ACCEL_BASELINE_HZ = 0.5f
		private const val RELATIVE_ACCEL_FULL_SCALE = 1.25f
		private const val RELATIVE_ACCEL_DEADZONE = 0.025f
		private const val MAX_RELATIVE_ACCELERATION = 32f
		private const val RELATIVE_ACCEL_FILTER_HZ_SMOOTH = 8f
		private const val RELATIVE_ACCEL_FILTER_HZ_REACTIVE = 22f
		private const val RELATIVE_JERK_FILTER_HZ_SMOOTH = 5f
		private const val RELATIVE_JERK_FILTER_HZ_REACTIVE = 16f
		private const val DERIVATIVE_TAU_SMOOTH = 0.18f
		private const val DERIVATIVE_TAU_REACTIVE = 0.07f
		private const val MAX_NORMALIZED_JERK = 60f
		private const val SENSOR_ACCEL_WEIGHT = 0.35f
		private const val SENSOR_JERK_WEIGHT = 0.65f

		private const val STRAIN_FOLLOW_HZ_SMOOTH = 3.5f
		private const val STRAIN_FOLLOW_HZ_REACTIVE = 12f
		private const val LENGTH_PROJECTION_EPSILON = 1e-6f
	}
}
