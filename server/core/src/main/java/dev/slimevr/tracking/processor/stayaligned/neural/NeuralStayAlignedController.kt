package dev.slimevr.tracking.processor.stayaligned.neural

import dev.slimevr.VRServer
import dev.slimevr.config.NeuralStayAlignedConfig
import dev.slimevr.math.Angle
import dev.slimevr.tracking.processor.stayaligned.adjust.CenterYaw
import dev.slimevr.tracking.processor.stayaligned.trackers.RestDetector
import dev.slimevr.tracking.processor.stayaligned.trackers.TrackerSkeleton
import dev.slimevr.tracking.trackers.Tracker
import dev.slimevr.tracking.trackers.TrackerPosition
import dev.slimevr.tracking.trackers.udp.MagnetometerStatus
import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import java.util.ArrayDeque
import java.util.concurrent.ConcurrentHashMap
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin

data class NeuralStayAlignedDeviceStatus(
	val deviceKey: String,
	val trackerName: String,
	val bodyPosition: String,
	val samplesSeen: Long,
	val historySize: Int,
	val supervisionEvents: Int,
	val predictedRateDegPerSec: Float,
	val appliedRateDegPerSec: Float,
	val confidence: Float,
	val lastResetCorrectionDeg: Float,
	val lastTrainingTargetDeg: Float,
	val lastLoss: Float,
)

data class NeuralStayAlignedStatus(
	val deviceCount: Int,
	val totalSamples: Long,
	val totalSupervisionEvents: Long,
	val devices: List<NeuralStayAlignedDeviceStatus>,
)

/**
 * Runtime coordinator for the tiny reset-supervised GRU.
 *
 * Model weights are shared so general cross-skeleton relationships can transfer
 * between sensors. Recurrent hidden state, compact history, and output bias are
 * isolated per physical device/sensor ID.
 */
object NeuralStayAlignedController {
	private data class DeviceState(
		val key: String,
		var trackerName: String,
		var trackerPosition: TrackerPosition?,
		var hidden: FloatArray = model.newHidden(),
		val history: ArrayDeque<NeuralYawSequenceSample> = ArrayDeque(),
		var historyDurationSeconds: Float = 0f,
		var lastSampleNanos: Long = 0L,
		var lastResetNanos: Long = 0L,
		var previousYawRad: Float? = null,
		var deviceBias: Float = 0f,
		var samplesSeen: Long = 0L,
		var supervisionEvents: Int = 0,
		var lastPredictedRateDegPerSec: Float = 0f,
		var lastAppliedRateDegPerSec: Float = 0f,
		var lastConfidence: Float = 0f,
		var lastResetCorrectionDeg: Float = 0f,
		var lastTrainingTargetDeg: Float = 0f,
		var lastLoss: Float = 0f,
	)

	private val model = NeuralYawGruModel()
	private val states = ConcurrentHashMap<String, DeviceState>()

	@Volatile
	private var totalSamples: Long = 0L

	@Volatile
	private var totalSupervisionEvents: Long = 0L

	fun observeAndCorrect(
		tracker: Tracker,
		trackers: TrackerSkeleton,
		config: NeuralStayAlignedConfig,
		nowNanos: Long = System.nanoTime(),
	) {
		if (!config.enabled || !tracker.isImu()) return

		val key = deviceKey(tracker)
		val state = states.computeIfAbsent(key) {
			DeviceState(
				key = key,
				trackerName = tracker.name,
				trackerPosition = tracker.trackerPosition,
			)
		}

		if (state.trackerPosition != tracker.trackerPosition) {
			// The same physical sensor was reassigned to another body location.
			// Keep the learned per-device bias, but discard temporal state whose
			// cross-skeleton meaning belonged to the previous role.
			state.trackerPosition = tracker.trackerPosition
			state.trackerName = tracker.name
			clearTemporalState(state, nowNanos)
		}

		val sampleRate = config.sampleRateHz.coerceIn(5f, 60f)
		val minimumIntervalNanos = (1_000_000_000.0 / sampleRate).toLong()
		if (state.lastSampleNanos != 0L &&
			nowNanos - state.lastSampleNanos < minimumIntervalNanos
		) {
			return
		}

		val dt =
			if (state.lastSampleNanos == 0L) {
				1f / sampleRate
			} else {
				((nowNanos - state.lastSampleNanos).toDouble() / 1_000_000_000.0)
					.toFloat()
					.coerceIn(1f / 120f, 0.25f)
			}

		val features = buildFeatures(tracker, trackers, state, dt)
		val prediction = model.predict(features, state.hidden, state.deviceBias)
		state.hidden = prediction.hidden
		state.lastPredictedRateDegPerSec = prediction.rateDegPerSec
		state.samplesSeen++
		totalSamples++

		val historyLimit = config.historySamples.coerceIn(100, 5000)
		state.history.addLast(
			NeuralYawSequenceSample(
				features = features,
				dtSeconds = dt,
			),
		)
		state.historyDurationSeconds += dt
		while (state.history.size > historyLimit) {
			state.historyDurationSeconds -= state.history.removeFirst().dtSeconds
		}

		val confidence = confidenceFor(state.supervisionEvents)
		state.lastConfidence = confidence

		var appliedRate = 0f
		if (config.applyCorrections &&
			confidence >= config.confidenceThreshold.coerceIn(0f, 1f) &&
			tracker.magStatus != MagnetometerStatus.ENABLED
		) {
			val maxRate = config.maxCorrectionRateDegPerSec.coerceIn(0f, 3f)
			val requestedRate =
				(prediction.rateDegPerSec *
					config.correctionStrength.coerceIn(0f, 1f))
					.coerceIn(-maxRate, maxRate)

			val angularSpeedDeg =
				abs(features[FEATURE_ANGULAR_SPEED] * ANGULAR_SPEED_NORMALIZER_DEG)
			val motionFraction =
				((angularSpeedDeg - MOTION_GATE_START_DEG) /
					(MOTION_GATE_STOP_DEG - MOTION_GATE_START_DEG))
					.coerceIn(0f, 1f)
			val motionGate =
				1f -
					config.motionProtection.coerceIn(0f, 1f) *
						motionFraction

			appliedRate = requestedRate * motionGate
			tracker.stayAligned.yawCorrection +=
				Angle.ofDeg(appliedRate * dt)
		}

		state.lastAppliedRateDegPerSec = appliedRate
		state.lastSampleNanos = nowNanos
		if (state.lastResetNanos == 0L) state.lastResetNanos = nowNanos
	}

	/**
	 * Called immediately before a normal yaw reset changes the tracker's yaw fix.
	 * The current adjusted rotation and reference define a signed supervision
	 * target without needing external mocap ground truth.
	 */
	fun onYawReset(
		tracker: Tracker,
		currentAdjustedRotation: Quaternion,
		reference: Quaternion,
		nowNanos: Long = System.nanoTime(),
	) {
		if (!VRServer.instanceInitialized) return

		val config = VRServer.instance.configManager.vrConfig.neuralStayAligned
		val key = deviceKey(tracker)
		val state = states[key] ?: return

		val correctionDeg =
			Angle.ofRad(
				yawRad(reference) -
					yawRad(currentAdjustedRotation),
			).toDeg()

		state.lastResetCorrectionDeg = correctionDeg

		val intervalSeconds =
			if (state.lastResetNanos == 0L) {
				state.historyDurationSeconds
			} else {
				((nowNanos - state.lastResetNanos).toDouble() / 1_000_000_000.0)
					.toFloat()
			}

		val shouldTrain =
			config.enabled &&
				config.learnFromYawResets &&
				state.history.isNotEmpty() &&
				intervalSeconds >=
				config.minimumResetIntervalSeconds.coerceIn(1f, 600f) &&
				abs(correctionDeg) <=
				config.maxResetSupervisionDeg.coerceIn(1f, 180f)

		if (shouldTrain) {
			// If the reset interval is longer than the retained compact history,
			// supervise only the approximate fraction represented by this window.
			// This keeps memory bounded without pretending that discarded history
			// is still available for BPTT.
			val representedFraction =
				if (intervalSeconds <= 1e-4f) {
					1f
				} else {
					(state.historyDurationSeconds / intervalSeconds)
						.coerceIn(0.05f, 1f)
				}
			val targetForWindow = correctionDeg * representedFraction

			val result = model.trainSequence(
				samples = state.history.toList(),
				targetCorrectionDeg = targetForWindow,
				learningRate = config.learningRate,
				deviceBias = state.deviceBias,
			)

			state.deviceBias = result.deviceBias
			state.supervisionEvents++
			totalSupervisionEvents++
			state.lastLoss = result.loss
			state.lastTrainingTargetDeg = targetForWindow
			state.lastConfidence = confidenceFor(state.supervisionEvents)
		}

		clearTemporalState(state, nowNanos)
	}

	/**
	 * Full/mounting resets change the reference problem rather than providing a
	 * clean yaw-drift label, so their temporal histories are discarded.
	 */
	fun discardHistory(
		tracker: Tracker,
		nowNanos: Long = System.nanoTime(),
	) {
		states[deviceKey(tracker)]?.let {
			clearTemporalState(it, nowNanos)
		}
	}

	fun clearLearning() {
		model.resetWeights()
		states.clear()
		totalSamples = 0L
		totalSupervisionEvents = 0L
	}

	fun status(): NeuralStayAlignedStatus {
		val deviceStatuses =
			states.values
				.map {
					NeuralStayAlignedDeviceStatus(
						deviceKey = it.key,
						trackerName = it.trackerName,
						bodyPosition = it.trackerPosition?.name ?: "UNASSIGNED",
						samplesSeen = it.samplesSeen,
						historySize = it.history.size,
						supervisionEvents = it.supervisionEvents,
						predictedRateDegPerSec = it.lastPredictedRateDegPerSec,
						appliedRateDegPerSec = it.lastAppliedRateDegPerSec,
						confidence = it.lastConfidence,
						lastResetCorrectionDeg = it.lastResetCorrectionDeg,
						lastTrainingTargetDeg = it.lastTrainingTargetDeg,
						lastLoss = it.lastLoss,
					)
				}
				.sortedBy { it.bodyPosition }

		return NeuralStayAlignedStatus(
			deviceCount = deviceStatuses.size,
			totalSamples = totalSamples,
			totalSupervisionEvents = totalSupervisionEvents,
			devices = deviceStatuses,
		)
	}

	private fun buildFeatures(
		tracker: Tracker,
		trackers: TrackerSkeleton,
		state: DeviceState,
		dt: Float,
	): FloatArray {
		val features = FloatArray(NeuralYawGruModel.FEATURE_COUNT)
		val rotation = tracker.getAdjustedRotationForceStayAligned()

		features[0] = rotation.x
		features[1] = rotation.y
		features[2] = rotation.z
		features[3] = rotation.w

		if (tracker.hasAcceleration) {
			val acceleration = tracker.getAcceleration()
			features[4] = (acceleration.x / 4f).coerceIn(-1f, 1f)
			features[5] = (acceleration.y / 4f).coerceIn(-1f, 1f)
			features[6] = (acceleration.z / 4f).coerceIn(-1f, 1f)
		}

		val ownYaw = yawRad(rotation)
		features[7] = sin(ownYaw)
		features[8] = cos(ownYaw)

		putRelativeYaw(
			features,
			9,
			ownYaw,
			CenterYaw.ofSkeleton(trackers)?.toRad(),
		)
		putRelativeYaw(features, 11, ownYaw, trackers.head?.let(::trackerYawRad))
		putRelativeYaw(
			features,
			13,
			ownYaw,
			trackers.upperBody.lastOrNull()?.let(::trackerYawRad),
		)
		putRelativeYaw(features, 15, ownYaw, trackers.leftUpperLeg?.let(::trackerYawRad))
		putRelativeYaw(features, 17, ownYaw, trackers.rightUpperLeg?.let(::trackerYawRad))
		putRelativeYaw(features, 19, ownYaw, trackers.leftLowerLeg?.let(::trackerYawRad))
		putRelativeYaw(features, 21, ownYaw, trackers.rightLowerLeg?.let(::trackerYawRad))
		putRelativeYaw(features, 23, ownYaw, trackers.leftFoot?.let(::trackerYawRad))
		putRelativeYaw(features, 25, ownYaw, trackers.rightFoot?.let(::trackerYawRad))

		val previousYaw = state.previousYawRad
		val angularSpeedDeg =
			if (previousYaw == null || dt <= 0f) {
				0f
			} else {
				abs(
					Angle.ofRad(ownYaw - previousYaw).toDeg() / dt,
				)
			}
		state.previousYawRad = ownYaw

		features[FEATURE_ANGULAR_SPEED] =
			(angularSpeedDeg / ANGULAR_SPEED_NORMALIZER_DEG)
				.coerceIn(0f, 1f)
		features[28] =
			(tracker.stayAligned.yawCorrection.toDeg() / 45f)
				.coerceIn(-1f, 1f)
		features[29] =
			when (tracker.stayAligned.restDetector.state) {
				RestDetector.State.MOVING -> 0f
				RestDetector.State.RECENTLY_AT_REST -> 0.5f
				RestDetector.State.AT_REST -> 1f
			}
		features[30] = (tracker.packetLoss ?: 0f).coerceIn(0f, 1f)

		val position = tracker.trackerPosition
		features[31] =
			if (position == null) {
				0f
			} else {
				(position.ordinal.toFloat() + 1f) /
					TrackerPosition.values().size.toFloat()
			}

		return features
	}

	private fun trackerYawRad(tracker: Tracker): Float =
		yawRad(tracker.getAdjustedRotationForceStayAligned())

	private fun putRelativeYaw(
		features: FloatArray,
		index: Int,
		ownYaw: Float,
		contextYaw: Float?,
	) {
		if (contextYaw == null) {
			features[index] = 0f
			features[index + 1] = 0f
			return
		}

		val difference = Angle.ofRad(ownYaw - contextYaw).toRad()
		features[index] = sin(difference)
		features[index + 1] = cos(difference)
	}

	private fun yawRad(rotation: Quaternion): Float =
		rotation.toEulerAngles(EulerOrder.YZX).y

	private fun confidenceFor(supervisionEvents: Int): Float =
		(1f - exp(-supervisionEvents.toFloat() / 3f))
			.coerceIn(0f, 0.95f)

	private fun clearTemporalState(
		state: DeviceState,
		nowNanos: Long,
	) {
		state.hidden = model.newHidden()
		state.history.clear()
		state.historyDurationSeconds = 0f
		state.previousYawRad = null
		state.lastSampleNanos = 0L
		state.lastResetNanos = nowNanos
		state.lastAppliedRateDegPerSec = 0f
	}

	private fun deviceKey(tracker: Tracker): String {
		val hardware =
			tracker.device?.hardwareIdentifier
				?: tracker.name
		return hardware + ":" + tracker.trackerNum
	}

	private const val FEATURE_ANGULAR_SPEED = 27
	private const val ANGULAR_SPEED_NORMALIZER_DEG = 360f
	private const val MOTION_GATE_START_DEG = 45f
	private const val MOTION_GATE_STOP_DEG = 180f
}
