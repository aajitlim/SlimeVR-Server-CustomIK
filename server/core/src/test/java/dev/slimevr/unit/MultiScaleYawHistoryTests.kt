package dev.slimevr.unit

import dev.slimevr.tracking.processor.stayaligned.neural.MultiScaleYawHistory
import dev.slimevr.tracking.processor.stayaligned.neural.NeuralYawGruModel
import dev.slimevr.tracking.processor.stayaligned.neural.NeuralYawSequenceSample
import kotlin.math.abs
import org.junit.jupiter.api.Test
import kotlin.test.assertEquals
import kotlin.test.assertTrue

class MultiScaleYawHistoryTests {
    @Test
    fun fiftyThousandSamplesBecomeBoundedReplayAndPreserveTime() {
        val history = MultiScaleYawHistory(8)
        repeat(50_000) { i ->
            val f = FloatArray(8) { it.toFloat() / 8f }
            f[0] = if (i < 25_000) -1f else 1f
            history.add(
                NeuralYawSequenceSample(f, 0.05f),
                capacity = 50_000,
                chunkSize = 64,
                recentSamples = 1024,
            )
        }
        val tokens = history.trainingSequence()
        assertEquals(50_000L, history.rawEquivalentSamples)
        assertEquals(1024, history.detailedSamples)
        assertTrue(history.archivedCapsules > 0)
        assertTrue(tokens.size < 4_000)
        assertTrue(abs(history.durationSeconds - 2_500f) < 2f)
        assertTrue(abs(tokens.sumOf { it.dtSeconds.toDouble() } - 2500.0) < 2.0)
        assertTrue(tokens.first().features[0] < 0f)
        assertTrue(tokens.last().features[0] > 0f)
    }

    @Test
    fun exceedingResolutionBudgetNeverDropsOlderTimeOrDirection() {
        val history = MultiScaleYawHistory(9)
        repeat(80_000) { i ->
            val f = FloatArray(9)
            f[0] = if (i < 40_000) -1f else 1f
            history.add(
                NeuralYawSequenceSample(f, 0.05f),
                capacity = 20_000,
                chunkSize = 32,
                recentSamples = 512,
            )
        }
        val tokens = history.trainingSequence()
        assertEquals(80_000L, history.rawEquivalentSamples)
        assertTrue(tokens.size < 3_000)
        assertTrue(abs(tokens.sumOf { it.dtSeconds.toDouble() } - 4_000.0) < 3.0)
        assertTrue(tokens.first().features[0] < 0f)
        assertTrue(tokens.last().features[0] > 0f)
        assertTrue(history.archivedCapsules > 0)
    }

    @Test
    fun clearingAfterYawResetDiscardsOldTemporalLabels() {
        val history = MultiScaleYawHistory(8)
        repeat(500) {
            history.add(NeuralYawSequenceSample(FloatArray(8), 0.05f), 1000)
        }
        history.clear()
        assertEquals(0L, history.rawEquivalentSamples)
        assertEquals(0, history.replayTokens)
        assertTrue(history.isEmpty)
    }

    @Test
    fun checkpointedBpttSupportsLongCompressedResetTraining() {
        val history = MultiScaleYawHistory(16)
        repeat(50_000) { i ->
            val f = FloatArray(16)
            f[0] = if ((i / 200) % 2 == 0) 0.5f else -0.5f
            history.add(NeuralYawSequenceSample(f, 0.05f), 50_000, 64, 512)
        }
        val samples = history.trainingSequence()
        assertTrue(samples.size < 3500)
        val model = NeuralYawGruModel(inputSize = 16, hiddenSize = 12, seed = 22L)
        val result = model.trainSequence(
            samples = samples,
            targetCorrectionDeg = 2f,
            learningRate = 0.0005f,
            deviceBias = 0f,
            chunkSize = 64,
        )
        assertTrue(result.loss.isFinite())
        assertTrue(result.predictedCorrectionDeg.isFinite())
        assertTrue(result.deviceBias.isFinite())
    }
}
