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
	val historyRawEquivalentSamples: Long,
	val historyCompressedCapsules: Int,
	val historyReplayTokens: Int,
	val historySeconds: Float,
	val supervisionEvents: Int,
	val predictedRateDegPerSec: Float,
	val appliedRateDegPerSec: Float,
	val confidence: Float,
	val lastResetCorrectionDeg: Float,
	val lastTrainingTargetDeg: Float,
	val lastLoss: Float,
	val hardwareSamplesSeen: Long,
	val hardwareHistorySize: Int,
	val hardwareRawEquivalentSamples: Long,
	val hardwareCompressedCapsules: Int,
	val hardwareReplayTokens: Int,
	val hardwareHistorySeconds: Float,
	val hardwareResetLabels: Int,
	val hardwarePredictedRateDegPerSec: Float,
	val hardwareLastLoss: Float,
	val hardwareLastErrorDeg: Float,
	val hardwareConfidence: Float,
	val hardwareTemperatureCelsius: Float?,
	val hardwareTemperatureRateCelsiusPerSec: Float,
	val hardwareTemperatureFresh: Boolean,
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
		var hidden: FloatArray,
		val hardware: HardwareYawDriftEstimator = HardwareYawDriftEstimator(),
		var lastHardwareTemperatureFresh: Boolean = false,
		val history: MultiScaleYawHistory =
			MultiScaleYawHistory(NeuralYawGruModel.FEATURE_COUNT),
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
				hidden = model.newHidden(),
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

		// FIRST STAGE: own-sensor rotation / acceleration / fresh temperature.
		// No anatomical neighbors are passed into the hardware estimator.
		var hardwareRate = 0f
		if (config.hardwareLearningEnabled) {
			val tempStamp = tracker.temperatureLastUpdatedNanos
			val maxAge = config.hardwareTemperatureMaxAgeSeconds
				.coerceIn(1f, 600f)
			val temperatureIsFresh = tempStamp > 0L &&
				nowNanos >= tempStamp &&
				(nowNanos - tempStamp).toDouble() / 1_000_000_000.0 <= maxAge
			state.lastHardwareTemperatureFresh = temperatureIsFresh &&
				tracker.temperature?.isFinite() == true
			val accel = if (tracker.hasAcceleration) tracker.getAcceleration() else null
			hardwareRate = state.hardware.sample(
				rawRotation = tracker.getRawRotation(),
				acceleration = accel,
				temperature = if (state.lastHardwareTemperatureFresh) tracker.temperature else null,
				dtSeconds = dt,
				historyLimit = config.historySamples,
				temperatureUpdateNanos = if (state.lastHardwareTemperatureFresh) tempStamp else 0L,
				chunkSize = config.historyChunkSize,
				recentSamples = config.recentDetailedSamples,
			)
		} else {
			state.lastHardwareTemperatureFresh = false
			state.hardware.resetTemporal()
		}

		// SECOND STAGE: preserve the already-trained cross-skeleton network.
		val features = buildFeatures(tracker, trackers, state, dt)
		val prediction = model.predict(features, state.hidden, state.deviceBias)
		state.hidden = prediction.hidden
		state.lastPredictedRateDegPerSec = prediction.rateDegPerSec
		state.samplesSeen++
		totalSamples++

		state.history.add(
			sample = NeuralYawSequenceSample(features, dt),
			capacity = config.historySamples,
			chunkSize = config.historyChunkSize,
			recentSamples = config.recentDetailedSamples,
		)

		val confidence = confidenceFor(state.supervisionEvents)
		state.lastConfidence = confidence

		var appliedRate = 0f
		if (config.applyCorrections &&
			confidence >= config.confidenceThreshold.coerceIn(0f, 1f) &&
			tracker.magStatus != MagnetometerStatus.ENABLED
		) {
			val maxRate = config.maxCorrectionRateDegPerSec.coerceIn(0f, 3f)
			// Optional convex combination, NEVER sum two independent predictions.
			// The proven skeleton GRU remains authoritative by default.
			val blend =
				if (config.hardwareLearningEnabled && config.hardwareFusionEnabled) {
					config.hardwareBlend.coerceIn(0f, 1f) *
						state.hardware.confidence
				} else 0f
			val fusedRate = HardwareYawFusion.blend(
				skeletonRate = prediction.rateDegPerSec,
				hardwareRate = hardwareRate,
				maximumBlend = blend,
				hardwareMaturity = 1f,
			)
			val requestedRate =
				(fusedRate * config.correctionStrength.coerceIn(0f, 1f))
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
				state.history.durationSeconds
			} else {
				((nowNanos - state.lastResetNanos).toDouble() / 1_000_000_000.0)
					.toFloat()
			}

		val shouldTrain =
			config.enabled &&
				config.learnFromYawResets &&
				!state.history.isEmpty &&
				intervalSeconds >=
				config.minimumResetIntervalSeconds.coerceIn(1f, 600f) &&
				// Missing tracking time cannot be reconstructed by capsules.
				// Only train when observed sample time covers most of the
				// actual reset interval.
				state.history.durationSeconds >= intervalSeconds * 0.80f &&
				abs(correctionDeg) <=
				config.maxResetSupervisionDeg.coerceIn(1f, 180f)

		if (shouldTrain) {
			// Compressed older capsules cover the COMPLETE observed interval.
			// No guessed "retainedFraction" scaling of the reset label.
			val targetForWindow = correctionDeg

			// Both independent models use the same trusted reset angle, but
			// train separate weights. No reset correction is double-added.
			val hardwareSamples = state.hardware.historicalRawEquivalentSamples
			val skeletonSamples = state.history.rawEquivalentSamples
			if (config.hardwareLearningEnabled &&
				hardwareSamples > 0L &&
				hardwareSamples >= skeletonSamples - 1L
			) {
				// These branches are observed on the same scheduler pass.
				// Exact interval sample coverage (allowing one startup frame)
				// avoids assigning a full reset label to hardware training
				// that was enabled partway through the interval.
				state.hardware.learnFromReset(
					targetCorrectionDeg = correctionDeg,
					learningRate = config.learningRate,
					chunkSize = config.historyChunkSize,
				)
			}

			val result = model.trainSequence(
				samples = state.history.trainingSequence(),
				targetCorrectionDeg = targetForWindow,
				learningRate = config.learningRate,
				deviceBias = state.deviceBias,
				chunkSize = config.historyChunkSize,
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
						historySize = it.history.detailedSamples,
						historyRawEquivalentSamples = it.history.rawEquivalentSamples,
						historyCompressedCapsules = it.history.archivedCapsules,
						historyReplayTokens = it.history.replayTokens,
						historySeconds = it.history.durationSeconds,
						supervisionEvents = it.supervisionEvents,
						predictedRateDegPerSec = it.lastPredictedRateDegPerSec,
						appliedRateDegPerSec = it.lastAppliedRateDegPerSec,
						confidence = it.lastConfidence,
						lastResetCorrectionDeg = it.lastResetCorrectionDeg,
						lastTrainingTargetDeg = it.lastTrainingTargetDeg,
						lastLoss = it.lastLoss,
						hardwareSamplesSeen = it.hardware.samplesSeen,
						hardwareHistorySize = it.hardware.historySize,
						hardwareRawEquivalentSamples =
							it.hardware.historicalRawEquivalentSamples,
						hardwareCompressedCapsules = it.hardware.compressedHistoryCapsules,
						hardwareReplayTokens = it.hardware.replayTokens,
						hardwareHistorySeconds = it.hardware.historyDurationSeconds,
						hardwareResetLabels = it.hardware.resetLabels,
						hardwarePredictedRateDegPerSec = it.hardware.predictedRateDegPerSec,
						hardwareLastLoss = it.hardware.lastLoss,
						hardwareLastErrorDeg = it.hardware.lastPredictionErrorDeg,
						hardwareConfidence = it.hardware.confidence,
						hardwareTemperatureCelsius = it.hardware.temperatureCelsius,
						hardwareTemperatureRateCelsiusPerSec =
							it.hardware.temperatureRateCelsiusPerSecond,
						hardwareTemperatureFresh = it.lastHardwareTemperatureFresh,
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
		features[7] = sin(ownYaw.toDouble()).toFloat()
		features[8] = cos(ownYaw.toDouble()).toFloat()

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
		features[index] = sin(difference.toDouble()).toFloat()
		features[index + 1] = cos(difference.toDouble()).toFloat()
	}

	private fun yawRad(rotation: Quaternion): Float =
		rotation.toEulerAngles(EulerOrder.YZX).y

	private fun confidenceFor(supervisionEvents: Int): Float =
		(1.0 - exp(-supervisionEvents.toDouble() / 3.0))
			.toFloat()
			.coerceIn(0f, 0.95f)

	private fun clearTemporalState(
		state: DeviceState,
		nowNanos: Long,
	) {
		state.hidden = model.newHidden()
		state.hardware.resetTemporal()
		state.lastHardwareTemperatureFresh = false
		state.history.clear()
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
