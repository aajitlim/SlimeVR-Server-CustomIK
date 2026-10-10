package dev.slimevr.unit

import dev.slimevr.tracking.processor.stayaligned.neural.HardwareYawDriftEstimator
import dev.slimevr.tracking.processor.stayaligned.neural.HardwareYawFusion
import io.github.axisangles.ktmath.Quaternion
import io.github.axisangles.ktmath.Vector3
import kotlin.math.abs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class HardwareYawDriftEstimatorTests {
    private val pose = Quaternion.IDENTITY

    @Test
    fun missingTemperatureDoesNotInventMeasurement() {
        val device = HardwareYawDriftEstimator()
        device.sample(
            rawRotation = pose,
            acceleration = Vector3(0f, 1f, 0f),
            temperature = null,
            dtSeconds = 0.05f,
            historyLimit = 1000,
        )
        val f = device.latestFeatureVector()!!
        assertEquals(HardwareYawDriftEstimator.FEATURE_COUNT, f.size)
        for (i in 11..14) assertEquals(0f, f[i])
        assertEquals(null, device.temperatureCelsius)
        assertTrue(device.predictedRateDegPerSec.isFinite())
    }

    @Test
    fun temperatureSlopeUsesPacketTimeNotPoseTime() {
        val device = HardwareYawDriftEstimator()
        device.sample(pose, null, 25f, 0.05f, 1000, 1_000_000_000L)
        device.sample(pose, null, 26f, 0.05f, 1000, 6_000_000_000L)

        assertEquals(26f, device.temperatureCelsius)
        assertTrue(abs(device.temperatureRateCelsiusPerSecond - 0.2f) < 1e-4f)
        val f = device.latestFeatureVector()!!
        assertEquals(1f, f[14])
        assertTrue(abs(f[13] - 0.04f) < 1e-4f)
    }

    @Test
    fun sameTemperaturePacketDoesNotGenerateNewDerivative() {
        val device = HardwareYawDriftEstimator()
        device.sample(pose, null, 25f, 0.05f, 1000, 1_000_000_000L)
        device.sample(pose, null, 26f, 0.05f, 1000, 6_000_000_000L)
        val previousRate = device.temperatureRateCelsiusPerSecond

        repeat(12) {
            device.sample(pose, null, 26f, 0.05f, 1000, 6_000_000_000L)
        }
        assertEquals(previousRate, device.temperatureRateCelsiusPerSecond)
    }

    @Test
    fun localModelsLearnDifferentDeviceResetSigns() {
        val deviceA = HardwareYawDriftEstimator()
        val deviceB = HardwareYawDriftEstimator()
        repeat(60) {
            deviceA.sample(pose, Vector3(0f, 1f, 0f), 28f, 0.05f, 100, 1_000_000_000L)
            deviceB.sample(pose, Vector3(0f, 1f, 0f), 28f, 0.05f, 100, 1_000_000_000L)
        }
        repeat(35) {
            deviceA.learnFromReset(+2f, 0.001f)
            deviceB.learnFromReset(-2f, 0.001f)
        }

        deviceA.resetTemporal()
        deviceB.resetTemporal()
        val outputA = deviceA.sample(pose, null, 28f, 0.05f, 100)
        val outputB = deviceB.sample(pose, null, 28f, 0.05f, 100)
        assertTrue(outputA > outputB)
        assertTrue(outputA > 0f)
        assertTrue(outputB < 0f)
        assertEquals(35, deviceA.resetLabels)
        assertEquals(35, deviceB.resetLabels)
    }

    @Test
    fun convexFusionNeverSumsIndependentPredictions() {
        assertEquals(2f, HardwareYawFusion.blend(2f, 2f, 1f, 1f))
        assertEquals(1f, HardwareYawFusion.blend(2f, 0f, 0.5f, 1f))
        assertEquals(2f, HardwareYawFusion.blend(2f, -2f, 1f, 0f))
        assertEquals(0f, HardwareYawFusion.blend(2f, -2f, 1f, 0.5f))
    }
}
