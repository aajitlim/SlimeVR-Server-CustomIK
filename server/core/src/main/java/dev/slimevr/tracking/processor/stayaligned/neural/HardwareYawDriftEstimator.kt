package dev.slimevr.tracking.processor.stayaligned.neural

import io.github.axisangles.ktmath.EulerOrder
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import java.util.ArrayDeque
import kotlin.math.PI
import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.exp
import kotlin.math.sin
import kotlin.math.sqrt

/**
 * A genuinely single-sensor estimator. It never reads any other tracker's pose,
 * skeleton position or Stay Aligned state. Every physical device owns its own
 * weights, hidden state, training samples and reset-supervision history.
 *
 * Temperature is explicitly optional; zero-filled/masked channels are never
 * treated as a fabricated temperature measurement.
 */
class HardwareYawDriftEstimator {
    private val network = NeuralYawGruModel(
        inputSize = FEATURE_COUNT,
        hiddenSize = 12,
    )
    private var hidden = network.newHidden()
    private val history = ArrayDeque<NeuralYawSequenceSample>()
    private var bias = 0f
    private var previousYaw: Float? = null
    private var previousAccelMagnitude: Float? = null
    private var previousTemperature: Float? = null
    private var previousTemperatureUpdateNanos: Long = 0L
    private var temperatureBaseline: Float? = null
    private var elapsedSeconds = 0f

    var temperatureCelsius: Float? = null
        private set
    var temperatureRateCelsiusPerSecond: Float = 0f
        private set
    var predictedRateDegPerSec: Float = 0f
        private set
    var resetLabels: Int = 0
        private set
    var lastLoss: Float = 0f
        private set
    var lastPredictionErrorDeg: Float = 0f
        private set
    var lastPredictionDeg: Float = 0f
        private set
    var samplesSeen: Long = 0
        private set

    val historySize: Int get() = history.size

    val confidence: Float
        get() = (1.0 - exp(-resetLabels.toDouble() / 3.0))
            .toFloat().coerceIn(0f, 0.95f)

    fun sample(
        rawRotation: Quaternion,
        acceleration: Vector3?,
        temperature: Float?,
        dtSeconds: Float,
        historyLimit: Int,
        temperatureUpdateNanos: Long = 0L,
    ): Float {
        val dt = dtSeconds.coerceIn(1f / 120f, 0.25f)
        val features = featuresFor(
            rawRotation, acceleration, temperature, temperatureUpdateNanos, dt,
        )
        val prediction = network.predict(features, hidden, bias)
        hidden = prediction.hidden
        predictedRateDegPerSec = prediction.rateDegPerSec
        history.addLast(NeuralYawSequenceSample(features, dt))
        while (history.size > historyLimit.coerceIn(100, 5000)) {
            history.removeFirst()
        }
        samplesSeen++
        return predictedRateDegPerSec
    }

    /**
     * A yaw reset supervises the integral of local-only drift predictions.
     * This is a separate model from the multi-limb GRU; both use the same
     * reset event but neither one's gradients are written to the other.
     */
    fun learnFromReset(targetCorrectionDeg: Float, learningRate: Float) {
        if (history.isEmpty() || !targetCorrectionDeg.isFinite()) return
        val result = network.trainSequence(
            samples = history.toList(),
            targetCorrectionDeg = targetCorrectionDeg,
            learningRate = learningRate,
            deviceBias = bias,
        )
        bias = result.deviceBias
        resetLabels++
        lastLoss = result.loss
        lastPredictionDeg = result.predictedCorrectionDeg
        lastPredictionErrorDeg = abs(result.predictedCorrectionDeg - targetCorrectionDeg)
    }

    /** Clear temporal evidence without destroying the hardware's learned weights. */
    fun resetTemporal() {
        hidden = network.newHidden()
        history.clear()
        previousYaw = null
        previousAccelMagnitude = null
        previousTemperature = null
        previousTemperatureUpdateNanos = 0L
        temperatureBaseline = null
        temperatureCelsius = null
        temperatureRateCelsiusPerSecond = 0f
        elapsedSeconds = 0f
        predictedRateDegPerSec = 0f
    }

    private fun featuresFor(
        rotation: Quaternion,
        acceleration: Vector3?,
        temperature: Float?,
        temperatureUpdateNanos: Long,
        dt: Float,
    ): FloatArray {
        val f = FloatArray(FEATURE_COUNT)
        val yaw = rotation.toEulerAngles(EulerOrder.YZX).y
        f[0] = sin(yaw.toDouble()).toFloat()
        f[1] = cos(yaw.toDouble()).toFloat()
        f[2] = rotation.x
        f[3] = rotation.z

        val yawRate =
            previousYaw?.let {
                val diff = ((yaw - it + PI.toFloat()) % (2f * PI.toFloat())
                    + 2f * PI.toFloat()) % (2f * PI.toFloat()) - PI.toFloat()
                diff / dt
            } ?: 0f
        previousYaw = yaw
        f[4] = (yawRate / 6f).coerceIn(-1f, 1f)
        f[5] = (abs(yawRate) / 6f).coerceIn(0f, 1f)

        val a = acceleration?.takeIf {
            it.x.isFinite() && it.y.isFinite() && it.z.isFinite()
        }
        if (a != null) {
            f[6] = (a.x / 4f).coerceIn(-1f, 1f)
            f[7] = (a.y / 4f).coerceIn(-1f, 1f)
            f[8] = (a.z / 4f).coerceIn(-1f, 1f)
            val magnitude = sqrt(a.x * a.x + a.y * a.y + a.z * a.z)
            f[9] = (magnitude / 4f).coerceIn(0f, 1f)
            f[10] = previousAccelMagnitude?.let {
                ((magnitude - it) / dt / 40f).coerceIn(-1f, 1f)
            } ?: 0f
            previousAccelMagnitude = magnitude
        } else {
            previousAccelMagnitude = null
        }

        // SlimeVR's UDP temperature packet is in degrees Celsius. Missing or
        // stale measurements are masked, not replaced by an assumed 25 C.
        val validTemp = temperature?.takeIf { it.isFinite() && it in -30f..105f }
        temperatureCelsius = validTemp
        if (validTemp != null) {
            if (temperatureBaseline == null) temperatureBaseline = validTemp
            // Temperature packets arrive far less often than pose samples.
            // Differentiate between actual packet timestamps, not neural dt.
            val newPacket = temperatureUpdateNanos > 0L &&
                temperatureUpdateNanos != previousTemperatureUpdateNanos
            if (newPacket) {
                val packetDt = if (previousTemperatureUpdateNanos > 0L) {
                    ((temperatureUpdateNanos - previousTemperatureUpdateNanos).toDouble() /
                        1_000_000_000.0).toFloat()
                } else 0f
                temperatureRateCelsiusPerSecond =
                    if (packetDt > 0.05f && previousTemperature != null) {
                        ((validTemp - previousTemperature!!) / packetDt)
                            .coerceIn(-20f, 20f)
                    } else 0f
                previousTemperature = validTemp
                previousTemperatureUpdateNanos = temperatureUpdateNanos
            }
            val rate = temperatureRateCelsiusPerSecond
            f[11] = ((validTemp - 25f) / 40f).coerceIn(-1f, 1f)
            f[12] = ((validTemp - (temperatureBaseline ?: validTemp)) / 20f)
                .coerceIn(-1f, 1f)
            f[13] = (rate / 5f).coerceIn(-1f, 1f)
            f[14] = 1f
        } else {
            previousTemperature = null
            previousTemperatureUpdateNanos = 0L
            temperatureRateCelsiusPerSecond = 0f
        }
        elapsedSeconds += dt
        f[15] = (elapsedSeconds / 600f).coerceIn(0f, 1f)
        return f
    }

    companion object {
        const val FEATURE_COUNT = 16
    }
}
